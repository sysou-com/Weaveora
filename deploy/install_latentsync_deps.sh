#!/bin/bash
# LatentSync 节点依赖（ComfyUI venv）
# 输出直写日志（无管道缓冲）；pypi 官方源(Fastly)本机停滞 → 用清华镜像
set -uo pipefail

UV=/opt/anaconda3/bin/uv
VENV=/home/dataset-local/weaveora/ComfyUI/venv
PY="$VENV/bin/python"
NODE=/home/dataset-local/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper
export UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple
export UV_NO_PROGRESS=1
UVPIP="$UV pip install --python $PY --no-progress"

log() { echo "[$(date '+%F %T')] $*"; }

log "===== LatentSync 节点依赖 START ====="
log "安装前 torch: $($PY -c 'import torch;print(torch.__version__)' 2>&1)"

cd "$NODE" || { log "找不到 $NODE"; exit 1; }

log "--- (a) 节点 requirements.txt ---"
$UVPIP -r requirements.txt
log "(a) rc=$?"

log "--- (b) 文档额外依赖 ---"
$UVPIP insightface librosa pytorch_lightning onnx
log "(b) rc=$?"

log "--- (c) onnxruntime-gpu ---"
$UVPIP onnxruntime-gpu
log "(c) rc=$?"

log "--- (d) 校验 ---"
"$PY" - <<'PYEOF'
import importlib
for m in ("torch","torchvision","torchaudio","omegaconf","diffusers","einops","cv2",
          "mediapipe","decord","safetensors","soundfile","librosa","insightface",
          "pytorch_lightning","onnx","onnxruntime","face_alignment","DeepCache"):
    try:
        mod = importlib.import_module(m)
        print("  OK  %-18s %s" % (m, getattr(mod, "__version__", "")))
    except Exception as e:
        print("  ERR %-18s %s" % (m, str(e)[:60]))
import torch
print("  torch=%s cuda=%s avail=%s" % (torch.__version__, torch.version.cuda, torch.cuda.is_available()))
PYEOF
log "DONE"
