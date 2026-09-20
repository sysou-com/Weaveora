#!/usr/bin/env bash
# Weaveora GPU 服务器「多路 Range 并行 + 断点续传」下载器（Weaveora.md §0.2）
#
# 为什么需要（2026-09-19 在 GPU 服务器实测，可复现）：
#   · 单连接 curl：17.6 MB/s（100MB/5.96s）
#   · 4 路并行  ：5.8 MB/s × 4 = 23.2 MB/s
#   ⇒ 服务端按「每条连接」限速，并行只能多约 30%；分片的价值主要在**断了只重传那一片**，
#     以及避免单条长连接跑到一半掉速/被 reset。所以默认 10 路。
#   ⚠️ 别再凭印象说“单连接被限速所以慢” —— 要先量。
#
# 特性：
#   · 自动识别已下完整 → 直接退出；已有**连续前缀**（如之前用 curl -C - 下的部分）→ 只补剩余区间
#   · 每片独立重试 5 次 + SHA 无关的**字节数校验**（每片必须正好等于请求区间长度）
#   · 全部片齐了才按序 append；尺寸不符则报错并保留分片目录（可重跑）
#
# 用法：
#   gpu_mp_download.sh <URL> <输出绝对路径> [并发数，默认 10]
set -uo pipefail

URL="${1:?用法: gpu_mp_download.sh <URL> <输出路径> [并发]}"
OUT="${2:?缺少输出路径}"
N="${3:-10}"

TMP="$(dirname "$OUT")/.wvparts_$(basename "$OUT")"
mkdir -p "$TMP"

# 远端总长：先试 HEAD 的 content-length；HEAD 拿不到时（2026-09-19 实测 ModelScope 的 CDN 不回
# content-length）→ 用 Range GET 的 `Content-Range: bytes 0-0/<TOTAL>` 兜底。
TOTAL="$(curl -sIL -m 60 "$URL" | awk 'tolower($1)=="content-length:"{v=$2} END{gsub(/\r/,"",v); print v}')"
if [ -z "${TOTAL:-}" ]; then
  TOTAL="$(curl -sL -m 60 -r 0-0 -D - -o /dev/null "$URL" 2>/dev/null \
           | awk 'tolower($1)=="content-range:"{split($3,a,"/"); t=a[2]} END{gsub(/\r/,"",t); print t}')"
fi
if [ -z "${TOTAL:-}" ] || [ "$TOTAL" -lt 1024 ] 2>/dev/null; then
  echo "✗ 取不到远端大小（URL 失效或需要鉴权）：$URL"; exit 1
fi

HAVE=0
[ -f "$OUT" ] && HAVE="$(stat -c %s "$OUT")"
printf '远端 %.2f GB ｜ 本地已有 %.2f GB ｜ 并发 %s\n' \
  "$(echo "$TOTAL/1073741824" | bc -l)" "$(echo "$HAVE/1073741824" | bc -l)" "$N"

if [ "$HAVE" -ge "$TOTAL" ]; then
  echo "✅ 已是完整文件，跳过（$(stat -c %s "$OUT") 字节）"; echo "$TOTAL" > "$OUT.done"; exit 0
fi

REM=$((TOTAL - HAVE))
CH=$(( (REM + N - 1) / N ))
echo "待补 $((REM/1048576)) MB，每片约 $((CH/1048576)) MB"

for i in $(seq 0 $((N - 1))); do
  S=$((HAVE + i * CH))
  E=$((S + CH - 1))
  [ "$E" -ge "$TOTAL" ] && E=$((TOTAL - 1))
  [ "$S" -gt "$E" ] && continue
  (
    WANT=$((E - S + 1))
    for try in 1 2 3 4 5; do
      GOT=0; [ -f "$TMP/p$i" ] && GOT="$(stat -c %s "$TMP/p$i")"
      [ "$GOT" = "$WANT" ] && { echo "  ✅ 片$i 已完整（沿用上次的）"; exit 0; }
      # 片内续传：从 S+GOT 追加到 E（>> 追加，不覆盖已有字节 —— 上次跑到一半的片能接着用）
      curl -sL -r "$((S + GOT))-${E}" -m 7200 "$URL" >> "$TMP/p$i" || true
      GOT=0; [ -f "$TMP/p$i" ] && GOT="$(stat -c %s "$TMP/p$i")"
      if [ "$GOT" = "$WANT" ]; then echo "  ✅ 片$i 完成"; exit 0; fi
      echo "  ⚠ 片$i 第 $try 次后 $GOT/$WANT，接着续传"
      sleep 2
    done
    echo "  ✗ 片$i 未完成（保留分片，重跑本脚本可继续）"; exit 1
  ) &
done
wait

# 全部片都齐了才拼接（按序 append，避免再复制一份 19GB）
MISSING=0
for i in $(seq 0 $((N - 1))); do
  S=$((HAVE + i * CH)); E=$((S + CH - 1)); [ "$E" -ge "$TOTAL" ] && E=$((TOTAL - 1))
  [ "$S" -gt "$E" ] && continue
  WANT=$((E - S + 1))
  GOT=0; [ -f "$TMP/p$i" ] && GOT="$(stat -c %s "$TMP/p$i")"
  [ "$GOT" != "$WANT" ] && { echo "  ✗ 片$i 不完整，未拼接（可重跑本脚本续传）"; MISSING=1; }
done
[ "$MISSING" = 1 ] && exit 1

for i in $(seq 0 $((N - 1))); do
  [ -f "$TMP/p$i" ] && cat "$TMP/p$i" >> "$OUT" && rm -f "$TMP/p$i"
done
rmdir "$TMP" 2>/dev/null || true

SZ="$(stat -c %s "$OUT")"
if [ "$SZ" = "$TOTAL" ]; then
  echo "$TOTAL" > "$OUT.done"
  printf '✅ 完成：%s 字节（%.2f GB）→ %s\n' "$SZ" "$(echo "$SZ/1073741824" | bc -l)" "$OUT"
else
  printf '⚠️ 尺寸不符：%s / %s（保留文件，可重跑续传）\n' "$SZ" "$TOTAL"; exit 1
fi
