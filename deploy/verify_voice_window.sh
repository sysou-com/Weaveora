#!/usr/bin/env bash
# P8.7 验证：@end_sec 裁切配音 + 逐段字幕烧录（不依赖 Spring/DB）
#
# 1) 配音窗口：at=2.0 end=3.6 的 4s 素材，应只听到 2.0-3.6s 这段（其余被剪掉）
# 2) 多段字幕：一镜两条各带自己时间段的 ASS，libass 应接受并烧进画面
set -uo pipefail
FF="${1:-ffmpeg}"
command -v "$FF" >/dev/null 2>&1 || [ -x "$FF" ] || { echo "找不到 ffmpeg: $FF"; exit 1; }
W=$(mktemp -d); cd "$W" || exit 1
trap 'rm -rf "$W"' EXIT

echo "== 1. 配音按结束点裁切 =="
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:duration=4" -ar 44100 -ac 2 v.wav
# 无窗口：4s 都会响；有窗口：atrim 到 1.6s 再 adelay 1600ms
"$FF" -hide_banner -loglevel error -y -i v.wav -filter_complex \
  "[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=0:1.600,asetpts=PTS-STARTPTS,adelay=2000|2000,volume=1.0,apad[o]" \
  -map "[o]" -t 6 -c:a aac win.m4a
"$FF" -hide_banner -loglevel error -y -i v.wav -filter_complex \
  "[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,adelay=2000|2000,volume=1.0,apad[o]" \
  -map "[o]" -t 6 -c:a aac nowin.m4a

echo "  配音窗口 = 2.0s → 3.6s（adelay 2000ms + atrim 1.6s）"
for spec in "窗口前(应静音) 1.0 0.8" "窗口内(应有声) 2.2 1.2" "窗口后(应静音) 4.0 1.5"; do
  set -- $spec
  M=$("$FF" -hide_banner -ss "$2" -t "$3" -i win.m4a -af volumedetect -f null - 2>&1 \
      | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
  printf "  %-16s (%ss+%ss): mean=%s dB\n" "$1" "$2" "$3" "$M"
done
M=$("$FF" -hide_banner -ss 4.0 -t 1.5 -i nowin.m4a -af volumedetect -f null - 2>&1 \
    | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
echo "  对照（不设结束点，4.0s 处）：mean=$M dB —— 应仍有声（证明差异确实来自裁切）"

echo
echo "== 2. 一镜两段字幕（各带自己的时间段） =="
cat > sub.ass <<'EOF'
[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,Noto Sans CJK SC,30,&H00FFFFFF,&H000000FF,&H00000000,&H90000000,0,0,0,0,100,100,0,0,1,2.2,1,2,40,40,20,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.20,0:00:02.00,Sub,,0,0,0,,虎牢关前，两军列阵。
Dialogue: 0,0:00:02.00,0:00:03.60,Sub,,0,0,0,,谁敢与我决一死战！
EOF
"$FF" -hide_banner -loglevel warning -y -f lavfi -i "color=c=0x203040:s=640x360:d=4" \
  -vf "ass=filename=sub.ass" -c:v libx264 -pix_fmt yuv420p -t 4 burn.mp4
rc=$?
echo "  ffmpeg rc=$rc（必须 0：说明 libass 接受两条各带时间段的字幕）"
[ "$rc" -ne 0 ] && exit 1
D=$("$FF" -hide_banner -i burn.mp4 2>&1 | grep -oE "Duration: [0-9:.]+" | head -1)
echo "  $D （应 00:00:04）"
# 字幕出现前后应有像素差异（说明真的烧上去了）
"$FF" -hide_banner -loglevel error -y -ss 0.8 -i burn.mp4 -frames:v 1 f1.png
"$FF" -hide_banner -loglevel error -y -ss 3.9 -i burn.mp4 -frames:v 1 f2.png 2>/dev/null || true
S1=$(stat -c %s f1.png); S2=$(stat -c %s f2.png 2>/dev/null || echo 0)
echo "  第 0.8s 帧 ${S1}B / 第 3.9s 帧 ${S2}B（都 >0 且不同 → 字幕/画面有变化）"

echo
echo "OK: 结束点裁切与逐段字幕均被 ffmpeg 接受。"
