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
#   WEAVEORA_COMFY_URL=http://36.103.182.217:30250
#   WEAVEORA_TTS_URL  =http://36.103.182.217:30250/audio
#   WEAVEORA_MUSIC_URL=http://36.103.182.217:30250/bgm
#   WEAVEORA_FACE_URL =http://36.103.182.217:30250
# =============================================================================
set -uo pipefail

ROOT=/home/dataset-local/weaveora
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
  start_bg "ComfyUI(:8001)" "$LOGD/comfyui.log" \
    "$VENV_PY" "$CX/main.py" --listen 0.0.0.0 --port 8001
fi

# ---------- TTS :8091 ----------
if up 8091; then
  log "TTS :8091 已在监听，跳过"
else
  export WEAVEORA_COSYVOICE_DIR="$ROOT/audio/CosyVoice"
  export WEAVEORA_COSYVOICE_MODEL="pretrained_models/CosyVoice2-0.5B"
  export WEAVEORA_COSYVOICE_SFT_MODEL="pretrained_models/CosyVoice-300M-SFT"
  export WEAVEORA_TTS_DEFAULT_VOICE="中文女"
  export WEAVEORA_TTS_PRELOAD=1
  export WEAVEORA_TTS_ALIGN=0
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

# ---------- 边缘网关 :8000 ----------
# 必须最后起（依赖上面各服务）
if up 8000; then
  log "网关 :8000 已在监听，跳过"
else
  cd "$ROOT" || exit 1
  start_bg "EdgeProxy(:8000)" "$LOGD/edge_proxy.log" \
    "$VENV_PY" "$ROOT/edge_proxy.py" --port 8000
fi

log "---------------- 监听汇总 ----------------"
for p in 8000 8001 8091 8093; do
  if up $p; then log "  :$p  LISTEN"; else log "  :$p  --"; fi
done
log "================ services_up DONE ================"
