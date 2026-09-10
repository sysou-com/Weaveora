#!/usr/bin/env bash
# CosyVoice 部署探针（在 WSL 内以 root 运行）：确认第三方依赖与 submodule 状态
set -uo pipefail
cd /data/audio/CosyVoice || exit 1

echo "=== 第三方 submodule 目录内容 ==="
ls -la third_party/ 2>/dev/null
echo "--- third_party/Matcha-TTS ---"
ls -A third_party/Matcha-TTS 2>/dev/null | head -10

echo
echo "=== 重依赖在代码中的 import 次数 ==="
for m in deepspeed tensorrt gradio fastapi uvicorn tensorboard lightning hyperpyyaml onnxruntime; do
  n=$(grep -rl -E "^(import|from) ${m}" --include='*.py' cosyvoice third_party 2>/dev/null | wc -l)
  printf '  %-14s %s\n' "$m" "$n"
done

echo
echo "=== cosyvoice/cli/cosyvoice.py 的顶层 import ==="
grep -nE "^(import|from) " cosyvoice/cli/cosyvoice.py | head -30

echo
echo "=== /mnt/d 可见性 ==="
ls /mnt/d/workspace/Weaveora/deploy/audio/ 2>/dev/null | head -5 || echo "(不可见)"
