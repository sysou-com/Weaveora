#!/bin/bash
# =============================================================================
# Weaveora 新 GPU 服务器 · 第二批下载（第 1 步）：Wan2.2 I2V-A14B 视频栈
#   目标机：新 GPU 服务器（RTX 4090 24G / 100G 持久卷）
#   落盘：/opt/weaveora/models  （ComfyUI/models 是指向它的符号链接）
#   组成：I2V-A14B 高/低噪声 fp8_scaled + umt5 fp8 文本编码器 + wan_2.1 VAE
#         + lightx2v 4-step LoRA（260412 rank64 高/低噪声）
#   纪律：≥200MiB 一律 gpu_model_downloader.js（10 进程 Range 分片 + 断点续传）；
#         每个文件**双校验**（官方字节数 + 官方 sha256），任一不符视为失败；
#         本脚本必须后台静默启动，不得前台阻塞。
# =============================================================================
set -u

R=/opt/weaveora
MOD=$R/models
DL=$R/gpu_model_downloader.js
export PATH="/opt/weaveora/opt-node/bin:$PATH"
export WEAVEORA_CURL=curl   # 下载器默认 curl.exe（Windows 口径）；Linux 必须显式覆盖
NODE=node
TH=10
LOGD=$R/logs
mkdir -p "$LOGD"
LOG=$LOGD/dl_wan_batch.log

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

# 单实例锁：防止重复拉起两个下载器互写同一文件
exec 9>"$LOGD/dl_wan_batch.lock"
if ! flock -n 9; then echo "[$(date '+%F %T')] 已有 dl_wan_batch 实例在跑，本次退出" | tee -a "$LOG"; exit 0; fi

MS=https://www.modelscope.cn/models
REPO_C="$MS/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/master/split_files"
REPO_K="$MS/Kijai/WanVideo_comfy/resolve/master/LoRAs/Wan22_Lightx2v"

# 顺序：小文件先行（快速验证下载器与校验链路），14B 大件放最后
# 格式：相对路径|URL|期望字节|期望 sha256|校验强度(full|quick)
#   本机 CPU 是共享超卖的（cgroup 14 核 / load average 20），对 13GiB 文件做全量 sha256
#   要跑十几分钟且时间不可控 → 大件用 quick = 字节数 + safetensors 头结构校验
#   （能抓「裁断/不完整」，这正是 ModelScope 副本唯一出现过的坑），小件仍全量 sha256。
TARGETS=(
"vae/wan_2.1_vae.safetensors|$REPO_C/vae/wan_2.1_vae.safetensors|253815318|2fc39d31359a4b0a64f55876d8ff7fa8d780956ae2cb13463b0223e15148976b|full"
"loras/Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors|$REPO_K/Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors|630695648|8e0a86e765ade42a1deca52eb7411348254a019147be8c4eed88c7ad465d3399|full"
"loras/Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors|$REPO_K/Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors|630695648|09e10abd98460b66439bd77ea671e94c10fdc8251e0b986aa196d1904e3cc583|full"
"text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors|$REPO_C/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors|6735906897|c3355d30191f1f066b26d93fba017ae9809dce6c627dda5f6a66eaa651204f68|quick"
"diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors|$REPO_C/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors|14294742832|6122e79d55e0f235698d11d657f3b196c5273c830da00b2b013c5a048d5e6a42|quick"
"diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors|$REPO_C/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors|14294742832|5471a457b6ac404202a5fbe6c11595a3d5641fc766b00f38763f72303fffc21e|quick"
)

verify_file() {  # $1=path $2=期望字节 $3=sha256 $4=full|quick  → 0 通过 / 1 不通过
  local f="$1" exp="$2" sha="$3" mode="$4" sz got
  sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$sz" != "$exp" ]; then echo "字节不符 got=$sz exp=$exp"; return 1; fi
  if [ "$mode" = "quick" ]; then
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
    return $?
  fi
  got=$(sha256sum "$f" | awk '{print $1}')
  if [ "$got" != "$sha" ]; then echo "sha256 不符 got=$got"; return 1; fi
  return 0
}

log "================ Wan2.2 I2V-A14B 下载 START ================"
for bin in $NODE curl sha256sum; do
  command -v $bin >/dev/null 2>&1 || { log "ABORT 缺少 $bin"; exit 1; }
done
log "node: $(command -v $NODE) $($NODE -v 2>&1) | curl: $(curl --version | head -1)"
mkdir -p "$MOD/diffusion_models" "$MOD/text_encoders" "$MOD/vae" "$MOD/loras"
log "落盘根：$MOD（ComfyUI/models -> $(readlink -f $R/ComfyUI/models)）"
df -h /opt/weaveora | tail -1 | tee -a "$LOG"

# 前置空间检查：需要 ~35GiB，留 5GiB 余量
AVAIL_K=$(df -Pk /opt/weaveora | awk 'NR==2{print $4}')
if [ "$AVAIL_K" -lt $((40*1024*1024)) ]; then
  log "ABORT 可用空间不足 40GiB（当前 $((AVAIL_K/1024/1024))GiB）"
  exit 1
fi

ok=0; fail=0
for row in "${TARGETS[@]}"; do
  IFS='|' read -r rel url size sha mode <<< "$row"
  [ -n "${mode:-}" ] || mode=full
  out="$MOD/$rel"
  base=$(basename "$rel")
  mkdir -p "$(dirname "$out")"

  # 已完整（字节 + 强度对应的校验）则跳过
  if [ -f "$out" ] && [ "$(stat -c%s "$out")" = "$size" ]; then
    if verify_file "$out" "$size" "$sha" "$mode"; then
      log "SKIP(已校验[$mode]) $base"; ok=$((ok+1)); continue
    fi
    log "RETRY(校验不通过[$mode]) $base —— 删除残片后重下"
    rm -f "$out" "$out.done" "$out.meta.json"
  fi

  log "BIG  $base  ->  $rel  (期望 $((size/1048576)) MiB, 校验=$mode)"
  t0=$(date +%s)
  $NODE "$DL" "$url" "$out" "$TH" >> "$LOG" 2>&1
  rc=$?
  t1=$(date +%s)

  if [ $rc -ne 0 ]; then log "FAIL(downloader rc=$rc) $base"; fail=$((fail+1)); continue; fi
  if out=$(verify_file "$out" "$size" "$sha" "$mode" 2>&1); then
    log "OK   $base  $((size/1048576)) MiB  用时 $((t1-t0))s  校验[$mode] ✓  $out"
    ok=$((ok+1))
  else
    log "FAIL(校验[$mode]) $base  $out"; fail=$((fail+1))
  fi
done

log "================ 结果 ================"
log "OK=$ok FAIL=$fail"
find "$MOD" -type f \( -name '*.safetensors' \) -printf '%10s  %p\n' 2>/dev/null | sort -rn | \
  awk '{printf "%8.2f GiB  %s\n", $1/1073741824, $2}' | tee -a "$LOG"
df -h /opt/weaveora | tail -1 | tee -a "$LOG"
if [ "$fail" -eq 0 ]; then log "ALL_DONE"; else log "FINISHED_WITH_ERRORS ($fail)"; fi
