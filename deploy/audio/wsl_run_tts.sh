#!/usr/bin/env bash
# TTS(CosyVoice2) 常驻监督脚本（在 WSL 内运行；由 Windows 计划任务持有）
#   - pidfile 锁（比目录锁抗 SIGKILL 残留）：已在跑就直接退出
#   - 崩溃自愈：tts_server 退出后 8s 重拉
#   - 日志 /data/audio/tts.log
set -uo pipefail

PIDFILE=/data/audio/.tts.pid
if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null || echo "")
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    # 已有 supervisor 在跑。不要直接 exit：调用方（Windows 计划任务）需要本进程存活以
    # 持有 WSL 实例。这里阻塞等待，顺便保证 WSL 不会被回收。
    echo "tts supervisor already running (pid=$old); holding WSL instance open"
    while kill -0 "$old" 2>/dev/null; do sleep 60; done
    echo "peer supervisor gone; exit so parent can restart"
    exit 0
  fi
  echo "stale pidfile ($old), taking over"
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT

export WEAVEORA_COSYVOICE_DIR=/data/audio/CosyVoice
export WEAVEORA_COSYVOICE_MODEL=pretrained_models/CosyVoice2-0.5B
export WEAVEORA_COSYVOICE_SFT_MODEL=pretrained_models/CosyVoice-300M-SFT
export WEAVEORA_TTS_PRELOAD=1
export PYTHONUNBUFFERED=1

LOG=/data/audio/tts.log
cd /data/audio || exit 1

echo "[$(date +%F' '%T)] tts supervisor started (pid=$$)" >> "$LOG"
while :; do
  echo "[$(date +%F' '%T)] starting tts_server.py 8091 ..." >> "$LOG"
  /opt/miniconda/envs/cosy/bin/python /data/audio/tts_server.py 8091 >> "$LOG" 2>&1
  echo "[$(date +%F' '%T)] tts_server exited rc=$?; restart in 8s" >> "$LOG"
  sleep 8
done
