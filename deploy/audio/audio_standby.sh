#!/usr/bin/env bash
# Weaveora GPU 自托管音频待命（幂等）——TTS(CosyVoice) + 音乐(ACE-Step) + audio worker。
# 用法（在能 ssh GPU 机处）：
#   scp deploy/audio/*.py deploy/audio/audio_standby.sh root@<gpu>:/data/weaveora/
#   scp worker/stub_worker.py worker/audio_client.py root@<gpu>:/data/weaveora/
#   ssh root@<gpu> 'bash /data/weaveora/audio_standby.sh 2>&1 | tee /data/weaveora/audio_standby.log'
set -uo pipefail
DATA=/data/weaveora
cd "$DATA" || exit 1
log(){ echo "[$(date +%H:%M:%S)] $*"; }

log "== 1. 环境 =="
export WEAVEORA_WORKER_MODE=comfy
export WEAVEORA_API_BASE=https://sysou.com/weaveora
export WEAVEORA_WORKER_NAME=gpu-comfy-worker
export WEAVEORA_TTS_URL=http://127.0.0.1:8091
export WEAVEORA_MUSIC_URL=http://127.0.0.1:8092
export WEAVEORA_COSYVOICE_DIR=${WEAVEORA_COSYVOICE_DIR:-/data/audio/CosyVoice}
export WEAVEORA_COSYVOICE_MODEL=${WEAVEORA_COSYVOICE_MODEL:-pretrained_models/CosyVoice2-0.5B}
export WEAVEORA_MUSIC_ENGINE=${WEAVEORA_MUSIC_ENGINE:-ace-step}
export WEAVEORA_MUSIC_CKPT=${WEAVEORA_MUSIC_CKPT:-/data/audio/ACE-Step/checkpoints}
export WEAVEORA_COMFY_URL=${WEAVEORA_COMFY_URL:-http://127.0.0.1:8188}

log "== 2. 启动 TTS(:8091) =="
if curl -s -m3 http://127.0.0.1:8091/health >/dev/null 2>&1; then
  log "tts already running"
else
  setsid nohup python3 "$DATA/tts_server.py" 8091 > "$DATA/audio_tts.log" 2>&1 < /dev/null &
  disown; log "tts launched"
fi

log "== 3. 启动 音乐(:8092) =="
if curl -s -m3 http://127.0.0.1:8092/health >/dev/null 2>&1; then
  log "music already running"
else
  setsid nohup python3 "$DATA/music_server.py" 8092 > "$DATA/audio_music.log" 2>&1 < /dev/null &
  disown; log "music launched"
fi

log "== 4. 常驻 worker（守护循环 + 单实例锁）=="
cat > "$DATA/audio_run.sh" <<'SH'
#!/usr/bin/env bash
cd /data/weaveora
lock=/data/weaveora/.audio-worker.lock
if ! mkdir "$lock" 2>/dev/null; then exit 0; fi
trap 'rmdir "$lock" 2>/dev/null' EXIT
while :; do
  python3 stub_worker.py
  echo "[$(date +%H:%M:%S)] worker-exited rc=$?; restart in 6s" >> audio_worker.log
  sleep 6
done
SH
chmod +x "$DATA/audio_run.sh"
if pgrep -f '[a]udio_run.sh' >/dev/null || pgrep -f '[s]tub_worker.py' >/dev/null; then
  log "worker already running"
else
  setsid nohup bash "$DATA/audio_run.sh" >> "$DATA/audio_worker.log" 2>&1 < /dev/null &
  disown; log "worker supervisor launched"
fi

sleep 3
curl -s -m3 http://127.0.0.1:8091/health && echo " <- tts" || log "tts health FAIL（模型可能仍在加载，首次请求会触发）"
curl -s -m3 http://127.0.0.1:8092/health && echo " <- music" || log "music health FAIL（模型可能仍在加载）"
pgrep -af 'stub_worker.py|audio_run.sh' | grep -v grep | cut -c1-90
log "AUDIO_STANDBY_READY"
