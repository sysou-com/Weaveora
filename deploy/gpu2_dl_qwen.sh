#!/bin/bash
# =============================================================================
# GPU#2 · 文生图 Phase 1 下载 —— Qwen-Image 全家 + Lightning 加速 + Qwen-Image-Edit（一致性）
#   遵守 Weaveora.md §0.2：10 路 Range 分片 + .meta.json 断点续传 + 后台静默 + 字节/结构校验
#   总计 ≈52.2 GB（ModelScope API 端点，本机实测 8–25 MiB/s）
#   用法：setsid nohup bash /opt/weaveora/dl_qwen.sh > /opt/weaveora/logs/dl_qwen.log 2>&1 < /dev/null &
#   进度：tail -n 3 /opt/weaveora/logs/dl_qwen.log      （只做短查）
#   叫停：kill $(cat /opt/weaveora/logs/dl_qwen.pid)     （重跑自动续传）
# =============================================================================
set -u
ROOT=/opt/weaveora
export PATH="$ROOT/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl                 # 下载器默认 curl.exe（Windows 口径），Linux 必须覆盖
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; MK="$ROOT/models"; mkdir -p "$L"
exec 9>"$L/dl_qwen.lock"
if ! flock -n 9; then echo "[$(date +%H:%M:%S)] [skip] 已有 dl_qwen 实例"; exit 0; fi
echo $$ > "$L/dl_qwen.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
big(){ "$NODE" "$DL" "$1" "$2" "${3:-10}"; }
MSC="https://modelscope.cn/api/v1/models"
QI="$MSC/Comfy-Org/Qwen-Image_ComfyUI/repo?Revision=master&FilePath="
QE="$MSC/Comfy-Org/Qwen-Image-Edit_ComfyUI/repo?Revision=master&FilePath="
LT="$MSC/lightx2v/Qwen-Image-Lightning/repo?Revision=master&FilePath="

# PHASE=1 → 只下 Qwen-Image 主力三件 + Lightning（≈31.8 GB，当前盘可容纳）
# PHASE=2 → 追加 Qwen-Image-Edit 一致性模型（+20.43 GB，需先扩盘：实测 196G 盘只余 ~16 G）
PHASE="${WEAVEORA_QWEN_PHASE:-1}"
log "=== Phase $PHASE 开始 $(date '+%F %T')（PHASE=1 ≈31.8 GB；PHASE=2 追加 Edit +20.4 GB）==="
big "${QI}split_files%2Fdiffusion_models%2Fqwen_image_fp8_e4m3fn.safetensors"            "$MK/diffusion_models/qwen_image_fp8_e4m3fn.safetensors" 10
big "${QI}split_files%2Ftext_encoders%2Fqwen_2.5_vl_7b_fp8_scaled.safetensors"           "$MK/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors" 10
big "${QI}split_files%2Fvae%2Fqwen_image_vae.safetensors"                                "$MK/vae/qwen_image_vae.safetensors" 10
big "${LT}Qwen-Image-Lightning-8steps-V1.0.safetensors"                                  "$MK/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors" 10
if [ "$PHASE" = "2" ]; then
  big "${QE}split_files%2Fdiffusion_models%2Fqwen_image_edit_fp8_e4m3fn.safetensors"     "$MK/diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors" 10
else
  log "（跳过 Qwen-Image-Edit：需先扩盘；要用时 WEAVEORA_QWEN_PHASE=2 重跑本脚本）"
fi

log "=== 校验（字节 + safetensors 头结构）==="
fail=0
chk(){ f="$1"; want="$2"
  got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  if ! python3 - "$f" <<'PY'
import json, struct, sys
with open(sys.argv[1], "rb") as fh:
    n = struct.unpack("<Q", fh.read(8))[0]
    if not (0 < n < 200_000_000):
        raise SystemExit(1)
    json.loads(fh.read(n))
PY
  then log "FAIL $(basename "$f") safetensors 头异常"; fail=$((fail+1)); return; fi
  log "OK   $(basename "$f")  $got"; }
chk "$MK/diffusion_models/qwen_image_fp8_e4m3fn.safetensors"         20430635136
chk "$MK/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors"         9384670680
chk "$MK/vae/qwen_image_vae.safetensors"                               253806246
chk "$MK/loras/Qwen-Image-Lightning-8steps-V1.0.safetensors"          1698951104
if [ "$PHASE" = "2" ]; then chk "$MK/diffusion_models/qwen_image_edit_fp8_e4m3fn.safetensors" 20430635136; fi
[ "$fail" = "0" ] && log "=== ALL_DONE $(date '+%F %T') ===" || log "=== FINISHED_WITH_ERRORS($fail) ==="
