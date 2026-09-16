#!/usr/bin/env bash
# =============================================================================
# 升级到 Qwen-Image-Edit-**2511** + Qwen-Image-**2512**（用户要求强上 2511）
#   2511 变体名是 fp8mixed / bf16 / int8_convrot（不是 fp8_e4m3fn）—— 官方 Comfy-Org 仓库里就有。
#   选 fp8mixed（19.12 GiB）：ComfyUI 的 MixedPrecisionOps 口径，与现网一致。
# 落盘：数据盘 /addDisk/weaveora/models/diffusion_models/ + 软链到 /opt/weaveora/models/…
# 纪律：10 路 Range + .meta.json 续传 + .done；后台静默；WEAVEORA_CURL=curl（下载器默认调 curl.exe）
# 进度：tail -3 /opt/weaveora/logs/dl_upgrade.log（出现 UPGRADE_DONE 即完成）
# =============================================================================
set -u
ROOT=/opt/weaveora
STORE=/addDisk/weaveora/models
export PATH="$ROOT/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl
NODE="$ROOT/opt-node/bin/node"
DL="$ROOT/gpu_model_downloader.js"
L="$ROOT/logs"; mkdir -p "$L" "$STORE/diffusion_models"
LOG="$L/dl_upgrade.log"
exec 9>"$L/dl_upgrade.lock"; flock -n 9 || { echo "[skip] 已有实例"; exit 0; }

log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

FREE_GB=$(df -BG --output=avail "$STORE" | tail -1 | tr -dc '0-9')
log "=== 数据盘可用 ${FREE_GB}G（需要 ~39G：2511 19.12 + 2512 19.03）==="
[ "$FREE_GB" -ge 45 ] || { log "空间不足，退出"; exit 3; }

MSC="https://modelscope.cn/api/v1/models"
QI="$MSC/Comfy-Org/Qwen-Image_ComfyUI/repo?Revision=master&FilePath="
QE="$MSC/Comfy-Org/Qwen-Image-Edit_ComfyUI/repo?Revision=master&FilePath="

dl(){
  "$NODE" "$DL" "$1" "$STORE/diffusion_models/$2" "${3:-10}"
  ln -sfn "$STORE/diffusion_models/$2" "$ROOT/models/diffusion_models/$2"
  rm -f "$ROOT/models/diffusion_models/$2.done" 2>/dev/null || true
}

log "=== ① 基座 Qwen-Image-2512（19.03 GiB）==="
dl "${QI}split_files%2Fdiffusion_models%2Fqwen_image_2512_fp8_e4m3fn.safetensors" \
   qwen_image_2512_fp8_e4m3fn.safetensors 10

log "=== ② 改图 Qwen-Image-Edit-2511 fp8mixed（19.12 GiB）==="
dl "${QE}split_files%2Fdiffusion_models%2Fqwen_image_edit_2511_fp8mixed.safetensors" \
   qwen_image_edit_2511_fp8mixed.safetensors 10

log "=== 校验（字节 + .done + safetensors 头）==="
chk(){ f="$STORE/diffusion_models/$1"; want="$2"
  got=$(stat -c%s "$f" 2>/dev/null || echo 0)
  [ "$got" = "$want" ] || { log "FAIL $1 字节 $got != $want"; return; }
  [ -f "$f.done" ] || { log "FAIL $1 缺 .done"; return; }
  "$ROOT/ComfyUI/venv/bin/python" - "$f" <<'PY' && log "OK   $1 $got" || log "FAIL $1 safetensors 头异常"
import json, struct, sys
fh = open(sys.argv[1], "rb"); n = struct.unpack("<Q", fh.read(8))[0]
assert 0 < n < 200_000_000; json.loads(fh.read(n))
PY
  sha256sum "$f" >> "$L/sha256_manifest.txt"
}
chk qwen_image_2512_fp8_e4m3fn.safetensors 20430679144
chk qwen_image_edit_2511_fp8mixed.safetensors 20533762817
log "=== UPGRADE_DONE ==="
