#!/usr/bin/env bash
# 把「开发机上已打好补丁的 ComfyUI 节点文件」同步进 deploy/latentsync-node/files/
# （让仓库副本 = 正在验证的版本；GPU 机上的副本由 apply.sh 从这里覆盖）
#
# 用法： bash sync_from_local.sh [节点目录]
set -euo pipefail
NODE="${1:-/d/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES=(
  "latentsync/utils/face_detector.py"
  "latentsync/utils/image_processor.py"
  "latentsync/pipelines/lipsync_pipeline.py"
  "nodes.py"
  "scripts/inference.py"
)
for f in "${FILES[@]}"; do
  mkdir -p "$HERE/files/$(dirname "$f")"
  cp "$NODE/$f" "$HERE/files/$f"
  printf "  synced %-46s %s 行\n" "$f" "$(wc -l < "$HERE/files/$f")"
done
VER="$(grep -m1 '^WEAVEORA_NODE_VERSION' "$HERE/files/nodes.py" | sed 's/.*=\s*"\(.*\)".*/\1/')"
echo "仓库补丁版本 = $VER"
echo "提醒：改了节点代码要 bump nodes.py 里的 WEAVEORA_NODE_VERSION，并在 docs/lipsync-setup.md 第九节登记。"
