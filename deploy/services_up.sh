#!/bin/bash
# =============================================================================
# Weaveora 服务编排（新 GPU 服务器）—— 幂等，可重复执行
#
# 端口规划（公网只有 30250 → 容器 8000，故必须网关做路径分发）：
#   8000  边缘网关 edge_proxy.py   ← 平台映射的公网端口（对外唯一的入口）
#   8001  ComfyUI                  (配乐 / 对口型 / 出图 宿主)
#   8091  tts_server.py            (配音 /tts + 转写 /transcribe)
#   8092  music_server.py          (配乐 HTTP 兜底，默认不起；主线走 ComfyUI)
#   8093  face_server.py           (人脸 /face/probe /face/embed)
#
# 对外 URL（给 worker 用）：
#   WEAVEORA_COMFY_URL=http://<GPU公网地址:端口>
#   WEAVEORA_TTS_URL  =http://<GPU公网地址:端口>/audio
#   WEAVEORA_MUSIC_URL=http://<GPU公网地址:端口>/bgm
#   WEAVEORA_FACE_URL =http://<GPU公网地址:端口>
# =============================================================================
set -uo pipefail

# 根目录可配：GPU#1（容器）=/home/dataset-local/weaveora（默认）；GPU#2（VM）=/opt/weaveora
ROOT=${WEAVEORA_ROOT:-/home/dataset-local/weaveora}
LOGD=$ROOT/logs
CX=$ROOT/ComfyUI
VENV_PY=$CX/venv/bin/python
COSY_PY=$ROOT/envs/cosy/bin/python
mkdir -p "$LOGD"
LOG=$LOGD/services_up.log

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

up() { ss -ltn 2>/dev/null | grep -q ":$1 "; }

start_bg() {  # $1=tag $2=logfile 剩下的为命令
  local tag="$1" lf="$2"; shift 2
  setsid nohup "$@" >> "$lf" 2>&1 < /dev/null &
  log "  已拉起 $tag pid=$!"
}

log "================ services_up START ================"

# ---------- ComfyUI :8001 ----------
if up 8001; then
  log "ComfyUI :8001 已在监听，跳过"
else
  cd "$CX" || exit 1
  # 2026-09-19 调整（原配置来自 24G 卡时代）：
  #   原 --disable-smart-memory --cache-ram 32 来由：Wan2.2 I2V 双专家 fp8 各 13.3GiB，
  #   24G 卡不主动释放 → 两专家同驻 26.6GiB 直接 OOM。
  #   现在：① 卡是 48G；② 任务按 kind 串行（claim 同 kind 优先）→ 一批内只有一套重资产；
  #   ③ cache-ram 32 在 62GiB 机器上会让两套重资产同时进 RAM → 曾触发内核 OOM killer。
  #   ⇒ smart memory 打开（去掉 --disable-smart-memory）+ cache-ram 8：
  # ★ 2026-09-23 23:2x 再调整（cache-ram 8 → 16 16）：
  #   事故：A/B 里 flux2(33GB) 已被 pin → 紧接着加载 qwen-edit(19GB + 8.7GB 文本编码器)
  #        → anon-rss 45.3GiB → 内核 OOM killer 杀了 ComfyUI（weaveora-stack 变 failed，5 端口全 000）。
  #   根因（盒上现网源码）：model_management.py:720 `ensure_pin_budget()` 的余量 = max(RAM_CACHE_HEADROOM/2, 2GB)，
  #        而 RAM_CACHE_HEADROOM ← --cache-ram 的**第一个值**（8）⇒ 只有 4GB 余量时才去卸旧模型；
  #        且 psutil.available 把可回收的 page cache 也算进去 ⇒ 它以为还有余量，匿名内存却已顶死。
  #        第二个值（inactive/pin 阈值）默认 = min(128, 总内存) = 47GB（main.py:356），显式给 16 更可控。
  #   ⇒ 16 16：pin 预算余量 4GB→8GB（换模型前更早卸旧的）；代价 = 缓存更早被清、重复出图略慢。
  #   未改 --disable-smart-memory（它会让 VRAM 更激进地往 RAM 卸，方向相反）/ --cache-none（铁律③ 禁）。
  #     批内模型常驻（省掉每张重搬 ~36GB），跨 kind 由 smart memory 自然淘汰一次。
  # ★ 2026-09-28（用户裁定：查为什么出图变慢）第二个值 16 → 24：
  #   实测每张图都要重新装载「17.18GB 文本编码器 + 33.81GB 主干」（日志每张图前都出现这两行）；
  #   两件合计 51.1GB > 49.1GB 显存 ⇒ 必然互相挤出；而 17.18GB 的 TE > 16GB 的 inactive/pin 阈值
  #   ⇒ 每张图都被丢出 RAM、下一张再从盘重读（这就是「刚部署时快、现在慢」的主因）。
  #   抬到 24 后 TE 可常驻 RAM ⇒ 换装变成 RAM→VRAM 拷贝（秒级）。
  #   RAM 账：总 47GB、进程+其它服务 ~15GB、TE 17.18GB ⇒ 24 仍留 ~10GB 余量（不回到 32，避免 09-23 那次内核 OOM）。
  #   注意：主干 33.81GB 仍无法与 TE 同时常驻（51.1 > 47GB RAM）⇒ 它的重装省不掉；根治要换更小模型/TE。
  # --reserve-vram 0.5：给 CUDA 上下文 / VAE 解码留 0.5GiB 余量。
  start_bg "ComfyUI(:8001)" "$LOGD/comfyui.log" \
    "$VENV_PY" "$CX/main.py" --listen 0.0.0.0 --port 8001 \
    --cache-ram 16 24 \
    --reserve-vram 0.5
