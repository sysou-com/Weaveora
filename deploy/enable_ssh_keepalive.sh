#!/usr/bin/env bash
# 开启 SSH 保活，回收「睡眠/断网后残留的幽灵会话」。
#
# 为什么需要：本机（GPU 盒）睡眠后，服务器侧的 sshd 会话进入 ESTAB-但已死状态，
# 而 clientaliveinterval=0 时服务器**永不回收**它 → 旧会话一直占着反向转发端口
# （18188/18091）→ 客户端重连时 "remote port forwarding failed" + ExitOnForwardFailure
# → rc=255 无限重连 → 配音/转写全挂（实测幽灵会话挂了 12 小时）。
#
# 安全约束（上次就是因为没校验就 reload 把 sshd 弄挂了）：
#   1) 先备份
#   2) 追加用 `Match all` 块（文件末尾若已有 Match 块，直接追加非法项会报错）
#   3) sshd -t 校验通过才 reload；不通过立即回滚且不 reload
#   4) reload 后再确认服务活着，不活则回滚并重启
set -uo pipefail

CFG=/etc/ssh/sshd_config
BAK="${CFG}.bak.$(date +%Y%m%d-%H%M%S)"

echo "== 现状 =="
sshd -T 2>/dev/null | grep -iE 'clientalive' || true
systemctl is-active sshd

cp -a "$CFG" "$BAK"
echo "备份: $BAK"

# 注意：默认配置里有一行**被注释**的 `#ClientAliveInterval 0`，
# 所以必须匹配“行首（可为空白）的非注释项”，否则会误判为已存在。
if grep -qE '^[[:space:]]*ClientAliveInterval' "$CFG"; then
  echo "已存在生效的 ClientAliveInterval 配置，跳过追加"
else
  cat >> "$CFG" <<'EOF'

# Weaveora: reap dead SSH sessions so stale reverse-forwards are released.
# Dead-but-established sessions otherwise hold ports 18188/18091 forever
# (clientaliveinterval=0 means the server never probes idle clients).
Match all
    ClientAliveInterval 30
    ClientAliveCountMax 3
EOF
  echo "已追加 Match all 块"
fi

echo "== 校验 =="
if ! sshd -t; then
  echo "!! 校验不通过 → 回滚，且**不** reload"
  cp -a "$BAK" "$CFG"
  sshd -t && echo "回滚后配置合法"
  exit 1
fi
echo "校验通过"

echo "== reload =="
systemctl reload sshd
sleep 3

if systemctl is-active --quiet sshd; then
  echo "== 完成 =="
  sshd -T 2>/dev/null | grep -iE 'clientalive'
  ss -ltn | grep -E ':22\b' || true
else
  echo "!! reload 后 sshd 不活 → 回滚并重启"
  cp -a "$BAK" "$CFG"
  systemctl start sshd
  systemctl is-active sshd
  exit 1
fi
