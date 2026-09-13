#!/bin/bash
# =============================================================================
# Weaveora 新 GPU 服务器 bootstrap（幂等，可在容器每次启动后执行）
#
# 背景：平台说明「关闭开发环境后，实例内除数据集挂载路径以外的文件和路径将被重置」。
#       本机唯一持久路径 = /home/dataset-local（K8s PVC, /dev/nvme1n1）
#       会被重置的：/（overlay）、/opt/anaconda3/envs/*、/home/start.sh、/home/batchcom
#
# 本脚本负责把「临时层」的东西重新铺好，并拉起常驻服务：
#   1) 系统依赖 curl / build-essential（pyworld 等需 g++）
#   2) /opt/anaconda3/envs/cosy -> 持久环境的兼容软链
#   3) 覆盖平台 /home/start.sh，改由自有 ComfyUI v0.34.0 监听 0.0.0.0:8000
#   4) 停掉抢占 8000 的平台自带 ComfyUI 0.27.0
#   5) 拉起 ComfyUI（幂等）与 TTS :8091（幂等）
#
# 建议：把平台「启动命令」设为  bash /home/dataset-local/weaveora/weaveora_boot.sh
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
if ! command -v g++ >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
  log "安装 curl + build-essential ..."
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl build-essential >/dev/null 2>&1
fi
log "系统依赖: curl=$(command -v curl || echo 缺失) g++=$(command -v g++ || echo 缺失)"

# ---------- 2) 兼容软链 + 缓存恢复 ----------
if [ -d "$COSY_ENV" ]; then
  if [ -L /opt/anaconda3/envs/cosy ]; then
    :
  else
    rm -rf /opt/anaconda3/envs/cosy 2>/dev/null
    mkdir -p /opt/anaconda3/envs
    ln -sfn "$COSY_ENV" /opt/anaconda3/envs/cosy && log "软链 /opt/anaconda3/envs/cosy -> $COSY_ENV"
  fi
else
  log "!! 持久环境不存在: $COSY_ENV（需重跑 install_cosy_deps.sh）"
fi

# whisper 缓存：LatentSync 的 Audio2Feature("tiny") 会找 ~/.cache/whisper/tiny.pt，
# 找不到就联网下载（被墙时会挂）。~/.cache 在 overlay，每次重置 -> 从持久盘恢复
if [ -f "$ROOT/cache/whisper/tiny.pt" ]; then
  mkdir -p "$HOME/.cache/whisper"
  if [ ! -f "$HOME/.cache/whisper/tiny.pt" ]; then
    cp "$ROOT/cache/whisper/tiny.pt" "$HOME/.cache/whisper/tiny.pt" && log "已恢复 whisper tiny.pt 缓存"
  fi
fi

# ---------- 3) 覆盖平台 start.sh ----------
cat > /home/start.sh <<'EOS'
#!/bin/sh
# Weaveora: 自有 ComfyUI v0.34.0（持久盘）+ 独立 venv
CX=/home/dataset-local/weaveora/ComfyUI
LOGD=/home/dataset-local/weaveora/logs
mkdir -p "$LOGD"
cd "$CX" || exit 1
"$CX/venv/bin/python" "$CX/main.py" --listen 0.0.0.0 --port 8000 >> "$LOGD/comfyui.log" 2>&1 &
echo "[start.sh] ComfyUI pid=$! (log: $LOGD/comfyui.log)"
sleep 10s
chrome 127.0.0.1:8000 &
sleep 365d
EOS
chmod +x /home/start.sh
log "已写入 /home/start.sh（指向自有 ComfyUI）"

# ---------- 4) 清理平台自带 ComfyUI（0.27.0，会抢 8000）----------
for p in $(pgrep -f "batchcom/comfyui/main.py" 2>/dev/null); do
  kill -9 "$p" 2>/dev/null && log "已停平台自带 ComfyUI pid=$p"
done

# ---------- 5) 拉起 ComfyUI（幂等）----------
if ss -ltn 2>/dev/null | grep -q ":8000 "; then
  log "8000 已在监听，跳过启动 ComfyUI"
else
  cd "$CX" || exit 1
  setsid nohup "$CX/venv/bin/python" "$CX/main.py" --listen 0.0.0.0 --port 8000 \
    >> "$LOGD/comfyui.log" 2>&1 < /dev/null &
  log "ComfyUI 已拉起 pid=$!"
fi

# ---------- 6) 拉起 TTS（幂等）----------
if [ -x "$ROOT/audio_start.sh" ]; then
  bash "$ROOT/audio_start.sh" 2>&1 | tee -a "$LOG"
else
  log "!! 缺少 $ROOT/audio_start.sh"
fi

log "================ weaveora bootstrap DONE ================"
log "  ComfyUI : 容器内 0.0.0.0:8000   公网 36.103.182.217:30250"
log "  TTS     : 0.0.0.0:8091"
