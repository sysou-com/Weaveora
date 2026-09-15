#!/usr/bin/env bash
# =============================================================================
# GPU#2 开机/重启后**预热**：把出图那套大权重先读进显存/内存，
# 让用户的第一个任务不再付冷启动成本（实测重启后首张图曾要 12 分钟）。
#
# 用法（后台静默，非阻塞）：
#   setsid nohup bash /opt/weaveora/warmup.sh > /opt/weaveora/logs/warmup.log 2>&1 < /dev/null &
# 配置：
#   WEAVEORA_WARMUP=qwen|off   （默认 qwen；off = 不预热）
# 纪律：
#   · **队列非空就不预热**（绝不插队生产任务）；
#   · flock 单实例；只跑 1 步 + 极小分辨率，目的是"把权重读进显存"，不是出图；
#   · 预热有自己的日志，失败只告警不阻断服务启动。
# =============================================================================
set -u
ROOT=/opt/weaveora
LOG="$ROOT/logs/warmup.log"
mkdir -p "$ROOT/logs"
WANT="${WEAVEORA_WARMUP:-qwen}"

log(){ echo "[$(date +%H:%M:%S)] $*"; }

if [ "$WANT" = "off" ]; then log "WEAVEORA_WARMUP=off，跳过预热"; exit 0; fi
exec 9>"$ROOT/logs/warmup.lock" 2>/dev/null || exit 0
flock -n 9 || { log "已有预热实例在跑"; exit 0; }

# ---- 等 ComfyUI 就绪（最多 5 分钟）----
CODE=000
for _ in $(seq 1 30); do
  CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8001/system_stats 2>/dev/null || echo 000)
  [ "$CODE" = "200" ] && break
  sleep 10
done
if [ "$CODE" != "200" ]; then log "ComfyUI 未就绪（last=$CODE），放弃预热"; exit 1; fi
log "ComfyUI 就绪，开始预热（plan=$WANT）"

# ---- 队列非空 → 不插队 ----
Q=$(curl -s -m 8 http://127.0.0.1:8001/queue 2>/dev/null || echo '{}')
case "$Q" in
  *'"queue_running": []'*'"queue_pending": []'*) ;;
  *) log "队列非空，跳过预热（不插队生产任务）"; exit 0 ;;
esac

warm_qwen(){   # 出图栈：UNET 20.4G + Qwen2.5-VL 7.9G + VAE（Qwen-Image）
  log "→ 预热出图栈（Qwen-Image + Qwen2.5-VL，1 步 / 320x192）"
  python3 - "$ROOT" <<'PY'
import json, sys, time, urllib.request
root = sys.argv[1]
g = {
 "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_fp8_e4m3fn.safetensors", "weight_dtype": "default"}},
 "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl_7b_fp8_scaled.safetensors", "type": "qwen_image", "device": "default"}},
 "3": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
 "5": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3.0}},
 "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": "warmup"}},
 "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": ""}},
 "8": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 320, "height": 192, "batch_size": 1}},
 "9": {"class_type": "KSampler", "inputs": {"model": ["5", 0], "positive": ["6", 0], "negative": ["7", 0],
        "latent_image": ["8", 0], "seed": 1, "steps": 1, "cfg": 1.0,
        "sampler_name": "euler", "scheduler": "simple", "denoise": 1.0}},
 "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
 "11": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "weaveora_warmup"}},
}
t0 = time.time()
req = urllib.request.Request("http://127.0.0.1:8001/prompt",
        data=json.dumps({"prompt": g, "client_id": "warmup"}).encode(),
        headers={"Content-Type": "application/json"})
pid = json.loads(urllib.request.urlopen(req, timeout=60).read().decode()).get("prompt_id")
print("   prompt_id=%s" % pid, flush=True)
while time.time() - t0 < 1800:
    time.sleep(10)
    try:
        h = json.loads(urllib.request.urlopen("http://127.0.0.1:8001/history/%s" % pid, timeout=30).read().decode())
    except Exception:
        continue
    if pid in h:
        print("   预热完成：%.0f 秒" % (time.time() - t0), flush=True)
        break
else:
    print("   预热超时", flush=True)
PY
}

case "$WANT" in
  qwen) warm_qwen ;;
  both) warm_qwen ;;   # 出片/整脸口型的预热需要输入素材，见文件头说明，暂只预热出图栈
  *) log "未知 plan=$WANT，按 qwen 处理"; warm_qwen ;;
esac

log "预热结束"
