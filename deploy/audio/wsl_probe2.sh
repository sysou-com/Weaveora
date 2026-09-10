#!/usr/bin/env bash
# 查 CosyVoice 的 submodule 定义与 deepspeed 引用位置
set -uo pipefail
cd /data/audio/CosyVoice || exit 1

echo "=== .gitmodules ==="
cat .gitmodules 2>/dev/null || echo "(无 .gitmodules)"

echo
echo "=== deepspeed 引用位置 ==="
grep -rn -E "^(import|from) deepspeed" --include='*.py' cosyvoice third_party 2>/dev/null

echo
echo "=== Matcha-TTS 在代码中的引用 ==="
grep -rn "matcha" --include='*.py' cosyvoice 2>/dev/null | head -10

echo
echo "=== requirements 里可跳过的重包 ==="
grep -nE "tensorrt|deepspeed|gradio|fastapi|uvicorn|tensorboard" requirements.txt
