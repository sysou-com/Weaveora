#!/bin/bash
# =============================================================================
# Weaveora 第一批模型下载 —— GPU 服务器 #2（root@<GPU公网地址:ssh端口>, Ubuntu 24.04, RTX 4090 48G）
#
# 规则（Weaveora.md §0.2「大文件下载铁律」）：
#   · ≥200 MiB → gpu_model_downloader.js（**10 路 Range 分片 + .meta.json 断点续传 + .done**）
#   · <200 MiB → curl -L -C -
#   · **必须后台静默启动**（setsid nohup），不得前台阻塞；只出进度日志
#
# 与 GPU #1（<GPU#1公网地址:ssh端口>）的差异（本机实测 2026-09-14）：
#   · ModelScope 单连 10.7 MB/s（#1 是 3.2）→ 主线源
#   · **GitHub 直连可用 2.4 MB/s**；ghfast.top 在本机**不通**（#1 可用）→ 改用直连
#   · aifasthub 1.2 MB/s（LatentSync 权重）
#
# 用法（在 GPU #2 上，后台）：
#   setsid nohup bash /opt/weaveora/dl_first_batch.sh > /opt/weaveora/logs/dl.log 2>&1 < /dev/null &
# =============================================================================
set -u

ROOT=/opt/weaveora
export PATH="$ROOT/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl
NODE=node
DL="$ROOT/gpu_model_downloader.js"

MS=https://www.modelscope.cn/models
API=https://modelscope.cn/api/v1/models
AF=https://aifasthub.com
GH=https://github.com

CV2="$ROOT/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B"
CVM="$ROOT/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT"
LS="$ROOT/latentsync"
MOD="$ROOT/models"

log() { echo "[$(date '+%F %T')] $*"; }

# ---- 大文件（≥200 MiB）：10 路 Range + 断点续传（后台静默，只出进度）----
big() {
  local url="$1" out="$2" th="${3:-10}"
  mkdir -p "$(dirname "$out")"
  if [ -s "$out" ] && [ -f "$out.done" ]; then log "SKIP  $(basename "$out")（已完成）"; return 0; fi
  log "BIG   $(basename "$out")  →  $out"
  $NODE "$DL" "$url" "$out" "$th"
  log "EXIT=$?  $(basename "$out")  $(stat -c%s "$out" 2>/dev/null) bytes"
}

# ---- 小文件（<200 MiB）：curl 续传 ----
small() {
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  if [ -s "$out" ]; then log "SKIP  $(basename "$out")"; return 0; fi
  curl -sL -C - --retry 5 --retry-delay 3 --connect-timeout 30 -o "$out" "$url"
  log "SMALL exit=$?  $(basename "$out")  $(stat -c%s "$out" 2>/dev/null) bytes"
}

log "================ 第一批下载 START（GPU#2）================"
df -h "$ROOT" | tail -1

# ---------------- B. 配乐 ACE-Step（9.34 GiB，ComfyUI 宿主）----------------
big "$API/Comfy-Org/ace_step_1.5_ComfyUI_files/repo?Revision=master&FilePath=checkpoints%2Face_step_1.5_turbo_aio.safetensors" \
    "$MOD/checkpoints/ace_step_1.5_turbo_aio.safetensors" 10

# ---------------- C. 配音 CosyVoice2-0.5B（3.8 GiB）----------------
big "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=llm.pt"                          "$CV2/llm.pt" 10
big "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Fmodel.safetensors" "$CV2/CosyVoice-BlankEN/model.safetensors" 10
big "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=speech_tokenizer_v2.onnx"        "$CV2/speech_tokenizer_v2.onnx" 10
big "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=flow.pt"                         "$CV2/flow.pt" 10
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=hift.pt"                              "$CV2/hift.pt"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=campplus.onnx"                        "$CV2/campplus.onnx"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=cosyvoice2.yaml"                      "$CV2/cosyvoice2.yaml"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Fvocab.json"         "$CV2/CosyVoice-BlankEN/vocab.json"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Fmerges.txt"         "$CV2/CosyVoice-BlankEN/merges.txt"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Ftokenizer_config.json" "$CV2/CosyVoice-BlankEN/tokenizer_config.json"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Fconfig.json"        "$CV2/CosyVoice-BlankEN/config.json"
small "$API/iic/CosyVoice2-0.5B/repo?Revision=master&FilePath=CosyVoice-BlankEN%2Fgeneration_config.json" "$CV2/CosyVoice-BlankEN/generation_config.json"

# ---------------- C2. 配音 CosyVoice-300M-SFT（2.2 GiB，7 内置音色）----------------
big "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=llm.pt"                  "$CVM/llm.pt" 10
big "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=speech_tokenizer_v1.onnx" "$CVM/speech_tokenizer_v1.onnx" 10
big "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=flow.pt"                 "$CVM/flow.pt" 10
small "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=hift.pt"       "$CVM/hift.pt"
small "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=campplus.onnx" "$CVM/campplus.onnx"
small "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=spk2info.pt"   "$CVM/spk2info.pt"
small "$API/iic/CosyVoice-300M-SFT/repo?Revision=master&FilePath=cosyvoice.yaml" "$CVM/cosyvoice.yaml"

# ---------------- D. 对口型 LatentSync 1.6（5.32 GiB）----------------
big "$AF/ByteDance/LatentSync-1.6/resolve/main/latentsync_unet.pt"                     "$LS/latentsync_unet.pt" 10
big "$AF/stabilityai/sd-vae-ft-mse/resolve/main/diffusion_pytorch_model.safetensors"   "$LS/vae/diffusion_pytorch_model.safetensors" 10
big "$GH/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"                 "$LS/_dl/buffalo_l.zip" 10
small "$AF/ByteDance/LatentSync-1.6/resolve/main/whisper/tiny.pt" "$LS/whisper/tiny.pt"
small "$AF/ByteDance/LatentSync-1.6/resolve/main/config.json"     "$LS/config.json"
small "$AF/stabilityai/sd-vae-ft-mse/resolve/main/config.json"    "$LS/vae/config.json"
small "$AF/ByteDance/LatentSync-1.6/resolve/main/stable_syncnet.pt" "$LS/stable_syncnet.pt"

log "================ 下载阶段结束 ================"
find "$ROOT/latentsync" "$ROOT/models" "$ROOT/audio" -type f \
  \( -name '*.safetensors' -o -name '*.pt' -o -name '*.onnx' -o -name '*.zip' -o -name '*.yaml' \) \
  -printf '%10s  %p\n' 2>/dev/null | sort -rn | head -40
df -h "$ROOT" | tail -1
log "ALL_DONE"
