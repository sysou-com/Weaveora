#!/usr/bin/env bash
# 下载 CosyVoice-300M-SFT（v1，带内置 spk2info，用于 UI 的 7 个音色预设）
# 目标：/data/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT
# 说明：只取推理必需文件；llm/flow/text_encoder 的 jit/trt zip 用不到（load_jit/load_trt=False）
set -uo pipefail

export REPO="https://www.modelscope.cn/models/iic/CosyVoice-300M-SFT/resolve/master"
export DEST="/data/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT"
mkdir -p "$DEST"
cd "$DEST" || exit 1

MANIFEST="cosyvoice.yaml 6421
llm.pt 1242994835
flow.pt 419900943
hift.pt 81896716
speech_tokenizer_v1.onnx 522624269
campplus.onnx 28303423
spk2info.pt 7772"

one() {
  rel="$1"; want="$2"
  mkdir -p "$(dirname "$rel")"
  got=$(stat -c %s "$rel" 2>/dev/null || echo 0)
  if [ "$got" = "$want" ]; then echo "  SKIP $rel (已完整)"; return 0; fi
  for a in 1 2 3 4 5 6 7 8 9 10; do
    curl -sL -C - -o "$rel" --retry 5 --retry-delay 3 --retry-all-errors \
         --connect-timeout 20 --max-time 3000 "$REPO/$rel"
    got=$(stat -c %s "$rel" 2>/dev/null || echo 0)
    if [ "$got" = "$want" ]; then
      echo "  OK   $rel ($((want / 1048576)) MB)"
      return 0
    fi
    echo "  retry($a) $rel got=$got want=$want"
    sleep 2
  done
  echo "  FAIL $rel"
  return 1
}
export -f one

echo "[$(date +%H:%M:%S)] 下载 CosyVoice-300M-SFT -> $DEST"
echo "$MANIFEST" | xargs -P 4 -n 2 bash -c 'one "$0" "$1"'
rc=$?

echo "[$(date +%H:%M:%S)] 汇总（rc=$rc）"
du -sh "$DEST"
find "$DEST" -type f -printf '%10s  %P\n' | sort -rn
echo "$MANIFEST" | while read -r rel want; do
  got=$(stat -c %s "$DEST/$rel" 2>/dev/null || echo 0)
  [ "$got" = "$want" ] || echo "MISSING/TRUNCATED: $rel ($got/$want)"
done
echo ALL_DONE
