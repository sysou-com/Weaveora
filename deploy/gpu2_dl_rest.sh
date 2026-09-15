#!/bin/bash
# =============================================================================
# GPU#2 · jaw-lip 收尾下载（补 VAE + tokenizer 小文件；大件走 aifasthub）
#
# 背景：2026-09-15 首轮下载把 umt5 / CLIP / base transformer / EchoMimic transformer /
#   wav2vec2 都下完了（CLIP 到 94.2% 时容器被平台回收），剩下：
#     · Wan2.1_VAE.pth            507,609,880 B（hf-mirror 侧实测只有 ~1 MB/min，改走 aifasthub）
#     · google/umt5-xxl/*、xlm-roberta-large/*  tokenizer 小文件
#   容器回收后重跑本脚本即可**续传**（≥200MiB 走 .meta.json 分片续传；小文件 curl -C -）。
#
# 用法（后台）：setsid nohup bash /opt/weaveora/dl_rest.sh > /opt/weaveora/logs/dl_rest.log 2>&1 < /dev/null &
# 进度：tail -n 3 /opt/weaveora/logs/dl_rest.log
# =============================================================================
set -u
ROOT=/opt/weaveora
export PATH="$ROOT/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; BASE="$ROOT/models/echo_mimic/Wan2.1-Fun-V1.1-1.3B-InP"
AF="https://aifasthub.com/alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP/resolve/main"
HFM="https://hf-mirror.com/alibaba-pai/Wan2.1-Fun-V1.1-1.3B-InP/resolve/main"
mkdir -p "$L" "$BASE/google/umt5-xxl" "$BASE/xlm-roberta-large"
exec 9>"$L/dl_rest.lock"
if ! flock -n 9; then echo "[$(date +%H:%M:%S)] [skip] 已有 dl_rest 实例"; exit 0; fi
echo $$ > "$L/dl_rest.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
big(){ "$NODE" "$DL" "$1" "$2" "${3:-10}"; }
small(){ curl -sL -C - --retry 3 --retry-delay 5 --max-time 1800 -o "$2" "$1"; }

log "=== 收尾下载开始 $(date '+%F %T') ==="
# VAE：aifasthub（hf-mirror 实测太慢）；507MB ≥200MiB → 10 路
big "$AF/Wan2.1_VAE.pth" "$BASE/Wan2.1_VAE.pth" 10
# 小文件：aifasthub 优先，失败落 hf-mirror
for f in config.json configuration.json; do
  small "$AF/$f" "$BASE/$f" || small "$HFM/$f" "$BASE/$f"
done
for f in special_tokens_map.json spiece.model tokenizer.json tokenizer_config.json; do
  small "$AF/google/umt5-xxl/$f" "$BASE/google/umt5-xxl/$f" || small "$HFM/google/umt5-xxl/$f" "$BASE/google/umt5-xxl/$f"
done
for f in sentencepiece.bpe.model special_tokens_map.json tokenizer.json tokenizer_config.json; do
  small "$AF/xlm-roberta-large/$f" "$BASE/xlm-roberta-large/$f" || small "$HFM/xlm-roberta-large/$f" "$BASE/xlm-roberta-large/$f"
done

# 校验（§0.2-9：≥1GiB 字节+头结构；<1GiB 字节+全量 sha256）
log "=== 校验 ==="
fail=0
chk(){ f="$1"; want="$2"; sha="${3:-}"
  got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  if [ -n "$sha" ]; then echo "$sha  $f" | sha256sum -c - >/dev/null 2>&1 || { log "FAIL $(basename "$f") sha256 不符"; fail=$((fail+1)); return; }; fi
  log "OK   $(basename "$f")  $got"; }
chk "$BASE/Wan2.1_VAE.pth" 507609880 38071ab59bd94681c686fa51d75a1968f64e470262043be31f7a094e442fd981
chk "$BASE/google/umt5-xxl/spiece.model" 4548313 ""
chk "$BASE/google/umt5-xxl/tokenizer.json" 16837417 ""
chk "$BASE/xlm-roberta-large/sentencepiece.bpe.model" 5069051 ""
chk "$BASE/xlm-roberta-large/tokenizer.json" 17082660 ""
[ "$fail" = "0" ] && log "=== ALL_DONE $(date '+%F %T') ===" || log "=== FINISHED_WITH_ERRORS($fail) $(date '+%F %T') ==="
