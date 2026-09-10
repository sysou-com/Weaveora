#!/usr/bin/env bash
# CosyVoice 部署状态总览（WSL 内运行）
set -uo pipefail
CV=/data/audio/CosyVoice
DEST=$CV/pretrained_models/CosyVoice2-0.5B

echo "===== 1. 权重完整性 ====="
if [ -d "$DEST" ]; then
  cat > /tmp/want.txt <<'EOF'
cosyvoice2.yaml 7330
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
CosyVoice-BlankEN/model.safetensors 988097824
EOF
  ok=0; bad=0
  while read -r rel want; do
    got=$(stat -c %s "$DEST/$rel" 2>/dev/null || echo 0)
    if [ "$got" = "$want" ]; then ok=$((ok+1)); else bad=$((bad+1)); echo "  BAD  $rel  ($got/$want)"; fi
  done < /tmp/want.txt
  echo "  -> 完整 $ok / 异常 $bad"
  du -sh "$DEST"
else
  echo "  (目录不存在)"
fi

echo
echo "===== 2. 依赖安装状态 ====="
tail -3 /data/audio/pip_install.log 2>/dev/null
echo "--- 关键包 ---"
/opt/miniconda/envs/cosy/bin/python - <<'PY' 2>/dev/null
import importlib
for m in ("torch", "torchaudio", "onnxruntime", "numpy", "hyperpyyaml", "modelscope",
          "transformers", "librosa", "soundfile", "whisper", "wetext", "inflect",
          "omegaconf", "pyworld", "conformer", "x_transformers", "matcha"):
    try:
        mod = importlib.import_module(m)
        print("  OK  %-14s %s" % (m, getattr(mod, "__version__", "")))
    except Exception as e:
        print("  ERR %-14s %s" % (m, str(e)[:60]))
try:
    import torch
    print("  cuda=%s device=%s" % (torch.cuda.is_available(),
          torch.cuda.get_device_name(0) if torch.cuda.is_available() else "-"))
except Exception as e:
    print("  cuda probe failed: %s" % e)
PY

echo
echo "===== 3. TTS 服务 ====="
curl -s -m 5 http://127.0.0.1:8091/health || echo "  :8091 未响应"
echo
pgrep -af "tts_server.py" | grep -v pgrep || echo "  (无 tts_server 进程)"
