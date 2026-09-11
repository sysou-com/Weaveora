#!/usr/bin/env bash
# P9 验证：克隆音色的样本处理链（与 AudioProcessService.filterChain 默认选项一致）
#   构造一段「有首尾静音 + 底噪 + 电平偏低」的假人声，跑处理链，验证：
#   - 输出是 24kHz 单声道（CosyVoice 前置要求）
#   - 首尾静音被去掉（时长变短）
#   - 响度被归一（电平显著提高）
set -uo pipefail
FF="${1:-ffmpeg}"
command -v "$FF" >/dev/null 2>&1 || [ -x "$FF" ] || { echo "找不到 ffmpeg: $FF"; exit 1; }
W=$(mktemp -d); cd "$W" || exit 1
trap 'rm -rf "$W"' EXIT

echo "== 1. 造一段“不理想”的样本：0.8s 静音 + 3s 低电平语音味 + 1.0s 静音，带底噪 =="
# 语音味：两个正弦叠加 + 轻微调幅；电平压到 0.08；再混一点白噪
"$FF" -hide_banner -loglevel error -y -f lavfi \
  -i "sine=frequency=220:duration=3" -f lavfi -i "sine=frequency=660:duration=3" \
  -filter_complex "[0:a][1:a]amix=inputs=2:normalize=0,volume=0.08[s];\
[s]adelay=800|800,apad=whole_dur=4.8,aformat=channel_layouts=stereo[s2];\
[s2]volume=1.0[v]" -map "[v]" -ar 44100 -ac 2 raw.wav
# 叠底噪
"$FF" -hide_banner -loglevel error -y -i raw.wav -f lavfi -i "anoisesrc=color=white:amplitude=0.002:duration=4.8" \
  -filter_complex "[0:a][1:a]amix=inputs=2:duration=shortest:normalize=0[v]" -map "[v]" -ar 44100 -ac 2 sample.wav
INFO=$(ffprobe -v error -show_entries stream=sample_rate,channels -show_entries format=duration -of default=noprint_wrappers=1 sample.wav 2>/dev/null || "$FF" -hide_banner -i sample.wav 2>&1 | grep -E "Duration|Audio")
echo "  输入：$(echo "$INFO" | tr '\n' ' ')"
R=$("$FF" -hide_banner -ss 0.2 -t 0.4 -i sample.wav -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
echo "  输入首段（静音区）mean=$R dB"

echo
echo "== 2. 跑与 Java 默认选项一致的滤镜链 =="
CHAIN="aformat=channel_layouts=mono,aresample=24000,silenceremove=start_periods=1:start_silence=0.1:start_threshold=-45dB:stop_periods=-1:stop_silence=0.3:stop_threshold=-45dB,loudnorm=I=-16:TP=-1.5:LRA=11"
echo "  $CHAIN"
"$FF" -hide_banner -loglevel error -y -i sample.wav -af "$CHAIN" -ac 1 -ar 24000 -c:a pcm_s16le out.wav
rc=$?
echo "  ffmpeg rc=$rc（必须 0）"
[ "$rc" -ne 0 ] && exit 1

echo
echo "== 3. 校验输出 =="
OUTINFO=$("$FF" -hide_banner -i out.wav 2>&1 | grep -E "Duration|Stream")
echo "  $(echo "$OUTINFO" | tr '\n' ' ')"
# 单声道 + 24kHz
echo "$OUTINFO" | grep -q "24000 Hz, mono" && echo "  ✓ 24kHz 单声道（CosyVoice 前置要求）" || { echo "  ✗ 不是 24kHz 单声道"; exit 1; }
# 时长变短（静音被去掉）
# 时长从 ffmpeg 自身的 -i 输出解析（venv 里可能没带 ffprobe）
dur_of() {  # $1=文件
  "$FF" -hide_banner -i "$1" 2>&1 | grep -oE 'Duration: [0-9]+:[0-9]+:[0-9.]+' | head -1 | sed 's/Duration: //' |
  awk -F: '{ printf "%.2f", $1*3600 + $2*60 + $3 }'
}
DIN=$(dur_of sample.wav)
DOUT=$(dur_of out.wav)
echo "  时长：输入 ${DIN}s → 输出 ${DOUT}s（静音被去掉，应明显变短）"
awk -v a="$DIN" -v b="$DOUT" 'BEGIN{ if (b+0 < a+0 - 0.1) print "  ✓ 首尾静音已去除"; else { print "  ✗ 时长没变短"; exit 1 } }' || exit 1
# 响度提高
MOUT=$("$FF" -hide_banner -ss 0.3 -t 1.0 -i out.wav -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
echo "  输出语音区 mean=$MOUT dB（输入语音区原本约 -30dB，应被提到 -16 LUFS 附近）"
awk -v m="$MOUT" 'BEGIN{ if (m+0 > -25) print "  ✓ 响度已归一（明显提高）"; else { print "  ✗ 响度没提上来"; exit 1 } }' || exit 1

echo
echo "OK: 样本处理链输出满足 CosyVoice 参考音要求，且静音/响度处理确实生效。"
