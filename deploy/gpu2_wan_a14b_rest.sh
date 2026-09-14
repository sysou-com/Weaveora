#!/bin/bash
# =============================================================================
# Wan2.2 I2V-A14B 剩余 3 个大件（GPU#2）—— 改用 ModelScope **API 端点**
#
# 为什么换端点（2026-09-14 本机实测）：
#   resolve 端点 https://www.modelscope.cn/models/{o}/{r}/resolve/master/{p}   → 0.38 MB/s（被限流，且会中途挂死）
#   API   端点 https://modelscope.cn/api/v1/models/{o}/{r}/repo?Revision=master&FilePath={urlencode(p)} → **6.9 MB/s**
#   = 约 18 倍差距；下载脚本里的大件一律用 API 端点。
#
# 纪律：≥200MiB 走 gpu_model_downloader.js（10 路 Range 分片 + 断点续传 + .done）；
#       单实例 flock；大件校验 = 字节数 + safetensors 头结构（quick）；后台静默启动。
# =============================================================================
set -u

R=/opt/weaveora
MOD=$R/models
DL=$R/gpu_model_downloader.js
export PATH="$R/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl
NODE=node
TH=10
LOGD=$R/logs
mkdir -p "$LOGD"
LOG=$LOGD/dl_wan_rest.log
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

exec 9>"$LOGD/dl_wan_rest.lock"
if ! flock -n 9; then echo "[$(date '+%F %T')] 已有实例在跑，退出" | tee -a "$LOG"; exit 0; fi

API=https://modelscope.cn/api/v1/models/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/repo
TARGETS=(
"text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors|$API?Revision=master&FilePath=split_files%2Ftext_encoders%2Fumt5_xxl_fp8_e4m3fn_scaled.safetensors|6735906897|quick"
"diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors|$API?Revision=master&FilePath=split_files%2Fdiffusion_models%2Fwan2.2_i2v_high_noise_14B_fp8_scaled.safetensors|14294742832|quick"
"diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors|$API?Revision=master&FilePath=split_files%2Fdiffusion_models%2Fwan2.2_i2v_low_noise_14B_fp8_scaled.safetensors|14294742832|quick"
)

verify_quick() {  # $1=path $2=期望字节 → 0 通过
  local f="$1" exp="$2" sz
  sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  [ "$sz" = "$exp" ] || { echo "字节不符 got=$sz exp=$exp"; return 1; }
  python3 - "$f" "$exp" <<'PYEOF'
import json, struct, sys
p, exp = sys.argv[1], int(sys.argv[2])
with open(p, 'rb') as fh:
    n = struct.unpack('<Q', fh.read(8))[0]
    if not (0 < n < 200000000):
        print("safetensors 头长度异常: %d" % n); sys.exit(1)
    hdr = json.loads(fh.read(n).decode('utf-8'))
tensors = {k: v for k, v in hdr.items() if k != '__metadata__'}
if not tensors:
    print("safetensors 无张量"); sys.exit(1)
end = max(v['data_offsets'][1] for v in tensors.values())
total = 8 + n + end
if total != exp:
    print("张量数据范围与文件大小不符: %d != %d" % (total, exp)); sys.exit(1)
print("safetensors 结构 OK：%d 个张量 / 数据 %d 字节" % (len(tensors), end))
PYEOF
}

log "================ A14B 剩余大件 START（API 端点）================"
df -h "$R" | tail -1
ok=0; fail=0
for row in "${TARGETS[@]}"; do
  IFS='|' read -r rel url size mode <<< "$row"
  out="$MOD/$rel"; base=$(basename "$rel"); mkdir -p "$(dirname "$out")"
  if [ -f "$out.done" ] && [ "$(stat -c%s "$out")" = "$size" ]; then
    log "SKIP(已 done) $base"; ok=$((ok+1)); continue
  fi
  if [ -f "$out" ] && [ "$(stat -c%s "$out")" = "$size" ]; then
    if verify_quick "$out" "$size" >/dev/null 2>&1; then log "SKIP(已校验) $base"; ok=$((ok+1)); continue; fi
    log "RETRY(校验不过) $base —— 删残片重下"; rm -f "$out" "$out.done" "$out.meta.json"
  fi
  log "BIG  $base  (期望 $((size/1048576)) MiB)"
  t0=$(date +%s)
  $NODE "$DL" "$url" "$out" "$TH" >> "$LOG" 2>&1
  rc=$?; t1=$(date +%s)
  if [ $rc -ne 0 ]; then log "FAIL(downloader rc=$rc) $base"; fail=$((fail+1)); continue; fi
  if v=$(verify_quick "$out" "$size"); then
    log "OK   $base  $((size/1048576)) MiB  用时 $((t1-t0))s  ✓  $v"; ok=$((ok+1))
  else
    log "FAIL(校验) $base  $v"; fail=$((fail+1))
  fi
done
log "================ 结果 OK=$ok FAIL=$fail ================"
[ "$fail" -eq 0 ] && log "ALL_DONE" || log "FINISHED_WITH_ERRORS"
find "$MOD" -name '*.safetensors' -printf '%10s  %p\n' 2>/dev/null | sort -rn | awk '{printf "%8.2f GiB  %s\n", $1/1073741824, $2}' | tee -a "$LOG"
df -h "$R" | tail -1 | tee -a "$LOG"
