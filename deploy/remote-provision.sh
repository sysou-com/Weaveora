#!/usr/bin/env bash
# Weaveora VPS 初始化（一次性）：建 PG 角色/库 + 生成 /etc/weaveora/weaveora-api.env（0600）。
# 密钥来自：本脚本生成(DB/JWT)、/etc/redis/redis.conf(requirepass)、/opt/mirrortalk/application-prod.yml(DeepSeek key)。
# 任何密钥不回显到 stdout。
set -euo pipefail

echo "[1/4] dirs"
mkdir -p /etc/weaveora /opt/weaveora/api /opt/weaveora/web /opt/weaveora/data/storage

echo "[2/4] postgres role+db"
DBPASS=$(openssl rand -hex 16)
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='weaveora'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE ROLE weaveora LOGIN PASSWORD '${DBPASS}'"
# 幂等：已有角色也统一重置密码，保证与本次写入 env 一致
sudo -u postgres psql -q -c "ALTER ROLE weaveora WITH LOGIN PASSWORD '${DBPASS}'"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='weaveora'" | grep -q 1 \
  || sudo -u postgres psql -q -c "CREATE DATABASE weaveora OWNER weaveora"

echo "[3/4] gather secrets (no echo)"
REDISPASS=$(grep -E '^\s*requirepass' /etc/redis/redis.conf | awk '{print $2}' | head -1)
[ -n "${REDISPASS}" ] || { echo "FAIL redis requirepass empty"; exit 1; }
LLMKEY=$(python3 - <<'PY'
import yaml, os
base = '/opt/mirrortalk/application.yml'
if not os.path.exists(base):
    base = '/opt/mirrortalk/application-prod.yml'
d = yaml.safe_load(open(base))
print((d.get('app') or {}).get('deepseek') or {} .get('api-key', ''))
PY
)
[ -n "${LLMKEY}" ] || { echo "FAIL deepseek key empty"; exit 1; }
JWTSEC=$(openssl rand -hex 32)

echo "[4/4] write env file"
umask 077
cat > /etc/weaveora/weaveora-api.env <<EOF
WEAVEORA_DB_USER=weaveora
WEAVEORA_DB_PASSWORD=${DBPASS}
WEAVEORA_REDIS_PASSWORD=${REDISPASS}
WEAVEORA_JWT_SECRET=${JWTSEC}
WEAVEORA_LLM_BASE_URL=https://api.deepseek.com
WEAVEORA_LLM_API_KEY=${LLMKEY}
WEAVEORA_LLM_MODEL=deepseek-v4-flash
EOF
chown root:root /etc/weaveora/weaveora-api.env
chmod 600 /etc/weaveora/weaveora-api.env

# 联通性自检（不打印密钥）
PGPASSWORD="${DBPASS}" psql -h 127.0.0.1 -U weaveora -d weaveora -tAc "SELECT 'pg-ok'" >/dev/null \
  || { echo "FAIL pg connect"; exit 1; }
echo "DONE"
