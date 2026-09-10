#!/usr/bin/env bash
# 观测 pip 下载速率（读 /proc/<pid>/io 的 read_bytes 增量）
set -uo pipefail
PID=$(pgrep -f "pip install -r /tmp/req_filtered" | head -1)
if [ -z "$PID" ]; then
  PID=$(pgrep -f "pip install" | grep -v pgrep | head -1)
fi
if [ -z "$PID" ]; then
  echo "pip 进程不存在（可能已结束）"
  tail -4 /data/audio/pip_install.log | tr -d '\r'
  exit 0
fi

read_bytes() { grep '^read_bytes' "/proc/$1/io" 2>/dev/null | awk '{print $2}'; }

echo "pip pid=$PID"
a=$(read_bytes "$PID"); sleep 20; b=$(read_bytes "$PID")
if [ -n "$a" ] && [ -n "$b" ]; then
  echo "20s 读取: $(( (b - a) / 1048576 )) MiB  ->  $(( (b - a) / 20 / 1024 )) KiB/s"
else
  echo "无法读取 io 计数"
fi
echo "--- 日志尾 ---"
tail -c 400 /data/audio/pip_install.log | tr -d '\r' | tail -3
echo "--- pip 缓存 ---"
du -sh /root/.cache/pip 2>/dev/null
