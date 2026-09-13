#!/bin/bash
# =============================================================================
# Weaveora 新 GPU 服务器 bootstrap（幂等，可在容器每次启动后执行）
#
# 背景：平台说明「关闭开发环境后，实例内除数据集挂载路径以外的文件和路径将被重置」。
#       本机唯一持久路径 = /home/dataset-local（K8s PVC, /dev/nvme1n1）
#       会被重置的：/（overlay）、/opt/anaconda3/envs/*、/home/start.sh、/home/batchcom
#
# 本脚本负责把「临时层」的东西重新铺好，并拉起全部服务：
#   1) 系统依赖 curl / build-essential / ffmpeg（pyworld 需 g++，LatentSync 需 ffmpeg）
#   2) /opt/anaconda3/envs/cosy -> 持久环境的兼容软链
#   3) whisper tiny.pt 缓存恢复（~/.cache 在 overlay）
#   4) 覆盖平台 /home/start.sh，改由本脚本接管
#   5) 停掉抢 8000 端口的平台自带 ComfyUI 0.27.0
#   6) 启动 services_up.sh（ComfyUI:8001 + TTS:8091 + Face:8093 + 网关:8000）
#
# 用法（容器每次启动后执行一次）：bash /home/dataset-local/weaveora/weaveora_boot.sh
# =============================================================================
set -uo pipefail

ROOT=/home/dataset-local/weaveora
LOGD=$ROOT/logs
CX=$ROOT/ComfyUI
COSY_ENV=$ROOT/envs/cosy
mkdir -p "$LOGD"
LOG=$LOGD/boot.log

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

log "================ weaveora bootstrap START ================"

# ---------- 1) 系统依赖 ----------
if ! command -v g++ >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1 \
   || ! command -v ffmpeg >/dev/null 2>&1; then
  log "安装 curl + build-essential + ffmpeg ..."
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl build-essential ffmpeg >/dev/null 2>&1
fi
log "系统依赖: curl=$(command -v curl || echo 缺失) g++=$(command -v g++ || echo 缺失) ffmpeg=$(command -v ffmpeg || echo 缺失)"

# ---------- 2) 兼容软链 + 缓存恢复 ----------
if [ -d "$COSY_ENV" ]; then
  if [ ! -L /opt/anaconda3/envs/cosy ]; then
    rm -rf /opt/anaconda3/envs/cosy 2>/dev/null
    mkdir -p /opt/anaconda3/envs
    ln -sfn "$COSY_ENV" /opt/anaconda3/envs/cosy && log "软链 /opt/anaconda3/envs/cosy -> $COSY_ENV"
  fi
else
  log "!! 持久环境不存在: $COSY_ENV（需重跑 install_cosy_deps.sh）"
fi

# whisper 缓存：LatentSync 的 Audio2Feature("tiny") 会找 ~/.cache/whisper/tiny.pt，
# 找不到就联网下载（被墙时会挂）。~/.cache 在 overlay，每次重置 -> 从持久盘恢复
if [ -f "$ROOT/cache/whisper/tiny.pt" ] && [ ! -f "$HOME/.cache/whisper/tiny.pt" ]; then
  mkdir -p "$HOME/.cache/whisper"
  cp "$ROOT/cache/whisper/tiny.pt" "$HOME/.cache/whisper/tiny.pt" && log "已恢复 whisper tiny.pt 缓存"
fi

# ---------- 3) 覆盖平台 start.sh ----------
cat > /home/start.sh <<'EOS'
#!/bin/sh
# Weaveora: 拉起全部服务（ComfyUI:8001 + TTS:8091 + Face:8093 + 边缘网关:8000）
# 公网唯一入口 36.103.182.217:30250 -> 容器 8000(网关) -> 按路径分发
bash /home/dataset-local/weaveora/services_up.sh
sleep 10s
chrome 127.0.0.1:8000 &
sleep 365d
EOS
chmod +x /home/start.sh
log "已写入 /home/start.sh（调用 services_up.sh）"

# ---------- 4) 清理平台自带 ComfyUI（0.27.0，会抢 8000）----------
for p in $(pgrep -f "batchcom/comfyui/main.py" 2>/dev/null); do
  kill -9 "$p" 2>/dev/null && log "已停平台自带 ComfyUI pid=$p"
done

# ---------- 5) 启动全部服务（幂等）----------
if [ -x "$ROOT/services_up.sh" ]; then
  bash "$ROOT/services_up.sh" 2>&1 | sed "s/^/    /" | tee -a "$LOG" | tail -12
else
  log "!! 缺少 $ROOT/services_up.sh"
fi

sleep 5
log "---------------- 端口验收 ----------------"
for p in 8000 8001 8091 8093; do
  if ss -ltn 2>/dev/null | grep -q ":$p "; then log "  :$p LISTEN"; else log "  :$p --"; fi
done
log "公网入口: http://36.103.182.217:30250  （/  → ComfyUI；/audio/* → 配音+转写；/face/* → 人脸；/bgm/* → 配乐兜底）"
log "================ weaveora bootstrap DONE ================"
