#!/bin/bash
# =============================================================================
# GPU · FLUX.2 [dev] 出图通路下载（A 档：fp8mixed 主模型 + Mistral-3 fp8 编码器）
#   遵守 Weaveora.md §0.2：10 路 Range 分片 + .meta.json 断点续传 + 后台静默 + 字节/头结构校验
#   总计 52.93 GiB（ModelScope 官方镜像，免登录；HF 上 FLUX.2-dev 是 gated）
#   来源与字节数逐项对齐 docs/方案-FLUX2dev-替换Qwen出图通路-2026-09-23.md §3
#
#   用法：
#     # 全量（主模型 33.02 GiB 在 /addDisk，其余 19.91 GiB 在系统盘）
#     setsid nohup bash /opt/weaveora/dl_flux2.sh > /opt/weaveora/logs/dl_flux2.log 2>&1 </dev/null &
#     # 只下"系统盘那 4 件"（主模型已下好时）
#     REST_ONLY=1 setsid nohup bash /opt/weaveora/dl_flux2.sh > /opt/weaveora/logs/dl_flux2_rest.log 2>&1 </dev/null &
#   进度：tail -n 3 /opt/weaveora/logs/dl_flux2.log   （只做短查，不许 while 轮询）
#   叫停：kill $(cat /opt/weaveora/logs/dl_flux2.pid) （重跑自动续传）
#
#   ★ 落点为什么这样分：`/addDisk`（98 G 数据盘）在 2026-09-23 只剩 33 GiB —— 装得下主模型（33.02）
#     但装不下编码器；而系统盘 `/` 还剩 16 GiB ⇒ 编码器/VAE/LoRA 放系统盘真文件（无需软链）。
#     主模型放数据盘，再在 `models/diffusion_models/` 下建软链（ComfyUI 从软链目录读）。
# =============================================================================
set -u
ROOT=/opt/weaveora
export PATH="$ROOT/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl                 # 下载器默认 curl.exe（Windows 口径），Linux 必须覆盖
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; MK="$ROOT/models"; DD=/addDisk/weaveora/models; mkdir -p "$L" "$DD/diffusion_models"
exec 9>"$L/dl_flux2.lock"
if ! flock -n 9; then echo "[$(date +%H:%M:%S)] [skip] 已有 dl_flux2 实例"; exit 0; fi
echo $$ > "$L/dl_flux2.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
big(){ "$NODE" "$DL" "$1" "$2" "${3:-10}"; }
MSC="https://www.modelscope.cn/models"

FLUX="$MSC/Comfy-Org/flux2-dev/resolve/master/split_files"
DEC="$MSC/black-forest-labs/FLUX.2-small-decoder/resolve/master"

# ---- 空间预检（不够直接退出，别把盘写爆）--------------------------------------
avail_b(){ df -B1 "$1" | tail -1 | awk '{print $4}'; }
A_ADD=$(avail_b /addDisk); A_ROOT=$(avail_b /)
NEED_ADD=35455599592                       # 主模型 33.02 GiB
NEED_ROOT=21381187623                      # 编码器 16.80 + LoRA 2.57 + vae 0.31 + smalldec 0.23 = 19.91 GiB
log "预检：/addDisk 可用 $(numfmt --to=iec $A_ADD)（需 $(numfmt --to=iec $NEED_ADD)）｜/ 可用 $(numfmt --to=iec $A_ROOT)（需 $(numfmt --to=iec $NEED_ROOT)）"
if [ "${REST_ONLY:-0}" != "1" ] && [ "$A_ADD" -lt "$NEED_ADD" ]; then
  log "!! /addDisk 空间不够主模型 —— 先腾地方（见 docs/磁盘操作记录-2026-09-23-FLUX2部署.md §3，逐项确认后再删）"; exit 2
fi
if [ "$A_ROOT" -lt "$NEED_ROOT" ]; then
  log "!! 系统盘空间不够那 4 件（缺 $(( (NEED_ROOT - A_ROOT) / 1048576 )) MiB）—— 先腾地方（同上 §3）"; exit 2
fi

if [ "${REST_ONLY:-0}" != "1" ]; then
  log "=== [1/5] 主模型 flux2_dev_fp8mixed（33.02 GiB → /addDisk）==="
  big "$FLUX/diffusion_models/flux2_dev_fp8mixed.safetensors" "$DD/diffusion_models/flux2_dev_fp8mixed.safetensors" 10
  ln -sfn "$DD/diffusion_models/flux2_dev_fp8mixed.safetensors" \
          "$MK/diffusion_models/flux2_dev_fp8mixed.safetensors"
  log "     软链：$MK/diffusion_models/flux2_dev_fp8mixed.safetensors -> $(readlink -f "$MK/diffusion_models/flux2_dev_fp8mixed.safetensors")"
else
  log "=== REST_ONLY=1：跳过主模型（只下系统盘那 4 件）==="
fi

log "=== [2/5] 文本编码器 Mistral-3 fp8（16.80 GiB → 系统盘）==="
big "$FLUX/text_encoders/mistral_3_small_flux2_fp8.safetensors" "$MK/text_encoders/mistral_3_small_flux2_fp8.safetensors" 10

log "=== [3/5] FLUX.2 VAE（0.31 GiB）==="
big "$FLUX/vae/flux2-vae.safetensors" "$MK/vae/flux2-vae.safetensors" 10

log "=== [4/5] 全编码器+小解码器 VAE（0.23 GiB，官方模板当前默认）==="
big "$DEC/full_encoder_small_decoder.safetensors" "$MK/vae/full_encoder_small_decoder.safetensors" 10

log "=== [5/5] Turbo LoRA（2.57 GiB，8 步加速档）==="
big "$FLUX/loras/Flux_2-Turbo-LoRA_comfyui.safetensors" "$MK/loras/Flux_2-Turbo-LoRA_comfyui.safetensors" 10

# ---- 校验（字节 + safetensors 头结构；**不看 .done**）--------------------------
log "=== 校验（字节 + safetensors 头结构）==="
fail=0
chk(){ f="$1"; want="$2"
  got=$(stat -Lc%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  if ! python3 - "$f" <<'PY'
import json, struct, sys
with open(sys.argv[1], "rb") as fh:
    n = struct.unpack("<Q", fh.read(8))[0]
    if not (0 < n < 200_000_000):
        raise SystemExit(1)
    json.loads(fh.read(n))
PY
  then log "FAIL $(basename "$f") safetensors 头异常"; fail=$((fail+1)); return; fi
  log "OK   $(basename "$f")  $got"; }
if [ "${REST_ONLY:-0}" != "1" ]; then
  chk "$MK/diffusion_models/flux2_dev_fp8mixed.safetensors" 35455599592
fi
chk "$MK/text_encoders/mistral_3_small_flux2_fp8.safetensors" 18034640095
chk "$MK/vae/flux2-vae.safetensors"                           336213556
chk "$MK/vae/full_encoder_small_decoder.safetensors"          249519092
chk "$MK/loras/Flux_2-Turbo-LoRA_comfyui.safetensors"        2760814880
if [ "$fail" = "0" ]; then
  log "=== ALL_DONE $(date '+%F %T') ==="
  log "下一步：① bash $ROOT/post_maint_check.sh（应全绿）② 三个工作流 POST 试一枪 ③ A/B"
else
  log "=== FINISHED_WITH_ERRORS($fail) ==="
fi
exit $fail
