#!/bin/bash
# =============================================================================
# Weaveora 第一批模型下载（云 API 方案：仅 配音 voice / 配乐 bgm / 对口型 lipsync）
# 目标机：新 GPU 服务器（RTX 4090 24G, Ubuntu 20.04, batchcom）
# 规则：Weaveora.md §0.2「大文件下载铁律」
#   - >=200 MiB 一律 gpu_model_downloader.js（10 路 Range 分片 + .meta.json 断点续传）
#   - <200 MiB 允许 curl -L -C -
#   - 本脚本必须后台静默启动，不得前台阻塞
# 本批不含：stable_syncnet.pt（推理不加载）、Whisper base.pt（第二批）
# =============================================================================
set -u

export PATH="$HOME/opt/node-v20.18.0-linux-x64/bin:$PATH"
export WEAVEORA_CURL=curl
NODE=node
DL="$HOME/weaveora/gpu_model_downloader.js"

ROOT=/home/dataset-local/weaveora
MS=https://www.modelscope.cn/models
AF=https://aifasthub.com
GH=https://ghfast.top/https://github.com

CV2="$ROOT/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B"
CVM="$ROOT/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT"
LS="$ROOT/latentsync"
MOD="$ROOT/models"

log() { echo "[$(date '+%F %T')] $*"; }

# ---- 大文件（>=200 MiB）：10 路 Range + 断点续传 ----
big() {
  local url="$1" out="$2" th="${3:-10}"
  mkdir -p "$(dirname "$out")"
  log "BIG   $(basename "$out")  ->  $out"
  $NODE "$DL" "$url" "$out" "$th"
  log "EXIT=$?  $(basename "$out")"
}

# ---- 小文件（<200 MiB）：curl 续传 ----
small() {
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  if [ -s "$out" ]; then log "SKIP  $(basename "$out")"; return 0; fi
  curl -sL -C - --retry 5 --retry-delay 3 --connect-timeout 30 -o "$out" "$url"
  log "SMALL exit=$?  $(basename "$out")  $(stat -c%s "$out" 2>/dev/null) bytes"
}

log "================ 第一批下载 START ================"
df -h /home/dataset-local | tail -1

# ---------------- B. 配乐 ACE-Step (9.34 GiB) ----------------
big "$MS/Comfy-Org/ace_step_1.5_ComfyUI_files/resolve/master/checkpoints/ace_step_1.5_turbo_aio.safetensors" \
    "$MOD/checkpoints/ace_step_1.5_turbo_aio.safetensors" 10

# ---------------- D. 对口型 LatentSync (5.32 GiB) ----------------
big "$AF/ByteDance/LatentSync-1.6/resolve/main/latentsync_unet.pt" \
    "$LS/latentsync_unet.pt" 10
big "$AF/stabilityai/sd-vae-ft-mse/resolve/main/diffusion_pytorch_model.safetensors" \
    "$LS/vae/diffusion_pytorch_model.safetensors" 10
big "$GH/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip" \
    "$LS/_dl/buffalo_l.zip" 10

small "$AF/ByteDance/LatentSync-1.6/resolve/main/whisper/tiny.pt" "$LS/whisper/tiny.pt"
small "$AF/ByteDance/LatentSync-1.6/resolve/main/config.json"    "$LS/config.json"
small "$AF/stabilityai/sd-vae-ft-mse/resolve/main/config.json"   "$LS/vae/config.json"

# ---------------- C. 配音 CosyVoice2-0.5B (3.8 GiB) ----------------
big "$MS/iic/CosyVoice2-0.5B/resolve/master/llm.pt" \
    "$CV2/llm.pt" 10
big "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/model.safetensors" \
    "$CV2/CosyVoice-BlankEN/model.safetensors" 10
big "$MS/iic/CosyVoice2-0.5B/resolve/master/speech_tokenizer_v2.onnx" \
    "$CV2/speech_tokenizer_v2.onnx" 10
big "$MS/iic/CosyVoice2-0.5B/resolve/master/flow.pt" \
    "$CV2/flow.pt" 10

small "$MS/iic/CosyVoice2-0.5B/resolve/master/hift.pt"                            "$CV2/hift.pt"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/campplus.onnx"                      "$CV2/campplus.onnx"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/cosyvoice2.yaml"                    "$CV2/cosyvoice2.yaml"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/vocab.json"       "$CV2/CosyVoice-BlankEN/vocab.json"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/merges.txt"       "$CV2/CosyVoice-BlankEN/merges.txt"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/tokenizer_config.json" "$CV2/CosyVoice-BlankEN/tokenizer_config.json"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/config.json"      "$CV2/CosyVoice-BlankEN/config.json"
small "$MS/iic/CosyVoice2-0.5B/resolve/master/CosyVoice-BlankEN/generation_config.json" "$CV2/CosyVoice-BlankEN/generation_config.json"

# ---------------- C2. 配音 CosyVoice-300M-SFT (2.2 GiB, 7 内置音色) ----------------
big "$MS/iic/CosyVoice-300M-SFT/resolve/master/llm.pt" \
    "$CVM/llm.pt" 10
big "$MS/iic/CosyVoice-300M-SFT/resolve/master/speech_tokenizer_v1.onnx" \
    "$CVM/speech_tokenizer_v1.onnx" 10
big "$MS/iic/CosyVoice-300M-SFT/resolve/master/flow.pt" \
    "$CVM/flow.pt" 10

small "$MS/iic/CosyVoice-300M-SFT/resolve/master/hift.pt"           "$CVM/hift.pt"
small "$MS/iic/CosyVoice-300M-SFT/resolve/master/campplus.onnx"     "$CVM/campplus.onnx"
small "$MS/iic/CosyVoice-300M-SFT/resolve/master/spk2info.pt"       "$CVM/spk2info.pt"
small "$MS/iic/CosyVoice-300M-SFT/resolve/master/cosyvoice.yaml"    "$CVM/cosyvoice.yaml"

log "================ 下载阶段结束 ================"
log "--- 结果 ---"
find "$ROOT" -type f \( -name '*.safetensors' -o -name '*.pt' -o -name '*.onnx' -o -name '*.zip' -o -name '*.yaml' \) \
  -printf '%10s  %p\n' 2>/dev/null | sort -rn
df -h /home/dataset-local | tail -1
log "ALL_DONE"
