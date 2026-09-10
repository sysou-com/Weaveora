#!/usr/bin/env bash
# 诊断：配乐总线 [bgmraw] 与 duck 后的电平剖面（定位 amix / sidechaincompress 的影响）
set -uo pipefail
FF="${1:-ffmpeg}"
W=$(mktemp -d); cd "$W" || exit 1
trap 'rm -rf "$W"' EXIT

"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=220:duration=6" -ar 44100 -ac 2 open.mp3
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=180:duration=8" -ar 44100 -ac 2 chase.mp3
"$FF" -hide_banner -loglevel error -y -f lavfi -i "anullsrc=r=44100:cl=stereo" -t 24 silence.wav

profile() {  # $1=文件 $2=标签
  printf "  %-12s" "$2"
  for s in 2 5 10 13 17 20; do
    M=$("$FF" -hide_banner -ss $s -t 2 -i "$1" -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
    printf " %ss:%s" "$s" "${M:--}"
  done
  echo
}

echo "=== 期望：open(gain .150)≈-41dB，chase(gain .398)≈-32.5dB ==="

# A) 只有 open
"$FF" -hide_banner -loglevel error -y -stream_loop -1 -i open.mp3 -filter_complex \
 "[0:a]aresample=44100,atrim=0:16,asetpts=PTS-STARTPTS,volume=0.150,adelay=0|0,apad[o]" \
 -map "[o]" -t 24 -c:a aac A_only_open.m4a
profile A_only_open.m4a "A:仅open"

# B) open + chase 两段 amix（无 duck、无语音）
"$FF" -hide_banner -loglevel error -y -stream_loop -1 -i open.mp3 -stream_loop -1 -i chase.mp3 -filter_complex \
 "[0:a]aresample=44100,atrim=0:16,asetpts=PTS-STARTPTS,volume=0.150,adelay=0|0,apad[m0];\
  [1:a]aresample=44100,atrim=0:8,asetpts=PTS-STARTPTS,volume=0.398,adelay=16000|16000,apad[m1];\
  [m0][m1]amix=inputs=2:duration=longest:normalize=0[o]" \
 -map "[o]" -t 24 -c:a aac B_amix.m4a
profile B_amix.m4a "B:两段amix"

# C) B + 静音侧链做 sidechaincompress（等价"没有语音时"的 duck 输出）
"$FF" -hide_banner -loglevel error -y -stream_loop -1 -i open.mp3 -stream_loop -1 -i chase.mp3 -i silence.wav -filter_complex \
 "[0:a]aresample=44100,atrim=0:16,asetpts=PTS-STARTPTS,volume=0.150,adelay=0|0,apad[m0];\
  [1:a]aresample=44100,atrim=0:8,asetpts=PTS-STARTPTS,volume=0.398,adelay=16000|16000,apad[m1];\
  [m0][m1]amix=inputs=2:duration=longest:normalize=0[bgmraw];\
  [2:a]apad[sc];\
  [bgmraw][sc]sidechaincompress=threshold=0.05:ratio=6:attack=20:release=400[o]" \
 -map "[o]" -t 24 -c:a aac C_duck.m4a
profile C_duck.m4a "C:B+静音duck"
