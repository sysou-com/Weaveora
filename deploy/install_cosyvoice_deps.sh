#!/bin/bash
# CosyVoice 依赖安装（conda env cosy / Python 3.10）
# 输出直写日志（无管道缓冲），uv 加 --no-progress 便于逐行跟踪
# 要点：g++ 已装；pypi 官方源(Fastly)本机停滞 → 用清华镜像；whisper 需 setuptools<81
set -uo pipefail

UV=/opt/anaconda3/bin/uv
PY=/opt/anaconda3/envs/cosy/bin/python
CV=/home/dataset-local/weaveora/audio/CosyVoice
export UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple
export UV_NO_PROGRESS=1
UVPIP="$UV pip install --python $PY --no-progress"

log() { echo "[$(date '+%F %T')] $*"; }

log "===== CosyVoice 依赖安装 START ====="
log "python: $($PY -V 2>&1)"
cd "$CV" || { log "找不到 $CV"; exit 1; }

log "--- (a) setuptools<81 + wheel ---"
$UVPIP "setuptools<81" wheel
log "(a) rc=$?"

log "--- (b) 过滤 requirements ---"
grep -v -E '^(--|deepspeed|fastapi|fastapi-cli|gradio|tensorboard|tensorrt-cu12|uvicorn|openai-whisper)' \
  requirements.txt > /tmp/req_cosy.txt
log "  保留 $(grep -c . /tmp/req_cosy.txt) 行"

log "--- (c) 主依赖（torch 2.3.1 等）---"
$UVPIP -r /tmp/req_cosy.txt
rc1=$?
log "(c) rc=$rc1"

log "--- (d) openai-whisper ---"
$UV pip install --python "$PY" --no-progress --no-build-isolation "openai-whisper==20231117"
rc2=$?
log "(d) rc=$rc2"

log "--- (e) 校验 ---"
"$PY" - <<'PYEOF'
import importlib
for m in ("torch","torchaudio","onnxruntime","numpy","hyperpyyaml","modelscope",
          "transformers","librosa","soundfile","whisper","wetext","inflect",
          "omegaconf","pyworld","conformer","x_transformers"):
    try:
        mod = importlib.import_module(m)
        print("  OK  %-16s %s" % (m, getattr(mod, "__version__", "")))
    except Exception as e:
        print("  ERR %-16s %s" % (m, str(e)[:60]))
import torch
print("  cuda=%s device=%s" % (torch.cuda.is_available(),
      torch.cuda.get_device_name(0) if torch.cuda.is_available() else "-"))
PYEOF
log "DONE rc1=$rc1 rc2=$rc2"
