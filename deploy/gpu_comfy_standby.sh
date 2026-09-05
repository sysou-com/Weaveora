#!/usr/bin/env bash
# Weaveora GPU comfy 待命供给（幂等）——容器重建后一键恢复：
#   comfy 启动 + IPAdapter_plus 节点 + 常驻 worker（守护循环，单实例锁）。
# 用法（在能 ssh GPU 机处）：
#   scp -P 40026 deploy/gpu_comfy_standby.sh root@222.211.217.183:/data/weaveora/
#   ssh -p 40026 root@222.211.217.183 'bash /data/weaveora/gpu_comfy_standby.sh 2>&1 | tee /data/weaveora/gpu_standby.log'
set -uo pipefail
DATA=/data/weaveora
MODELS=/data/comfyui/models
COMFY=/opt/ComfyUI
IPA_REPO=https://github.com/cubiq/ComfyUI_IPAdapter_plus.git
IPA_DIR=$COMFY/custom_nodes/ComfyUI_IPAdapter_plus
log(){ echo "[$(date +%H:%M:%S)] $*"; }

log "== 1. 模型软链 =="
mkdir -p "$MODELS"/{checkpoints,ipadapter,clip_vision,unet,text_encoders,vae,upscale_models}
if [ -L "$COMFY/models" ]; then log "symlink ok -> $(readlink "$COMFY/models")";
else ln -s "$MODELS" "$COMFY/models" && log "symlink created"; fi

log "== 2. IPAdapter_plus 节点 =="
if [ -d "$IPA_DIR/.git" ]; then
  (cd "$IPA_DIR" && git pull --ff-only -q) && log "ipadapter_plus updated"
else
  git clone --depth=1 "$IPA_REPO" "$IPA_DIR" && log "ipadapter_plus cloned"
fi

log "== 3. 启动 comfy =="
if pgrep -f '[m]ain.py' >/dev/null; then
  log "comfy already running"
else
  cd "$COMFY" && setsid nohup python main.py --listen 0.0.0.0 --port 8188 \
    > /data/weaveora/comfy.log 2>&1 < /dev/null &
  disown
  log "comfy launched"
fi
for i in $(seq 1 30); do
  if curl -s -m3 http://127.0.0.1:8188/system_stats >/dev/null 2>&1; then break; fi
  sleep 4
done
curl -s -m8 http://127.0.0.1:8188/system_stats >/dev/null 2>&1 && log "comfy health OK" || log "comfy health FAIL"
IPA_NODES=$(curl -s -m10 http://127.0.0.1:8188/object_info | python3 -c \
  "import json,sys;d=json.load(sys.stdin);print(';'.join(k for k in d if 'IPAdapter' in k))" 2>/dev/null)
log "IPAdapter nodes: $IPA_NODES"

log "== 4. 常驻 worker（守护循环 + 单实例锁）=="
cat > "$DATA/gpu_run.sh" <<'SH'
#!/usr/bin/env bash
cd /data/weaveora
lock=/data/weaveora/.worker.lock
if ! mkdir "$lock" 2>/dev/null; then exit 0; fi
trap 'rmdir "$lock" 2>/dev/null' EXIT
export WEAVEORA_WORKER_MODE=comfy
export WEAVEORA_API_BASE=https://sysou.com/weaveora
export WEAVEORA_COMFY_URL=http://127.0.0.1:8188
export WEAVEORA_WORKER_NAME=gpu-comfy-worker
while :; do
  python3 stub_worker.py
  echo "[$(date +%H:%M:%S)] worker-exited rc=$?; restart in 6s" >> gpu_worker.log
  sleep 6
done
SH
chmod +x "$DATA/gpu_run.sh"
if pgrep -f '[g]pu_run.sh' >/dev/null || pgrep -f 'stub_worker.py' >/dev/null; then
  log "worker already running"
else
  setsid nohup bash "$DATA/gpu_run.sh" >> "$DATA/gpu_worker.log" 2>&1 < /dev/null &
  disown
  log "worker supervisor launched"
fi
sleep 3
pgrep -af 'stub_worker.py|gpu_run.sh' | grep -v grep | cut -c1-90
log "STANDBY_READY"
