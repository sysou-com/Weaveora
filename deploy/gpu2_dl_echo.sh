#!/bin/bash
# =============================================================================
# EchoMimicV3 权重下载（GPU#2 48G）—— 遵守 Weaveora.md §0.2「大文件下载铁律」
#
#   · ≥200 MiB → gpu_model_downloader.js（10 路 Range 分片 + .meta.json 断点续传 + .done）
#   · <200 MiB  → curl -L -C -（小文件）
#   · 后台静默（setsid nohup）+ 单实例锁；日志 /opt/weaveora/logs/dl_echo.log（每 10s 一行）
#   · 源（本机实测）：ModelScope **API 端点**（10 路 28–53 MiB/s）优先；
#     Wan2.1-Fun base 只在 HF 有 → aifasthub（Range 206；本机单连 ~0.7 MB/s，靠 10 路聚合）
#   · 校验口径（§0.2-9）：≥1 GiB → 字节数 + safetensors 头结构；<1 GiB → 字节数 + 全量 sha256
#
# 用法（在 GPU#2 上，后台）：
#   setsid nohup bash /opt/weaveora/dl_echo.sh > /opt/weaveora/logs/dl_echo.log 2>&1 < /dev/null &
# 进度：tail -n 3 /opt/weaveora/logs/dl_echo.log        # 只做短查，不要挂长轮询
# 叫停：kill $(cat /opt/weaveora/logs/dl_echo.pid)      # 按 PID 精确杀（重跑自动续传）
# =============================================================================
set -u
ROOT=/opt/weaveora
export WEAVEORA_CURL=curl                 # 下载器默认 curl.exe（Windows 口径），Linux 必须覆盖
export PATH="$ROOT/opt-node/bin:$PATH"
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
M="$ROOT/models/echo_mimic"; L="$ROOT/logs"; BASE="$M/Wan2.1-Fun-V1.1-1.3B-InP"

mkdir -p "$BASE/google/umt5-xxl" "$BASE/xlm-roberta-large" "$M/wav2vec2-base-960h" "$M/transformer" "$L"

# ---- 单实例锁（避免双实例互踩 / 重复占带宽）----
exec 9>"$L/dl_echo.lock"
if ! flock -n 9; then echo "[$(date +%H:%M:%S)] [skip] 已有 dl_echo 实例在跑"; exit 0; fi
echo $$ > "$L/dl_echo.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
big(){ "$NODE" "$DL" "$1" "$2" "${3:-10}"; }
small(){ curl -sL -C - --retry 3 --retry-delay 5 --max-time 1800 -o "$2" "$1"; }

MS="https://modelscope.cn/api/v1/models"
AF="https://aifasthub.com/alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP/resolve/main"
HFM="https://hf-mirror.com/alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP/resolve/main"
log "[$(date +%H:%M:%S)] === 开始 $(date '+%F %T')  目标 $M（预计 ~23.6 GB）==="

# ---------- A. EchoMimicV3 transformer（ModelScope，≈3.41 GB）----------
big "$MS/BadToBest/EchoMimicV3/repo?Revision=master&FilePath=transformer%2Fdiffusion_pytorch_model.safetensors" \
    "$M/transformer/diffusion_pytorch_model.safetensors" 10
small "$MS/BadToBest/EchoMimicV3/repo?Revision=master&FilePath=transformer%2Fconfig.json" "$M/transformer/config.json"

# ---------- B. wav2vec2-base-960h（ModelScope 镜像，≈0.38 GB）----------
for f in model.safetensors config.json preprocessor_config.json feature_extractor_config.json vocab.json tokenizer_config.json special_tokens_map.json; do
  case "$f" in
    model.safetensors) big "$MS/facebook/wav2vec2-base-960h/repo?Revision=master&FilePath=$f" "$M/wav2vec2-base-960h/$f" 8 ;;
    *) small "$MS/facebook/wav2vec2-base-960h/repo?Revision=master&FilePath=$f" "$M/wav2vec2-base-960h/$f" ;;
  esac
