#!/bin/bash
# =============================================================================
# EchoMimicV3（jaw-lip 第一步：整脸音频驱动）独立运行环境 —— GPU#2 48G
#
# 为什么独立 venv（不动 ComfyUI 的）：
#   ① ComfyUI venv **没有 pip**；② 官方 requirements.txt 含 tensorflow==2.15.0 + retina-face==0.0.17，
#      会把 numpy/torch 栈污染（LatentSync 生产在同一台机器上跑，绝不能受影响）；
#   ③ 我们自己的 venv 可以随意升/降 diffusers，而 ComfyUI 的环境保持原样。
#   retina-face 的用途只是「找脸框」→ 我们改用 insightface + 已存在的 buffalo_l 模型（见 face_detect 补丁）。
#
# 用法（后台）：setsid nohup bash /opt/weaveora/mk_talk_env.sh > /opt/weaveora/logs/talk_env.log 2>&1 < /dev/null &
# 进度：tail -n 3 /opt/weaveora/logs/talk_env.log
# =============================================================================
set -u
ROOT=/opt/weaveora; V="$ROOT/envs/talk"; L="$ROOT/logs"; mkdir -p "$L"
exec 9>"$L/talk_env.lock"
if ! flock -n 9; then echo "[$(date +%H:%M:%S)] [skip] 已有 mk_talk_env 实例在跑"; exit 0; fi
echo $$ > "$L/talk_env.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }

UV=/opt/miniconda3/bin/uv
IDX="--index-url https://pypi.tuna.tsinghua.edu.cn/simple"
P="$V/bin/python"

log "=== 1) 建 venv（python 3.12，独立于 ComfyUI）==="
"$UV" venv "$V" --python 3.12 --seed || { log "FAIL venv 创建失败"; exit 1; }

log "=== 2) torch 2.7.1 + torchvision（与 ComfyUI 同版本，避开 2.14 死锁坑）==="
"$UV" pip install --python "$P" $IDX torch==2.7.1 torchvision || { log "FAIL torch 安装失败"; exit 1; }

log "=== 3) EchoMimicV3 依赖（去掉 tensorflow / retina-face）==="
"$UV" pip install --python "$P" $IDX \
  diffusers==0.35.1 transformers accelerate safetensors einops omegaconf timm tomesd \
  torchdiffeq torchsde decord numpy scikit-image opencv-python-headless imageio imageio-ffmpeg av \
  moviepy==2.2.1 librosa soundfile sentencepiece ftfy func_timeout onnxruntime insightface pillow \
  datasets albumentations tensorboard beautifulsoup4 gradio \
  || log "WARN 部分依赖安装失败（继续，逐个补）"

log "=== 4) 自检 ==="
"$P" - <<'PY'
mods = ["torch", "diffusers", "transformers", "insightface", "cv2", "librosa", "decord", "moviepy", "omegaconf", "einops"]
bad = []
for m in mods:
    try:
        __import__(m)
    except Exception as e:
        bad.append("%s: %s" % (m, type(e).__name__))
import torch
print("torch", torch.__version__, "cuda_avail", torch.cuda.is_available(), "ndev", torch.cuda.device_count())
print("MISSING:", bad if bad else "none")
PY
log "=== DONE $(date '+%F %T') ==="
