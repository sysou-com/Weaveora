#!/usr/bin/env bash
# 1) 拉取 Matcha-TTS submodule（ghfast 代理）；2) 查文本正则化依赖（pynini vs wetext）
set -uo pipefail
cd /data/audio/CosyVoice || exit 1

echo "=== 1. 拉 Matcha-TTS ==="
if [ -n "$(ls -A third_party/Matcha-TTS 2>/dev/null)" ]; then
  echo "已存在，跳过"
else
  cd third_party
  curl -sL -o mt.zip "https://ghfast.top/https://github.com/shivammehta25/Matcha-TTS/archive/refs/heads/main.zip"
  ls -la mt.zip
  unzip -q -o mt.zip
  rm -rf Matcha-TTS && mv Matcha-TTS-main Matcha-TTS
  rm -f mt.zip
  echo "--- 结果 ---"
  ls Matcha-TTS | head -10
fi

cd /data/audio/CosyVoice
echo
echo "=== 2. 文本正则化依赖 ==="
grep -rn -E "pynini|WeTextProcessing|we_text|wetext|import tn" --include='*.py' cosyvoice/ 2>/dev/null | head -20
echo
echo "=== 3. frontend.py 顶层 import ==="
grep -nE "^(import|from) " cosyvoice/cli/frontend.py | head -30
echo
echo "=== 4. 是否有 ttsfrd ==="
grep -rn "ttsfrd" --include='*.py' cosyvoice/ 2>/dev/null | head -5
