#!/usr/bin/env bash
# Weaveora API 部署：本地 mvn package(含测试) → 上传 /opt/weaveora/api/weaveora-api.jar
#                 → 备份旧包 → systemctl restart weaveora-api → 健康检查
#
# 前置：JDK21（默认 /d/jdk21/jdk-21.0.2，可用 JDK21= 覆盖）
# 安全闸：有 running 任务时拒绝重启（避免打断在跑的生成任务）；WEAVEORA_API_FORCE=1 可强推
#
# 用法：bash deploy/api-deploy.sh
# 可覆盖：WEAVEORA_API_HOST / WEAVEORA_SSH_KEY / WEAVEORA_API_FORCE / WEAVEORA_API_SKIP_TESTS / JDK21
set -euo pipefail

HOST="${WEAVEORA_API_HOST:-root@sysou.com}"
KEY="${WEAVEORA_SSH_KEY:-$HOME/.ssh/comfy_tunnel_ed25519}"
SVC="weaveora-api"
REMOTE_DIR="/opt/weaveora/api"
REMOTE_JAR="$REMOTE_DIR/weaveora-api.jar"

cd "$(dirname "$0")/.."

# ---- JDK21（本机环境里可能残留 JAVA_HOME=jdk-17，必须覆盖，否则 surefire 用 17 跑不了 21 的 class）----
JDK21="${JDK21:-/d/jdk21/jdk-21.0.2}"
if [ -x "$JDK21/bin/javac" ]; then
  [ -n "${JAVA_HOME:-}" ] && echo "   注意：环境里的 JAVA_HOME=$JAVA_HOME 会被覆盖为 JDK21"
  export JAVA_HOME="$(cd "$JDK21" && pwd -W 2>/dev/null || echo "$JDK21")"
  export PATH="$JDK21/bin:$PATH"
else
  echo "!! 找不到 JDK21（用 JDK21=... 指定）；当前 JAVA_HOME=${JAVA_HOME:-<未设>}"
  exit 1
fi
JMAJOR="$("$JDK21/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
if [ "${JMAJOR:-0}" -lt 21 ]; then
  echo "!! 需要 JDK21+，实际拿到 $JMAJOR（JAVA_HOME=$JAVA_HOME）"; exit 1
fi
echo "   JAVA_HOME=$JAVA_HOME (java $JMAJOR)"

SSH=(ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=15 "$HOST")

echo "== 0/5 预检：是否有正在跑的生成任务 =="
REMOTE_SQL="select state, count(*) from generation_jobs where state in ('queued','running') group by state;"
STATUS="$("${SSH[@]}" "sudo -u postgres psql -d weaveora -tA -c \"$REMOTE_SQL\"" || true)"
echo "$STATUS" | sed 's/^/   /' | grep . || echo "   （无 queued/running）"
RUNNING="$(printf '%s\n' "$STATUS" | awk -F'|' '$1=="running"{print $2}' | tr -d ' ')"
if [ -n "$RUNNING" ] && [ "${RUNNING:-0}" != "0" ] && [ "${WEAVEORA_API_FORCE:-0}" != "1" ]; then
  echo "!! 有 $RUNNING 个 running 任务，重启会打断它们。等它跑完，或用 WEAVEORA_API_FORCE=1 强推。"
  exit 1
fi

echo "== 1/5 构建（含测试）=="
MVN=(mvn -o -q package)
[ "${WEAVEORA_API_SKIP_TESTS:-0}" = "1" ] && MVN=(mvn -o -q package -DskipTests)
(cd api && "${MVN[@]}")
JAR="api/target/weaveora-api-0.1.0-SNAPSHOT.jar"
test -f "$JAR" || { echo "!! 没找到 $JAR"; exit 1; }
LOCAL_SIZE=$(wc -c < "$JAR" | tr -d ' ')
echo "   $JAR  ${LOCAL_SIZE} bytes"

echo "== 2/5 上传 =="
scp -i "$KEY" -q "$JAR" "$HOST:$REMOTE_JAR.new"

TS=$(date +%Y%m%d-%H%M%S)
echo "== 3/5 备份 + 替换 + 重启（ts=$TS）=="
"${SSH[@]}" "set -e
  cd '$REMOTE_DIR'
  [ -x weaveora-api.jar ] && cp -f weaveora-api.jar 'weaveora-api.jar.bak.$TS'
  mv -f weaveora-api.jar.new weaveora-api.jar
  # 只留最近 5 份旧包
  ls -dt weaveora-api.jar.bak.* 2>/dev/null | tail -n +6 | xargs -r rm -f
  systemctl restart $SVC
  echo '   restart 已发出'"

echo "== 4/5 健康检查（/actuator/health，最多 60s）=="
OK=0
for _ in $(seq 1 30); do
  H="$("${SSH[@]}" "curl -s -m 5 http://127.0.0.1:8080/actuator/health" || true)"
  case "$H" in
    *'"status":"UP"'*) echo "   健康：$H"; OK=1; break;;
  esac
  sleep 2
done
if [ "$OK" != "1" ]; then
  echo "!! 60s 内未 UP，最近日志："
  "${SSH[@]}" "journalctl -u $SVC -n 40 --no-pager" || true
  echo "== 回滚：ssh $HOST 'cd $REMOTE_DIR && mv weaveora-api.jar.bak.$TS weaveora-api.jar && systemctl restart $SVC' =="
  exit 1
fi

echo "== 5/5 校验线上包 =="
REMOTE_SIZE="$("${SSH[@]}" "wc -c < $REMOTE_JAR" | tr -d ' ')"
echo "   远端 $REMOTE_SIZE bytes / 本地 $LOCAL_SIZE bytes"
[ "$REMOTE_SIZE" = "$LOCAL_SIZE" ] || echo "   !! 大小不一致，注意"
"${SSH[@]}" "systemctl is-active $SVC | sed 's/^/   service: /'; ls -la $REMOTE_JAR | sed 's/^/   /'"
echo "== 完成。回滚：ssh $HOST 'cd $REMOTE_DIR && mv weaveora-api.jar.bak.$TS weaveora-api.jar && systemctl restart $SVC' =="
