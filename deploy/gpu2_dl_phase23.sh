#!/bin/bash
# =============================================================================
# GPU#2 · Phase 2 + T2 下载 —— Qwen-Image-Edit（一致性）+ FLUX.1-schnell 线
#   用户 2026-09-15：先完成到 T2；一致性选 (a)（FLUX schnell 出图 + Qwen-Image-Edit 跨模型一致性）；
#   新挂载磁盘 /media/vipuser/addDisk（ext4, 93G 可用）→ **所有新增大件都下到新盘**，
#   再在 /opt/weaveora/models/<sub>/ 建同名软链（ComfyUI 逐请求扫目录，**不需要重启**）。
#   遵守 §0.2：10 路 Range + .meta.json 断点续传 + 后台静默 + 字节/结构校验（≥1GiB 用 .done+头结构）。
#   用法：setsid nohup bash /opt/weaveora/dl_phase23.sh > /opt/weaveora/logs/dl_phase23.log 2>&1 < /dev/null &
#   进度：tail -n 3 /opt/weaveora/logs/dl_phase23.log ;  叫停：kill $(cat /opt/weaveora/logs/dl_phase23.pid)
# =============================================================================
set -u
ROOT=/opt/weaveora
STORE="${WEAVEORA_MODEL_STORE:-/media/vipuser/addDisk/weaveora/models}"   # 新盘
LIVE="$ROOT/models"                                                       # ComfyUI 实际读取的目录（软链在此）
export PATH="$ROOT/opt-node/bin:$PATH"; export WEAVEORA_CURL=curl
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; mkdir -p "$L"
exec 9>"$L/dl_phase23.lock"; flock -n 9 || { echo "[skip] 已有实例"; exit 0; }; echo $$ > "$L/dl_phase23.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }

NEED_GB=50
FREE_GB=$(df -BG --output=avail "$STORE" | tail -1 | tr -dc '0-9')
log "=== 磁盘闸门：$STORE free=${FREE_GB}G need=${NEED_GB}G ==="
[ "$FREE_GB" -ge "$NEED_GB" ] || { log "新盘空间不足，退出"; exit 3; }

MSC="https://modelscope.cn/api/v1/models"
QE="$MSC/Comfy-Org/Qwen-Image-Edit_ComfyUI/repo?Revision=master&FilePath="
FQ="$MSC/Comfy-Org/flux1-schnell/repo?Revision=master&FilePath="
TE="$MSC/AI-ModelScope/flux_text_encoders/repo?Revision=master&FilePath="
AE="$MSC/AI-ModelScope/FLUX.1-schnell/repo?Revision=master&FilePath="

dl(){ # $1=url $2=subdir $3=filename $4=threads
  mkdir -p "$STORE/$2"
  "$NODE" "$DL" "$1" "$STORE/$2/$3" "${4:-10}"
  ln -sfn "$STORE/$2/$3" "$LIVE/$2/$3"     # 软链进 ComfyUI 实际读取目录
  rm -f "$LIVE/$2/$3.done" 2>/dev/null || true
}

log "=== 开始 $(date '+%F %T') —— 目标 ≈43.1 GB（Qwen-Image-Edit 20.4 + FLUX 22.7）==="
# ---- Phase 2：Qwen-Image-Edit（一致性/改图）----
dl "${QE}split_files%2Fdiffusion_models%2Fqwen_image_edit_fp8_e4m3fn.safetensors" \
   diffusion_models qwen_image_edit_fp8_e4m3fn.safetensors 10
# ---- T2：FLUX.1-schnell 线 ----
dl "${FQ}flux1-schnell-fp8.safetensors"                        diffusion_models flux1-schnell-fp8.safetensors 10
dl "${TE}t5xxl_fp8_e4m3fn.safetensors"                          text_encoders    t5xxl_fp8_e4m3fn.safetensors 10
dl "${TE}clip_l.safetensors"                                    text_encoders    clip_l.safetensors 8
dl "${AE}ae.safetensors"                                        vae              ae.safetensors 6

log "=== 校验（字节 + .done + safetensors 头结构）==="
fail=0
chk(){ f="$1"; want="$2"
  got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  if [ ! -f "$f.done" ]; then log "FAIL $(basename "$f") 缺 .done 标记（可能仍是稀疏预分配/未写完）"; fail=$((fail+1)); return; fi
  if [[ "$f" == *.safetensors ]] && ! python3 - "$f" <<'PY'
import json, struct, sys
with open(sys.argv[1], "rb") as fh:
    n = struct.unpack("<Q", fh.read(8))[0]
    if not (0 < n < 200_000_000): raise SystemExit(1)
    json.loads(fh.read(n))
PY
  then log "FAIL $(basename "$f") safetensors 头异常"; fail=$((fail+1)); return; fi
  log "OK   $(basename "$f")  $got（软链→$STORE）"; }
chk "$LIVE/diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors" 20430635136
chk "$LIVE/diffusion_models/flux1-schnell-fp8.safetensors"          17236328572
chk "$LIVE/text_encoders/t5xxl_fp8_e4m3fn.safetensors"               4893934904
chk "$LIVE/text_encoders/clip_l.safetensors"                          246144152
chk "$LIVE/vae/ae.safetensors"                                        335304388
[ "$fail" = "0" ] && log "=== ALL_DONE $(date '+%F %T') ===" || log "=== FINISHED_WITH_ERRORS($fail) ==="
