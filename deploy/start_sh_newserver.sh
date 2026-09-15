#!/bin/sh
# =============================================================================
# Weaveora 新 GPU 服务器启动脚本（平台 autostart，由 xfce4-terminal 拉起）
# 原版：/home/start.sh.orig（0.27.0 + base conda 环境 + 空 models）
# 本版：ComfyUI v0.34.0 + 独立 venv + 模型在持久盘 /home/dataset-local
# 端口：容器内 0.0.0.0:8000  ←→  公网 <GPU公网地址:端口>
# 日志：/home/dataset-local/weaveora/logs/comfyui.log
# =============================================================================
CX=/home/dataset-local/weaveora/ComfyUI
LOGD=/home/dataset-local/weaveora/logs
mkdir -p "$LOGD"

cd "$CX" || exit 1
"$CX/venv/bin/python" "$CX/main.py" --listen 0.0.0.0 --port 8000 \
  >> "$LOGD/comfyui.log" 2>&1 &
echo "[start.sh] ComfyUI v0.34.0 已启动 pid=$! (log: $LOGD/comfyui.log)"

sleep 10s
chrome 127.0.0.1:8000 &
sleep 365d
