#!/bin/bash
# =============================================================================
# Weaveora 音频服务启动（新 GPU 服务器）
#   TTS   配音  :8091  tts_server.py  (CosyVoice2-0.5B + CosyVoice-300M-SFT)
#   Music 配乐  :8092  —— 主线走 ComfyUI 原生 ACE-Step 节点，此处不启动 music_server
# 环境：conda env cosy (Python 3.10)
# =============================================================================
set -uo pipefail

ROOT=/home/dataset-local/weaveora
COSY=$ROOT/audio/CosyVoice
# 持久环境优先；若不在则回退到兼容软链
if [ -x "$ROOT/envs/cosy/bin/python" ]; then
  PY="$ROOT/envs/cosy/bin/python"
else
  PY=/opt/anaconda3/envs/cosy/bin/python
fi
LOGD=$ROOT/logs
mkdir -p "$LOGD"

export WEAVEORA_COSYVOICE_DIR="$COSY"
export WEAVEORA_COSYVOICE_MODEL="pretrained_models/CosyVoice2-0.5B"
export WEAVEORA_COSYVOICE_SFT_MODEL="pretrained_models/CosyVoice-300M-SFT"
export WEAVEORA_TTS_DEFAULT_VOICE="中文女"
# 新服务器 24G 显存，无需省显存；预加载模型让首次任务不必等
export WEAVEORA_TTS_PRELOAD=1
# 对齐/转写需要 whisper——base.pt 属第二批，先关闭对齐
export WEAVEORA_TTS_ALIGN=0
# 单进程串行，避免两个 CUDA 上下文抢显存
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True

log() { echo "[$(date '+%F %T')] $*"; }

# 已在跑则跳过
if ss -ltn 2>/dev/null | grep -q ":8091"; then
  log "8091 已在监听，跳过启动"
  exit 0
fi

log "启动 TTS 服务 :8091 ..."
cd "$COSY" || exit 1
setsid nohup "$PY" "$ROOT/audio/tts_server.py" 8091 >> "$LOGD/audio_tts.log" 2>&1 < /dev/null &
log "已拉起 pid=$!  日志: $LOGD/audio_tts.log"
