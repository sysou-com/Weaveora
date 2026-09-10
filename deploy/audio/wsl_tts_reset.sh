#!/usr/bin/env bash
# 清掉 WSL 内手工起的 TTS supervisor + 服务，交给 Windows 计划任务（ComfyTTS）重新持有。
# 目的：让常驻归属唯一（重启后由任务自愈），避免"孤儿 supervisor"与任务实例并存。
set -uo pipefail

kill_pid() {  # $1=pid
  [ -n "$1" ] && kill -9 "$1" 2>/dev/null && echo "  killed $1"
}

echo "=== 停 tts_server ==="
for pid in $(pgrep -x python3 2>/dev/null || true); do
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || echo "")
  case "$cmd" in *tts_server*) kill_pid "$pid" ;; esac
done

echo "=== 停 supervisor（bash wsl_run_tts.sh）==="
for pid in $(pgrep -x bash 2>/dev/null || true); do
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || echo "")
  case "$cmd" in
    *wsl_run_tts.sh*) [ "$pid" != "$$" ] && kill_pid "$pid" ;;
  esac
done

rm -f /data/audio/.tts.pid
sleep 2
echo "=== 剩余 ==="
pgrep -af 'tts_server|wsl_run_tts' | grep -v pgrep || echo "  (无)"
echo done
