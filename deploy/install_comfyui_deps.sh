#!/bin/bash
# ComfyUI v0.34.0 依赖安装（新 GPU 服务器）
# 规则：后台静默，输出到日志
set -u

CX=/home/dataset-local/weaveora/ComfyUI
PIP="$CX/venv/bin/pip"
PY="$CX/venv/bin/python"

log() { echo "[$(date '+%F %T')] $*"; }

log "================ ComfyUI 依赖安装 START ================"
log "venv: $($PY -V 2>&1)"

log "--- 1/4 升级 pip ---"
"$PIP" install -q --upgrade pip setuptools wheel
log "exit=$?"

log "--- 2/4 安装 torch 2.7.1 + cu126 (基线与本机一致) ---"
"$PIP" install --index-url https://download.pytorch.org/whl/cu126 \
  torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1 \
  --timeout 60 --retries 8
log "torch exit=$?"

log "--- 3/4 安装 ComfyUI requirements ---"
cd "$CX"
"$PIP" install -r requirements.txt --timeout 60 --retries 8
log "requirements exit=$?"

log "--- 4/4 校验 ---"
"$PY" - <<'PYEOF'
import torch, sys
print("python :", sys.version.split()[0])
print("torch  :", torch.__version__)
print("cuda   :", torch.version.cuda, "| available:", torch.cuda.is_available())
if torch.cuda.is_available():
    print("device :", torch.cuda.get_device_name(0))
    print("capab  :", torch.cuda.get_device_capability(0))
PYEOF
log "DONE"
