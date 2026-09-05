#!/usr/bin/env bash
# Weaveora GPU 供给（SDXL 兼容档）——幂等；模型放持久 /data（镜像自带 ComfyUI 代码，勿动）。
# 用法（在能 ssh GPU 机处）：
#   scp -P 40026 deploy/gpu_provision.sh root@222.211.217.183:/data/weaveora/
#   ssh -p 40026 root@222.211.217.183 'bash /data/weaveora/gpu_provision.sh 2>&1 | tee /data/weaveora/gpu_provision.log'
set -uo pipefail
HF=https://hf-mirror.com
MS='https://www.modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0/resolve/master/sd_xl_base_1.0.safetensors'
DATA=/data
MODELS=$DATA/comfyui/models

log(){ echo "[$(date +%H:%M:%S)] $*"; }
log "== 0. 镜像自带 ComfyUI 代码不动；仅补模型到 $MODELS =="
hostname; df -h "$DATA" | tail -1

# 1) 目录 + 软链（镜像默认就是 /opt/ComfyUI/models -> /data/comfyui/models）
mkdir -p "$MODELS"/{checkpoints,ipadapter,clip_vision,unet,text_encoders,vae,upscale_models}
[ -L /opt/ComfyUI/models ] && readlink /opt/ComfyUI/models || ln -s "$MODELS" /opt/ComfyUI/models
log "symlink: $(readlink /opt/ComfyUI/models)"

dl(){ local u="$1" o="$2"; mkdir -p "$(dirname "$o")"; for i in $(seq 1 120); do
      curl -sL --fail -C - --retry 3 -o "$o.part" "$u" && { mv -f "$o.part" "$o"; log "DONE $(basename $o)"; return 0; }
      log "retry $i $(basename $o)"; sleep 8; done; log "FAIL $(basename $o)"; }

log "== 2. 下载模型（断点续传，日志见同目录 dl.log）=="
dl "$MS" "$MODELS/checkpoints/sd_xl_base_1.0.safetensors"
dl "$HF/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter_sdxl_vit-h.safetensors" "$MODELS/ipadapter/ip-adapter_sdxl_vit-h.safetensors"
dl "$HF/laion/CLIP-ViT-H-14-laion2B-s32B-b79K/resolve/main/model.safetensors" "$MODELS/clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors"
log "ALL_DONE"
ls -lh "$MODELS/checkpoints" "$MODELS/ipadapter" "$MODELS/clip_vision"
