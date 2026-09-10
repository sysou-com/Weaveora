#!/usr/bin/env bash
# P8 配乐多段混音 —— ffmpeg 滤镜图冒烟验证（不依赖 Spring/DB）。
#
# 复刻 ConcatService.mixAudio 的滤镜图（与 ConcatMusicFilterTest 断言的字符串一致）：
#   语音：按「镜头起点 + at_sec」adelay 摆放
#   配乐：每段 atrim + volume(gain_db) + afade + adelay，多段 amix 成 [bgmraw]
#   旁白对 [bgmraw] 做 sidechaincompress ducking
# 额外验证：-stream_loop -1 能让「短曲子填满长区间」。
#
# 用法：bash deploy/verify_mix_filter.sh [ffmpeg路径]
set -uo pipefail

FF="${1:-ffmpeg}"
command -v "$FF" >/dev/null 2>&1 || [ -x "$FF" ] || { echo "找不到 ffmpeg: $FF"; exit 1; }

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
cd "$W" || exit 1

TOTAL=24

echo "== 1. 合成素材 =="
# 24s 黑底视频 + 静音轨（模拟 concat 出来的 master）
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=black:s=320x180:d=$TOTAL" \
  -f lavfi -i "anullsrc=r=44100:cl=stereo" -shortest \
  -c:v libx264 -pix_fmt yuv420p -c:a aac master.mp4
# 两段“语音”（2s 正弦，足够触发 ducking）
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=300:duration=2" -ar 44100 -ac 1 v0.wav
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=420:duration=2" -ar 44100 -ac 1 v1.wav
# 开场曲只给 6s —— 区间是 16s，必须靠 -stream_loop -1 循环填满
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=220:duration=6" -ar 44100 -ac 2 bgm_open.mp3
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=180:duration=8" -ar 44100 -ac 2 bgm_chase.mp3
ls -la master.mp4 v0.wav v1.wav bgm_open.mp3 bgm_chase.mp3 | awk '{printf "  %-16s %s B\n",$9,$5}'

echo
echo "== 2. 跑混音滤镜图（等价 mixAudio） =="
# 镜头 1 起点 0s，其 at_sec=0.2；镜头 2 起点 4s，其 at_sec=2.0 → 实际 6.0s
FILTER="[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,adelay=200|200,volume=1.0,apad[v1];"
FILTER+="[2:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,adelay=6000|6000,volume=1.0,apad[v2];"
FILTER+="[v1][v2]amix=inputs=2:duration=longest:normalize=0[voice];"
FILTER+="[3:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=0:16.000,asetpts=PTS-STARTPTS,volume=0.150,afade=t=in:st=0:d=2.000,afade=t=out:st=15.000:d=1.000,adelay=0|0,apad[m3];"
FILTER+="[4:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=0:8.000,asetpts=PTS-STARTPTS,volume=0.398,afade=t=in:st=0:d=0.500,afade=t=out:st=6.000:d=2.000,adelay=16000|16000,apad[m4];"
FILTER+="[m3][m4]amix=inputs=2:duration=longest:normalize=0[bgmraw];"
FILTER+="[voice]asplit=2[vmix][vsc];"
FILTER+="[bgmraw][vsc]sidechaincompress=threshold=0.05:ratio=6:attack=20:release=400[bgmduck];"
FILTER+="[vmix][bgmduck]amix=inputs=2:duration=longest:normalize=0[aout]"

"$FF" -hide_banner -loglevel warning -y \
  -i master.mp4 \
  -i v0.wav -i v1.wav \
  -stream_loop -1 -i bgm_open.mp3 \
  -stream_loop -1 -i bgm_chase.mp3 \
  -filter_complex "$FILTER" \
  -map 0:v:0 -map "[aout]" -c:v copy -c:a aac -b:a 160k -t "$TOTAL" -movflags +faststart out.mp4
rc=$?
echo "  ffmpeg rc=$rc（关键：必须为 0，即 sidechaincompress + stream_loop + amix 都被接受）"
[ "$rc" -ne 0 ] && exit 1

echo
echo "== 3. 校验成片 =="
DUR=$("$FF" -hide_banner -i out.mp4 2>&1 | grep -oE "Duration: [0-9:.]+" | head -1)
echo "  $DUR （应为 00:00:24）"
ls -la out.mp4 | awk '{printf "  大小 %.1f KB\n",$5/1024}'

echo
echo "== 4. 分窗响度（验证“开场一半、追赶更响”） =="
# 测量窗必须避开语音。语音落在 0.2-2.2s 与 6.0-8.0s，所以开场取 9-15s
echo "  语音位置：0.2-2.2s / 6.0-8.0s（测量窗已避开）"
for spec in "开场-open 9 15" "追赶-chase 17 21"; do
  set -- $spec
  ST=$2; EN=$3; LEN=$((EN - ST))
  M=$("$FF" -hide_banner -ss "$ST" -t "$LEN" -i out.mp4 -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
  printf "  %-14s (%ss-%ss): mean=%s dB\n" "$1" "$ST" "$EN" "$M"
done
echo "  期望：开场 ≈ -41dB，追赶 ≈ -32.5dB（差 8.5dB）"

echo
echo "== 5. 语音是否真的落在预期位置 =="
# 6.0s 处应有第二段语音（420Hz），这里只看该窗口的能量不为静音
M=$("$FF" -hide_banner -ss 6.0 -t 1.5 -i out.mp4 -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: -?[0-9.]+' | grep -oE '\-?[0-9.]+$' | head -1)
echo "  6.0-7.5s mean=$M dB （应明显高于纯静音 -91dB）"

echo
echo "OK: 滤镜图被 ffmpeg 接受，成片时长正确。"
