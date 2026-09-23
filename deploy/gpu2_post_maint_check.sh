#!/usr/bin/env bash
# =============================================================================
# GPU#2 维护/重建后的回归自检（**只做只读检查，不跑推理、不出验图**）
#
# 为什么需要：2026-09-15 16:20 实测 GPU 机进入维护/重建态 ——
#   · ssh 主机密钥变了、原公钥被拒（Permission denied (publickey)）
#   · 公网两个入口 10558 / 10588 均不可达（000）
#   重建后必须逐项确认「持久卷还在 / 服务起来了 / 模型没丢 / 网关路由齐 / 配置页地址对」，
#   否则会以「任务莫名失败」的形式暴露，排查成本极高。
#   2026-09-15 实测踩到的两类问题：① 数据盘挂载点从 /media/vipuser/addDisk 变成 /addDisk
#   → 既有软链全部悬空（Phase2/3 权重不可用）；② services_up.sh 缺 talk 块 → 重启后 8094 消失。
#
# 用法（在 GPU 机上）：
#   bash /opt/weaveora/post_maint_check.sh            # 本地检查
#   GW=http://<GPU公网地址:端口> bash /opt/weaveora/post_maint_check.sh   # 顺带查网关路由
# 退出码：0=全绿；1=有 FAIL（逐条看输出）
# =============================================================================
set -u
ROOT=/opt/weaveora
GW="${GW:-}"
FAIL=0
ok(){ echo "  OK   $*"; }
bad(){ echo "  FAIL $*"; FAIL=$((FAIL+1)); }
warn(){ echo "  WARN $*"; }

echo "== 1/6 主机与持久卷 =="
hostname; uptime | head -1
for d in "$ROOT" "$ROOT/models" "$ROOT/envs/talk" "$ROOT/envs/cosy" "$ROOT/ComfyUI" "$ROOT/latentsync" "$ROOT/audio/CosyVoice/pretrained_models"; do
  [ -e "$d" ] && ok "存在 $d" || bad "缺失 $d（持久卷没挂上或路径变了）"
done
df -h / "$(dirname "$ROOT")" 2>/dev/null | tail -3

echo "== 2/6 服务与端口（ComfyUI:8001 网关:8800 配音:8091 人脸:8093 整脸口型:8094）=="
systemctl is-active weaveora-stack.service 2>/dev/null | sed 's/^/  stack: /'
for p in 8001 8800 8091 8093 8094; do
  if ss -ltn 2>/dev/null | grep -q ":$p "; then ok "端口 $p 在听"; else bad "端口 $p 未监听（可跑 $ROOT/services_up.sh）"; fi
done
curl -s -m 8 -o /dev/null -w "  ComfyUI /system_stats = %{http_code}\n" http://127.0.0.1:8001/system_stats
curl -s -m 8 -o /dev/null -w "  TTS /health          = %{http_code}\n" http://127.0.0.1:8091/health
curl -s -m 8 -o /dev/null -w "  talk /health         = %{http_code}\n" http://127.0.0.1:8094/health

echo "== 3/6 关键模型（字节数 + .done 标记）=="
echo "  （含数据盘上的 Phase2/3 权重；若断链先看挂载点：df -h /addDisk）"
chk_nodone(){ f="$ROOT/$1"; want="$2"; got=$(stat -Lc%s "$f" 2>/dev/null || echo 0)
  if [ "$got" = "$want" ]; then ok "$(basename "$f") $got"; else bad "$(basename "$f") 期望 $want 实际 $got"; fi; }
chk(){ f="$ROOT/$1"; want="$2"; got=$(stat -Lc%s "$f" 2>/dev/null || echo 0)
  # ★ 2026-09-23：`.done` 要跟着**软链解析后的真文件**找。
  #   为什么：数据盘上的权重是 `/opt/weaveora/models/xxx -> /addDisk/...` 软链，下载器把 .done 写在
  #   **真文件旁边**（/addDisk/...），所以 `[ -f "$f.done" ]` 永远为假 ⇒ 会出现
  #   「字节数完全正确却报 FAIL（缺 .done）」的假警报（本次实测）。
  real="$(readlink -f "$f" 2>/dev/null || echo "$f")"
  if [ "$got" = "$want" ] && { [ -f "$f.done" ] || [ -f "$real.done" ]; }; then ok "$(basename "$f") $got"
  else bad "$(basename "$f") 期望 $want 实际 $got$( { [ -f "$f.done" ] || [ -f "$real.done" ]; } || echo '（缺 .done）')"; fi; }
