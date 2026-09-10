#!/usr/bin/env bash
# CosyVoice 依赖安装（WSL 内，后台可跑）
#
# 处理三个坑：
#  1. openai-whisper==20231117 的 setup.py 顶层 import pkg_resources，而 setuptools>=81
#     已移除该模块，build isolation 里必然失败 → 先装 setuptools<81，再用
#     --no-build-isolation 装 whisper。
#  2. tensorrt-cu12*(仅 load_trt=True 用) / deepspeed(仅训练) / gradio+fastapi+uvicorn(webui)
#     / tensorboard(训练) 与推理无关，跳过（省 ~4GB 与一次 deepspeed 源码编译）。
#  3. 文本正则化用 wetext（纯 Python）→ **不需要 conda 版 pynini**。
#
# 日志：/data/audio/pip_install.log
set -uo pipefail
export PIP_INDEX_URL=https://mirror.nju.edu.cn/pypi/web/simple
export PIP_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cu121
# 大轮子（nvidia-*/triton）已用 curl 预下到 /data/audio/wheels：
# pip 自己的下载器在 700MB 级文件上会卡死（0 B/s 且不超时），交给 curl 更可靠。
WHEELHOUSE=/data/audio/wheels
export PIP_DEFAULT_TIMEOUT=120
export PIP_DISABLE_PIP_VERSION_CHECK=1

CV=/data/audio/CosyVoice
PY=/opt/miniconda/envs/cosy/bin/python
PIP=/opt/miniconda/envs/cosy/bin/pip

cd "$CV" || exit 1

echo "[$(date +%H:%M:%S)] (a) 预装 setuptools<81 + wheel"
$PIP install -q "setuptools<81" wheel || echo "  (a) 失败，继续"

echo "[$(date +%H:%M:%S)] (b) 过滤 requirements（剔除 whisper，稍后单独装）"
grep -v -E '^(--|deepspeed|fastapi|fastapi-cli|gradio|tensorboard|tensorrt-cu12|uvicorn|openai-whisper)' \
  requirements.txt > /tmp/req_filtered.txt
echo "--- 保留 $(wc -l < /tmp/req_filtered.txt) 行 ---"

echo "[$(date +%H:%M:%S)] (c) 安装主依赖（含 torch==2.3.1）"
$PIP install --find-links "$WHEELHOUSE" -r /tmp/req_filtered.txt
rc1=$?
echo "[$(date +%H:%M:%S)] (c) rc=$rc1"

echo "[$(date +%H:%M:%S)] (d) 装 openai-whisper（--no-build-isolation）"
$PIP install --find-links "$WHEELHOUSE" --no-build-isolation "openai-whisper==20231117"
rc2=$?
echo "[$(date +%H:%M:%S)] (d) rc=$rc2"

echo "[$(date +%H:%M:%S)] (e) 校验关键包"
$PY - <<'PY'
import importlib
for m in ("torch", "torchaudio", "onnxruntime", "numpy", "hyperpyyaml", "modelscope",
          "transformers", "librosa", "soundfile", "whisper", "wetext", "inflect",
          "omegaconf", "pyworld", "conformer", "x_transformers"):
    try:
        mod = importlib.import_module(m)
        print("  OK  %-14s %s" % (m, getattr(mod, "__version__", "")))
    except Exception as e:
        print("  ERR %-14s %s" % (m, str(e)[:70]))
import torch
print("  cuda_available=%s  device=%s" % (torch.cuda.is_available(),
      torch.cuda.get_device_name(0) if torch.cuda.is_available() else "-"))
PY
echo "[$(date +%H:%M:%S)] DONE rc1=$rc1 rc2=$rc2"
