#!/usr/bin/env bash
# 远程自检：确认 GPU 服务器上的 LatentSync 节点补丁版本与能力（在**任意能访问网关的机器**上跑）。
#   bash verify.sh [网关地址]      默认 http://36.103.182.217:30250
set -euo pipefail
GW="${1:-http://36.103.182.217:30250}"
echo "查询 $GW/weaveora/version ..."
BODY="$(curl -fsS -m 20 "$GW/weaveora/version" || true)"
if [ -z "$BODY" ]; then
  echo "!! 取不到版本接口 —— 说明 GPU 机上的节点**还没打补丁**（或 ComfyUI 未重启）。"
  exit 1
fi
echo "$BODY"
REQ=("point_lock" "inline_spec" "track_lock" "quality_gate" "paste_mask" "fps_pin")
MISS=""
for f in "${REQ[@]}"; do
  case "$BODY" in *"\"$f\""*) ;; *) MISS="$MISS $f" ;; esac
done
if [ -n "$MISS" ]; then
  echo "!! 缺少能力:$MISS —— worker 会拒绝跑对口型，请在 GPU 机上重新 apply.sh 并重启 ComfyUI。"
  exit 2
fi
echo "OK：能力齐全 ✅"
