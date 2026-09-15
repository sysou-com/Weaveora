#!/usr/bin/env bash
# Weaveora × LatentSync 节点补丁 —— 一键打到 GPU 服务器上的 ComfyUI 节点目录。
#
# 为什么需要它（docs/lipsync-setup.md 第九节）：
#   worker 跑在 **API 服务器**，LatentSync 节点跑在 **GPU 服务器**，两边代码只能手工同步。
#   曾实测：GPU 机上是旧副本（旧 inference.py 不认内联规格）→ 退回「取最大脸」→ 两段台词都驱动
#   同一张脸、画面被毁，而 worker 侧完全看不出异常。所以：**节点侧改动必须重新打补丁**，
#   并且 worker 会用 /weaveora/version 校验版本与能力，不一致就直接失败（不再静默降级）。
#
# 用法（在 GPU 服务器上执行）：
#   bash apply.sh [节点目录]
#   默认节点目录：/home/dataset-local/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper
#
# 打完后**必须重启 ComfyUI**（节点代码只在启动时加载）。
set -euo pipefail

NODE_DIR="${1:-/home/dataset-local/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/files"

FILES=(
  "latentsync/utils/face_detector.py"
  "latentsync/utils/image_processor.py"
  "latentsync/pipelines/lipsync_pipeline.py"
  "nodes.py"
  "scripts/inference.py"
)

echo "== Weaveora LatentSync 节点补丁 =="
echo "节点目录: $NODE_DIR"
[ -d "$NODE_DIR" ] || { echo "!! 节点目录不存在：$NODE_DIR"; echo "   请用参数指定，例如：bash apply.sh /path/to/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper"; exit 1; }
[ -d "$SRC" ] || { echo "!! 补丁文件缺失：$SRC（应随本脚本一起解压出来）"; exit 1; }

# 找用于语法检查的 python（优先节点自己的 venv）
PY=""
for cand in "$NODE_DIR/../../venv/bin/python" "$NODE_DIR/../venv/bin/python" "/home/dataset-local/weaveora/ComfyUI/venv/bin/python" "$(command -v python3 || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then PY="$cand"; break; fi
done
echo "语法检查用 python: ${PY:-（未找到，跳过）}"

STAMP="$(date +%Y%m%d-%H%M%S)"
BAK="$NODE_DIR/.weaveora_backup_$STAMP"
mkdir -p "$BAK"
echo "备份到: $BAK"

for f in "${FILES[@]}"; do
  [ -f "$SRC/$f" ] || { echo "!! 补丁里缺少 $f"; exit 1; }
  if [ -f "$NODE_DIR/$f" ]; then
    mkdir -p "$BAK/$(dirname "$f")"
    cp -p "$NODE_DIR/$f" "$BAK/$f"
  fi
  mkdir -p "$NODE_DIR/$(dirname "$f")"
  cp "$SRC/$f" "$NODE_DIR/$f"
  if [ -n "$PY" ]; then
    "$PY" -m py_compile "$NODE_DIR/$f" || { echo "!! 语法检查失败：$f（已回滚）"; cp "$BAK/$f" "$NODE_DIR/$f" 2>/dev/null || true; exit 1; }
  fi
  echo "  OK $f"
done

# 打印补丁版本（从 nodes.py 里读，避免两个地方写死）
VER="$(grep -m1 '^WEAVEORA_NODE_VERSION' "$NODE_DIR/nodes.py" | sed 's/.*=\s*"\(.*\)".*/\1/')"
FEATS="$(sed -n '/^WEAVEORA_NODE_FEATURES = \[/,/^\]/p' "$NODE_DIR/nodes.py" | grep -o '"[a-z_]*"' | tr -d '"' | tr '\n' ',' | sed 's/,$//')"
echo
echo "补丁已应用 ✅  版本=$VER"
echo "能力=$FEATS"
echo
echo "⚠️  现在必须**重启 ComfyUI**（节点代码只在启动时加载）。"
RESTART_HINT="$(dirname "$(dirname "$NODE_DIR")")/deploy/services_up.sh"
if [ -f "$RESTART_HINT" ]; then
  echo "  检测到本机启动脚本，执行："
  echo "    pkill -f 'ComfyUI/main.py' ; sleep 3 ; bash $RESTART_HINT"
else
  echo "  例如： pkill -f 'ComfyUI/main.py' ; sleep 3 ; bash <你的 services_up.sh 路径>"
  echo "  或：   sudo systemctl restart <你的 ComfyUI 服务>"
fi
echo "重启后自检（GPU 机本地）： curl -s http://127.0.0.1:8001/weaveora/version"
echo "自检（本脚本带的 verify）： bash $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify.sh http://127.0.0.1:8001"
echo "自检（经公网网关）：       bash verify.sh http://<GPU公网地址:端口>"
