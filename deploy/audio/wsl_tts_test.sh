#!/usr/bin/env bash
# TTS 端到端自测：POST /tts → 校验 wav 时长/采样率/响度
set -uo pipefail
TEXT="${1:-夜色温柔，愿你安然入睡。明天又是崭新的一天。}"
VOICE="${2:-中文女}"
OUT=/data/audio/tts_test.wav

echo "=== /health ==="
curl -s -m 5 http://127.0.0.1:8091/health; echo

echo "=== POST /tts  text=\"$TEXT\" voice=$VOICE ==="
curl -s -m 600 -X POST http://127.0.0.1:8091/tts \
  -H 'Content-Type: application/json' \
  -d "{\"text\":\"$TEXT\",\"voice\":\"$VOICE\",\"speed\":1.0,\"target_sec\":0}" \
  -D /tmp/tts_headers.txt -o "$OUT"
echo "--- 响应头 ---"
grep -iE 'HTTP/|x-duration|content-type|content-length' /tmp/tts_headers.txt | tr -d '\r'

echo "--- 产物 ---"
ls -la "$OUT"
file "$OUT" 2>/dev/null
ffprobe -v error -show_entries format=duration,bit_rate -show_entries stream=codec_name,sample_rate,channels -of default=noprint_wrappers=1 "$OUT" 2>&1 | head -10
ffmpeg -hide_banner -i "$OUT" -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume"