chk models/diffusion_models/qwen_image_fp8_e4m3fn.safetensors 20430635136
chk models/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors 9384670680
chk models/vae/qwen_image_vae.safetensors 253806246
chk models/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors 1698951104
chk models/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors 14294742832
chk models/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors 14294742832
chk models/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors 6735906897
chk models/vae/wan_2.1_vae.safetensors 253815318
chk models/checkpoints/ace_step_1.5_turbo_aio.safetensors 10025478736
chk latentsync/latentsync_unet.pt 5072222488
chk latentsync/vae/diffusion_pytorch_model.safetensors 334643276
chk_nodone latentsync/whisper/tiny.pt 75572083   # 该文件历史清单里没有 .done 标记，只查字节
chk models/echo_mimic/transformer/diffusion_pytorch_model.safetensors 3414541616
chk models/echo_mimic/Wan2.1-Fun-V1.1-1.3B-InP/models_t5_umt5-xxl-enc-bf16.pth 11361920418
# ★ 2026-09-23：FLUX.2 [dev] 出图通路（A 档：fp8mixed + Mistral-3 fp8 编码器）。
#   为什么必须进这张清单：fp8mixed 主模型在 /addDisk（软链进 models/），**软链一断 ComfyUI 会静默
#   回退/读另一份同名权重** ⇒ 症状是「图出得来但完全不对，且不报错」（CLAUDE.md §四-2）。
chk models/diffusion_models/flux2_dev_fp8mixed.safetensors 35455599592
chk models/text_encoders/mistral_3_small_flux2_fp8.safetensors 18034640095
chk models/vae/flux2-vae.safetensors 336213556
chk models/vae/full_encoder_small_decoder.safetensors 249519092
chk models/loras/Flux_2-Turbo-LoRA_comfyui.safetensors 2760814880
# 同名多副本检查（**只数真文件**：同名 >1 份就可能用错权重；模型目录里的软链不算份）。
# 用 -type f 而不是 -L：软链指过去的那份就是真文件本身，不能重复计数（否则正常布局也会误报 2 份）。
for base in flux2_dev_fp8mixed mistral_3_small_flux2_fp8 full_encoder_small_decoder; do
  n=$( { find / -xdev -type f -name "$base.safetensors" 2>/dev/null; \
         find /addDisk -xdev -type f -name "$base.safetensors" 2>/dev/null; } | wc -l )
  [ "$n" -le 1 ] && ok "$base 无同名多副本（$n 份真文件）" || bad "$base 存在 $n 份真文件 —— 先核实软链指向，别直接出图"
done
# 节点需要的权重软链（重建后最容易丢）
for l in "$ROOT/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/latentsync_unet.pt" \
         "$ROOT/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/whisper/tiny.pt"; do
  if [ -L "$l" ] && [ -r "$l" ]; then ok "软链可读 $(basename "$l")"; else warn "软链缺失/不可读 $l（节点会去 HF 下载 → 卡队列，见指南坑 30）"; fi
done

echo "== 4/6 依赖与防呆（venv / 节点自装标记 / HF 防直连）=="
V="$ROOT/ComfyUI/venv/bin/python"
[ -x "$V" ] && ok "ComfyUI venv python 存在" || bad "缺 $V"
"$V" -c "import accelerate, diffusers, transformers, av, imageio" 2>/dev/null && ok "accelerate/diffusers/transformers/av/imageio 均可导入" \
  || bad "有依赖缺失（对口型会崩，见指南坑 29/30）"
[ -f ~/.latentsync16_dependencies_installed ] && ok "节点自装标记存在" || bad "缺 ~/.latentsync16_dependencies_installed（节点会 pip 自装并可能抛错）"
grep -q "weaveora-hf-guard" /etc/hosts 2>/dev/null && ok "HF 防直连 hosts 条目在" || warn "缺 weaveora-hf-guard（节点可能去 HF 下 5GB 卡死队列）"
if [ -d "$ROOT/workflows" ]; then ls "$ROOT/workflows" | sed 's/^/    workflow: /'; else warn "本机无 $ROOT/workflows —— 文生图工作流 JSON 在 **worker 机器**（VPS：/opt/weaveora/workflows/），不是 GPU 机，属正常"; fi

echo "== 5/6 网关路由${GW:+（GW=$GW）}=="
if [ -n "$GW" ]; then
  curl -s -m 10 "$GW/__edge/health" | head -c 300; echo
  for path in /system_stats /audio/health /face/health /talk/health; do
    printf "  %-16s = " "$path"; curl -s -m 10 -o /dev/null -w "%{http_code}\n" "$GW$path"
  done
  echo "  期望：/system_stats 200、/audio/health 200、/talk/health 200（/face/health 允许 404）"
else
  warn "未传 GW，跳过公网网关检查（例：GW=http://<GPU公网地址:端口> bash $0）"
fi

echo "== 6/6 结论 =="
if [ "$FAIL" = "0" ]; then
  echo "  全部通过 ✅ 可进入业务验证：① 配置页确认「GPU 服务器地址+端口」；② 图像引擎切 GPU；"
  echo "  ③ 跑一镜三步链路（文生图 → motion → 对口型）+ 一个 kind=talk 任务。"
  exit 0
fi
echo "  有 $FAIL 项 FAIL ❌ 先按上面逐条修（多数可在 $ROOT/services_up.sh / 指南 §4 找到处置）"
exit 1