fi

# ---------- TTS :8091 ----------
if up 8091; then
  log "TTS :8091 已在监听，跳过"
else
  export WEAVEORA_COSYVOICE_DIR="$ROOT/audio/CosyVoice"
  export WEAVEORA_COSYVOICE_MODEL="pretrained_models/CosyVoice2-0.5B"
  export WEAVEORA_COSYVOICE_SFT_MODEL="pretrained_models/CosyVoice-300M-SFT"
  export WEAVEORA_TTS_DEFAULT_VOICE="中文女"
  export WEAVEORA_TTS_PRELOAD=0   # 48G 卡为了让 A14B motion 独占显存（实测峰值 47.3GiB），TTS 改为按需加载
  export WEAVEORA_TTS_ALIGN=0
  # 显存仲裁：TTS 常驻占 ~7GiB，与 Wan2.2 14B 双专家无法共存。
  #   worker 在出视频前会看 ComfyUI 的显存余量，不够时 POST /unload 让 TTS 卸载模型
  #   （tts_server 的 /unload；下次配音请求会自动懒加载回来）。所以 PRELOAD 可以保持 1。
  export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
  # wetext 的 FST 模型是运行时用 modelscope.snapshot_download 拉的（约 52MB / 38 文件）。
  # 默认缓存在 ~/.cache/modelscope（overlay，重启即丢）-> 指到持久盘，避免每次重启后
  # 第一个克隆音色任务都要重下（实测拖慢 3-4 分钟）。
  export MODELSCOPE_CACHE="$ROOT/cache/modelscope"
  cd "$ROOT/audio/CosyVoice" || exit 1
  start_bg "TTS(:8091)" "$LOGD/audio_tts.log" "$COSY_PY" "$ROOT/audio/tts_server.py" 8091
fi

# ---------- Face :8093 ----------
if up 8093; then
  log "Face :8093 已在监听，跳过"
else
  export WEAVEORA_LATENTSYNC_DIR="$CX/custom_nodes/ComfyUI-LatentSyncWrapper"
  export WEAVEORA_FACE_PORT=8093
  export WEAVEORA_FACE_DEVICE="${WEAVEORA_FACE_DEVICE:-cpu}"
  cd "$ROOT/face" || exit 1
  start_bg "Face(:8093)" "$LOGD/face.log" \
    "$VENV_PY" "$ROOT/face/face_server.py" --port 8093 --device "$WEAVEORA_FACE_DEVICE"
fi

# ---------- 整脸口型 talk :8094（EchoMimicV3 / jaw-lip）----------
# 独立 venv（envs/talk）与 ComfyUI 隔离；喊叫/尖叫/吟唱这类「嘴大张」镜用它替代 LatentSync。
# ⚠️ 之前只有手工 start_talk.sh，机器一重启该服务就消失（2026-09-15 维护后实测 8094 未监听）。
if up 8094; then
  log "talk :8094 已在监听，跳过"
else
  cd "$ROOT" || exit 1
  start_bg "talk(:8094)" "$LOGD/talk_server.log" "$ROOT/envs/talk/bin/python" "$ROOT/talk/talk_server.py"
fi

# ---------- 边缘网关（端口可配：WEAVEORA_GATEWAY_PORT，缺省 8000）----------
# GPU#2（180.127.11.166）平台给的公网映射是 10588→容器 8800，故那边要设 8800。
GWPORT=${WEAVEORA_GATEWAY_PORT:-8000}
# 必须最后起（依赖上面各服务）
if up "$GWPORT"; then
  log "网关 :$GWPORT 已在监听，跳过"
else
  cd "$ROOT" || exit 1
  start_bg "EdgeProxy(:$GWPORT)" "$LOGD/edge_proxy.log" \
    "$VENV_PY" "$ROOT/edge_proxy.py" --port "$GWPORT"
fi

# ---------- 预热（后台、非阻塞；队列非空会自动跳过）----------
# 目的：把出图那套大权重先读进显存/内存，用户的第一个任务不再付冷启动成本
# （2026-09-15 实测：重启后首张图曾要 12 分钟）。可用 WEAVEORA_WARMUP=off 关闭。
setsid nohup bash "$ROOT/warmup.sh" >> "$LOGD/warmup.log" 2>&1 < /dev/null &

log "---------------- 监听汇总 ----------------"
for p in "$GWPORT" 8001 8091 8093 8094; do
  if up $p; then log "  :$p  LISTEN"; else log "  :$p  --"; fi
done
log "================ services_up DONE ================"
