#!/usr/bin/env bash
# 重启 WSL 内 TTS 服务。
# 注意：pgrep -f 会匹配到本脚本自身及调用方的命令行（其中含 tts_server.py 字样），
# 因此必须排除自身 PID 与所有祖先进程 PID，否则会把自己杀掉。
set -uo pipefail

SELF=$$
ANC=""
p=$$
while [ "$p" != "1" ] && [ -n "$p" ]; do
  pp=$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null || echo "")
  [ -z "$pp" ] && break
  ANC="$ANC $pp"
  p=$pp
done
EXCLUDE="$SELF $ANC"
echo "排除 PID:$EXCLUDE"

PIDS=""
for pid in $(pgrep -f "tts_server\.py" 2>/dev/null || true); do
  skip=0
  for e in $EXCLUDE; do [ "$pid" = "$e" ] && skip=1; done
  # 只杀 python 解释器持有的 tts_server 进程
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || echo "")
  case "$cmd" in
    *python*tts_server.py*) [ "$skip" = 0 ] && PIDS="$PIDS $pid" ;;
  esac
done

if [ -n "$PIDS" ]; then
  echo "kill:$PIDS"
  kill -9 $PIDS 2>/dev/null || true
  sleep 2
else
  echo "无运行中的 tts_server"
fi

if ! pgrep -f "wsl_run_tts\.sh" >/dev/null 2>&1; then
  echo "拉起 supervisor"
  setsid nohup bash /data/audio/wsl_run_tts.sh > /data/audio/tts_sup.out 2>&1 < /dev/null &
  sleep 1
fi
echo "done"
