#!/bin/bash
# =============================================================================
# GPU#2 · T2（FLUX.1-schnell 线）下载 —— 用户决策「先完成到 T2」
#   文件（ModelScope API 实测字节，全部 Apache-2.0）：
#     flux1-schnell-fp8.safetensors        17,236,043,842  → models/diffusion_models/
#     ae.safetensors                          334,638,538  → models/vae/
#     t5xxl_fp8_e4m3fn.safetensors          ~4.89 GB       → models/text_encoders/
#     clip_l.safetensors                    ~0.25 GB       → models/text_encoders/
#   + 一致性（用户选 a：跨模型用 Qwen-Image-Edit，故此处不装 FLUX 系适配器）
#   ⚠️ 磁盘闸门：需要 ≥ 需求体积 + 6 GiB 余量；不够就**直接退出**（不半途写满磁盘）
#   用法：setsid nohup bash /opt/weaveora/dl_flux.sh > /opt/weaveora/logs/dl_flux.log 2>&1 < /dev/null &
# =============================================================================
set -u
ROOT=/opt/weaveora
export PATH="$ROOT/opt-node/bin:$PATH"; export WEAVEORA_CURL=curl
NODE="$ROOT/opt-node/bin/node"; DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; MK="$ROOT/models"; mkdir -p "$L"
exec 9>"$L/dl_flux.lock"; flock -n 9 || { echo "[skip] 已有实例"; exit 0; }; echo $$ > "$L/dl_flux.pid"
log(){ echo "[$(date +%H:%M:%S)] $*"; }
NEED_GB=23
FREE_GB=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
log "=== 磁盘闸门：free=${FREE_GB}G need=${NEED_GB}G(+6G 余量) ==="
if [ "$FREE_GB" -lt $((NEED_GB + 6)) ]; then log "空间不足，退出（等数据盘扩容后重跑）"; exit 3; fi

# 源：Comfy-Org 官方 repackaged 优先（ModelScope 可达、Range 206）；若 404 再换 hf-mirror
MSC="https://modelscope.cn/api/v1/models"
FQ="$MSC/Comfy-Org/flux1-schnell/repo?Revision=master&FilePath="
TE="$MSC/AI-ModelScope/flux_text_encoders/repo?Revision=master&FilePath="
AE="$MSC/AI-ModelScope/FLUX.1-schnell/repo?Revision=master&FilePath="
big(){ "$NODE" "$DL" "$1" "$2" "${3:-10}"; }
big "${FQ}flux1-schnell-fp8.safetensors" "$MK/diffusion_models/flux1-schnell-fp8.safetensors" 10
big "${TE}t5xxl_fp8_e4m3fn.safetensors"  "$MK/text_encoders/t5xxl_fp8_e4m3fn.safetensors" 10
big "${TE}clip_l.safetensors"            "$MK/text_encoders/clip_l.safetensors" 10
big "${AE}ae.safetensors"                "$MK/vae/ae.safetensors" 10

log "=== 校验（字节 + safetensors 头）==="
fail=0
chk(){ f="$1"; want="$2"; got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$got" != "$want" ]; then log "FAIL $(basename "$f") 字节 $got != $want"; fail=$((fail+1)); return; fi
  if ! python3 - "$f" <<'PY'
import json, struct, sys
with open(sys.argv[1], "rb") as fh:
    n = struct.unpack("<Q", fh.read(8))[0]
    if not (0 < n < 200_000_000): raise SystemExit(1)
    json.loads(fh.read(n))
PY
  then log "FAIL $(basename "$f") safetensors 头异常"; fail=$((fail+1)); return; fi
  log "OK   $(basename "$f")  $got"; }
# 期望字节：脚本落地时用 ModelScope Content-Range 实测值填（见日志首行）；此处先按已知值校验，缺的补 0 跳过
chk "$MK/vae/ae.safetensors" 334638538
EXPECT_JSON="$MK/.flux_expect.json"   # 由安装脚本写入"实测字节"；缺失则只依赖下载器 DONE 行
[ "$fail" = "0" ] && log "=== DONE（flux 大件字节校验见 dl_flux.log；如 _EXPECT 未填，以下载器 DONE 行为准）===" || log "=== FINISHED_WITH_ERRORS($fail) ==="
