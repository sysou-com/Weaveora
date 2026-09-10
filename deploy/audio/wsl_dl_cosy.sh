#!/usr/bin/env bash
# 下载 CosyVoice2-0.5B 权重（ModelScope；curl 断点续传 + 4 路文件并行 + 精确字节校验）
# 目标：/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B
# 日志：/data/audio/dl_cosy.log
set -uo pipefail

export REPO="https://www.modelscope.cn/models/iic/CosyVoice2-0.5B/resolve/master"
export DEST="/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B"
mkdir -p "$DEST"
cd "$DEST" || exit 1

# 路径 字节数（取自 ModelScope API，用于精确校验）
MANIFEST="cosyvoice2.yaml 7330
llm.pt 2023316821
flow.pt 450575567
hift.pt 83390254
speech_tokenizer_v2.onnx 496082973
campplus.onnx 28303423
CosyVoice-BlankEN/config.json 659
CosyVoice-BlankEN/generation_config.json 242
CosyVoice-BlankEN/tokenizer_config.json 1287
CosyVoice-BlankEN/vocab.json 2776833
CosyVoice-BlankEN/merges.txt 1402109
CosyVoice-BlankEN/model.safetensors 988097824"

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

echo "[$(date +%H:%M:%S)] 开始下载 -> $DEST"
echo "$MANIFEST" | xargs -P 4 -n 2 bash -c 'one "$0" "$1"'
rc=$?

echo "[$(date +%H:%M:%S)] 汇总（rc=$rc）"
du -sh "$DEST"
find "$DEST" -type f -printf '%10s  %P\n' | sort -rn
echo "$MANIFEST" | while read -r rel want; do
  got=$(stat -c %s "$DEST/$rel" 2>/dev/null || echo 0)
  [ "$got" = "$want" ] || echo "MISSING/TRUNCATED: $rel ($got/$want)"
done
echo "ALL_DONE"
