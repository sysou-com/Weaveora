#!/usr/bin/env bash
# Weaveora Web 部署：本地 type-check + build:prod → 上传 /opt/weaveora/web
#   - index.html 先上传为 .new 再原子 mv（避免半截页面）
#   - 每次部署备份 index.html（index.html.bak.<ts>），回滚就是 mv 回来
#   - assets 是内容哈希文件名，只增不覆盖；顺手删超过 KEEP_DAYS 的旧文件
#
# 用法：bash deploy/web-deploy.sh
# 可覆盖：WEAVEORA_WEB_HOST / WEAVEORA_SSH_KEY / WEAVEORA_WEB_DIR / WEAVEORA_WEB_KEEP_DAYS
set -euo pipefail

HOST="${WEAVEORA_WEB_HOST:-root@sysou.com}"
KEY="${WEAVEORA_SSH_KEY:-$HOME/.ssh/comfy_tunnel_ed25519}"
REMOTE="${WEAVEORA_WEB_DIR:-/opt/weaveora/web}"
KEEP_DAYS="${WEAVEORA_WEB_KEEP_DAYS:-14}"

cd "$(dirname "$0")/../web"

echo "== 1/4 type-check =="
npm run type-check

echo "== 2/4 build:prod（base=/weaveora/）=="
npm run build:prod

test -f dist/index.html || { echo "!! dist/index.html 不存在"; exit 1; }
grep -q 'src="/weaveora/assets/' dist/index.html \
  || { echo "!! base 不是 /weaveora/ —— 确认用的是 build:prod 而不是 build"; exit 1; }
NEW_JS=$(grep -o '/weaveora/assets/index-[^"]*\.js' dist/index.html | head -1)
echo "   新入口: $NEW_JS"

SSH=(ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=15 "$HOST")
TS=$(date +%Y%m%d-%H%M%S)

echo "== 3/4 服务器备份（ts=$TS）=="
"${SSH[@]}" "set -e; cd '$REMOTE'; [ -d assets ] || mkdir -p assets
  [ -f index.html ] && cp -f index.html index.html.bak.$TS
  # 只保留最近 2 份 assets 全量备份，避免无限增长
  ls -dt assets.bak.* 2>/dev/null | tail -n +3 | xargs -r rm -rf
  echo '   备份完成'"

echo "== 4/4 上传（assets 合并 + index.html 原子替换）=="
scp -i "$KEY" -q -r dist/assets/. "$HOST:$REMOTE/assets/"
scp -i "$KEY" -q dist/index.html "$HOST:$REMOTE/index.html.new"
"${SSH[@]}" "set -e; cd '$REMOTE'; mv -f index.html.new index.html
  find assets -type f -mtime +$KEEP_DAYS -delete
  echo '   --- 线上状态 ---'
  ls -la index.html | awk '{print \"   index.html\", \$5, \$6, \$7, \$8}'
  echo \"   assets 文件数: \$(ls assets | wc -l)  占用: \$(du -sh assets | cut -f1)\""

echo "== 验证 =="
curl -sS -o /dev/null -w "   https://sysou.com/weaveora/                 -> %{http_code}\n" "https://sysou.com/weaveora/"
curl -sS -o /dev/null -w "   $NEW_JS -> %{http_code}\n" "https://sysou.com$NEW_JS"
echo "== 完成。回滚：ssh $HOST 'cd $REMOTE && mv index.html.bak.$TS index.html' =="
