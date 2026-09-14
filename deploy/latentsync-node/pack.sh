#!/usr/bin/env bash
# 打包节点补丁并上传到 API 服务器 webroot（GPU 机用 curl 拉取）。
#   bash pack.sh [webroot]    默认 /opt/weaveora/web/weaveora-node
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBROOT="${1:-/opt/weaveora/web/weaveora-node}"
SSH_KEY="${WEAVEORA_SSH_KEY:-$HOME/.ssh/comfy_tunnel_ed25519}"
SSH_TARGET="${WEAVEORA_SSH_TARGET:-root@sysou.com}"

tar czf "$HERE/latentsync-node-patch.tar.gz" -C "$(dirname "$HERE")" latentsync-node
echo "已生成 $HERE/latentsync-node-patch.tar.gz ($(du -h "$HERE/latentsync-node-patch.tar.gz" | cut -f1))"

echo "上传到 $SSH_TARGET:$WEBROOT ..."
ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_TARGET" "mkdir -p '$WEBROOT'"
scp -i "$SSH_KEY" -q "$HERE/latentsync-node-patch.tar.gz" "$SSH_TARGET:$WEBROOT/"
# 顺手放一份解包好的目录，便于只想取单文件 / 直接读 apply.sh
ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_TARGET" "cd '$WEBROOT' && tar xzf latentsync-node-patch.tar.gz && chmod +x latentsync-node/*.sh && ls -la '$WEBROOT'"
echo
echo "GPU 服务器上执行："
echo "  curl -fsSL https://sysou.com/weaveora-node/latentsync-node-patch.tar.gz -o /tmp/p.tar.gz && tar xzf /tmp/p.tar.gz -C /tmp && bash /tmp/latentsync-node/apply.sh"