done

# ---------- C. Wan2.1-Fun-V1.1-1.3B-InP（≈19.8 GB）----------
#   ModelScope 没有这个仓库（试过 6 个候选 id 均 404）→ 只能走 HF 镜像。
#   本机实测（10 路 Range / 30s 上限）：aifasthub ≈1.24 MB/s、hf-mirror ≈1.23 MB/s，
#   且都与并发数无关（≈每出口 IP 上限）；gh-proxy.com 在本机 **30s 0 字节**（不可用）。
#   → 所以**按文件拆到两个镜像并行**，把总带宽凑到 ~2.5 MB/s；
#     同一时刻只让一个文件走同一个镜像（否则互相抢那 1.24 MB/s）。
log "--- C1 并行：umt5 文本编码器(aifasthub) + CLIP 图像编码器(hf-mirror) ---"
big "$AF/models_t5_umt5-xxl-enc-bf16.pth" "$BASE/models_t5_umt5-xxl-enc-bf16.pth" 6 &
big "$HFM/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth" "$BASE/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth" 6 &
wait
log "--- C2：base 1.3B transformer(aifasthub) + 小文件(hf-mirror) ---"
big "$AF/diffusion_pytorch_model.safetensors" "$BASE/diffusion_pytorch_model.safetensors" 10
for f in Wan2.1_VAE.pth config.json configuration.json; do small "$HFM/$f" "$BASE/$f"; done
for f in special_tokens_map.json spiece.model tokenizer.json tokenizer_config.json; do small "$HFM/google/umt5-xxl/$f" "$BASE/google/umt5-xxl/$f"; done
for f in sentencepiece.bpe.model special_tokens_map.json tokenizer.json tokenizer_config.json; do small "$HFM/xlm-roberta-large/$f" "$BASE/xlm-roberta-large/$f"; done

# ---------- D. 校验 ----------
log "=== 校验（≥1GiB：字节+头结构；<1GiB：字节+sha256）==="
fail=0
chk(){ f="$1"; want="$2"; sha="${3:-}"
  got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  case "$f" in *.safetensors)
      if ! python3 - "$f" <<'PY'
import struct, sys
with open(sys.argv[1], "rb") as fh:
    n = struct.unpack("<Q", fh.read(8))[0]
sys.exit(0 if 0 < n < 100_000_000 else 1)
PY
      then log "FAIL $(basename "$f") safetensors 头异常"; fail=$((fail+1)); return; fi
    ;; esac
  if [ -n "$sha" ] && [ "$got" -lt 1073741824 ]; then
      echo "$sha  $f" | sha256sum -c - >/dev/null 2>&1 || { log "FAIL $(basename "$f") sha256 不符"; fail=$((fail+1)); return; }
  fi
  log "OK   $(basename "$f")  $got"; }
chk "$BASE/models_t5_umt5-xxl-enc-bf16.pth"                        11361920418 ""
chk "$BASE/models_clip_open-clip-xlm-roberta-large-vit-huge-14.pth" 4772359047 ""
chk "$BASE/diffusion_pytorch_model.safetensors"                     3128957992 ""
chk "$BASE/Wan2.1_VAE.pth"                                           507609880 38071ab59bd94681c686fa51d75a1968f64e470262043be31f7a094e442fd981
chk "$M/transformer/diffusion_pytorch_model.safetensors"            3414541616 ""
chk "$M/wav2vec2-base-960h/model.safetensors"                        377607901 8aa76ab2243c81747a1f832954586bc566090c83a0ac167df6f31f0fa917d74a

if [ "$fail" = "0" ]; then log "=== ALL_DONE $(date '+%F %T') ==="; else log "=== FINISHED_WITH_ERRORS ($fail) $(date '+%F %T') ==="; fi
