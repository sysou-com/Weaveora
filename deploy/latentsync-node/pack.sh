#!/usr/bin/env bash
# 打包节点补丁并上传到 API 服务器 webroot（GPU 机用 curl 拉取）。
#   bash pack.sh [webroot]    默认 /opt/weaveora/web/weaveora-node
#
# ★ 2026-09-22 两处修复（都是"看起来在工作、其实一直在错"的那类）：
#   1) **换行符**：开发机是 Windows、core.autocrlf=true ⇒ 工作区文件是 CRLF，直接 tar 会把
#      CRLF 带进 Linux：`bash apply.sh` 第一行就 `set: pipefail\r: invalid option name`（实测）。
#      现在打包前先复制到临时目录并统一转 LF（.py 虽能容忍 CRLF，也一起转，保持两侧字节一致）。
#   2) **公开路径**：nginx 的 webroot 是 `/opt/weaveora/web/`，挂在 **`/weaveora/`** 前缀下，
#      所以正确的下载地址是 `https://sysou.com/weaveora/weaveora-node/...`；
#      旧文档里写的 `https://sysou.com/weaveora-node/...` 会被 nginx 的 SPA 回退命中，
#      **HTTP 200 但内容是 index.html**（tar 报 not in gzip format）。现在上传后**回读校验 md5**，
#      不一致就大声报错 —— "200 ≠ 拿到了包"。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBROOT="${1:-/opt/weaveora/web/weaveora-node}"
SSH_KEY="${WEAVEORA_SSH_KEY:-$HOME/.ssh/comfy_tunnel_ed25519}"
SSH_TARGET="${WEAVEORA_SSH_TARGET:-root@sysou.com}"
PUBLIC_URL="${WEAVEORA_NODE_PUBLIC_URL:-https://sysou.com/weaveora/weaveora-node/latentsync-node-patch.tar.gz}"

# ---- 1) 暂存目录 + 统一 LF ----
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/latentsync-node"
mkdir -p "$DEST"
# ★ 只收集该发的：排除 __pycache__/*.pyc（本地 py_compile 的产物，会随包上传并污染 Python 导入缓存）
cp -R "$HERE/files" "$DEST/files"
find "$DEST" \( -name '__pycache__' -o -name '*.pyc' \) -exec rm -rf {} + 2>/dev/null || true
for f in "$HERE"/*.sh "$HERE"/README.md "$HERE"/test_*.py; do
  [ -f "$f" ] && cp "$f" "$DEST/"
done
# 只转文本文件（二进制不在暂存目录里，不会被误伤）
find "$DEST" -type f \( -name '*.sh' -o -name '*.py' -o -name '*.md' \) -print0 |
  while IFS= read -r -d '' f; do
    tr -d '\r' < "$f" > "$f.lf" && mv "$f.lf" "$f"
  done
# 校验用 cmp（比 grep 可靠：MSYS 的 grep/tar 在本机对 CR 的判定会飘）
CRLF_LEFT=""
while IFS= read -r -d '' f; do
  cmp -s <(tr -d '\r' < "$f") "$f" || CRLF_LEFT="$CRLF_LEFT $f"
done < <(find "$DEST" -type f \( -name '*.sh' -o -name '*.py' -o -name '*.md' \) -print0)
echo "暂存: $DEST（已统一 LF）"
if [ -n "$CRLF_LEFT" ]; then
  echo "  !! 仍有 CRLF 残留：$CRLF_LEFT"
else
  echo "  行尾检查：CRLF 0 处 ✓"
fi
PYC_LEFT="$(find "$DEST" -name '*.pyc' | head -3 | tr '\n' ' ')"
[ -z "$PYC_LEFT" ] || echo "  !! 包里仍有 .pyc：$PYC_LEFT"

# ---- 2) 打包（不再把上一版 tarball 打进包里）----
tar czf "$HERE/latentsync-node-patch.tar.gz" -C "$STAGE" latentsync-node
LOCAL_MD5="$(md5sum "$HERE/latentsync-node-patch.tar.gz" | cut -d' ' -f1)"
echo "已生成 $HERE/latentsync-node-patch.tar.gz ($(du -h "$HERE/latentsync-node-patch.tar.gz" | cut -f1) md5=$LOCAL_MD5)"
echo "  内容：$(tar tzf "$HERE/latentsync-node-patch.tar.gz" | wc -l) 项，顶层=$(tar tzf "$HERE/latentsync-node-patch.tar.gz" | head -1)"

# ---- 3) 上传（旧包留 .bak.<ts>，出问题能退回上一版）----
echo "上传到 $SSH_TARGET:$WEBROOT ..."
ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_TARGET" \
  "mkdir -p '$WEBROOT' && cd '$WEBROOT' && [ -f latentsync-node-patch.tar.gz ] && cp -p latentsync-node-patch.tar.gz latentsync-node-patch.tar.gz.bak.\$(date +%Y%m%d-%H%M%S) || true"
scp -i "$SSH_KEY" -q "$HERE/latentsync-node-patch.tar.gz" "$SSH_TARGET:$WEBROOT/"
# 顺手放一份解包好的目录，便于只想取单文件 / 直接读 apply.sh
ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_TARGET" "cd '$WEBROOT' && tar xzf latentsync-node-patch.tar.gz && chmod +x latentsync-node/*.sh && ls -la '$WEBROOT'"

# ---- 4) 回读校验：公网拿到的必须是刚上传的那份包 ----
echo
echo "回读校验（公网 URL 必须等于刚上传的包）: $PUBLIC_URL"
GOT_MD5="$(curl -fsS -m 60 "$PUBLIC_URL" | md5sum | cut -d' ' -f1 || true)"
if [ "$GOT_MD5" = "$LOCAL_MD5" ]; then
  echo "  ✓ md5 一致（$GOT_MD5）"
else
  echo "  !! md5 不一致：本地 $LOCAL_MD5 / 公网 ${GOT_MD5:-<取不到>}"
  echo "     多半是 URL 前缀不对（nginx 会回退成 SPA 的 index.html，HTTP 200 但不是包）——"
  echo "     用 WEAVEORA_NODE_PUBLIC_URL=... 覆盖，或先确认 webroot 与 nginx location。"
fi
echo
echo "GPU 服务器上执行："
echo "  curl -fsSL $PUBLIC_URL -o /tmp/p.tar.gz && tar xzf /tmp/p.tar.gz -C /tmp && bash /tmp/latentsync-node/apply.sh"
