#!/bin/bash
# 修 services_up.sh 的网关端口硬编码 bug：--port 8000 → --port "$GWPORT"
# （原脚本 start_bg 那行硬编码 8000，导致 WEAVEORA_GATEWAY_PORT 只改了健康检查、没改实际监听）
set -uo pipefail
P=/opt/weaveora/services_up.sh
[ -f "$P.bak" ] || cp "$P" "$P.bak"

python3 - "$P" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
subs = [
    ('start_bg "EdgeProxy(:8000)"', 'start_bg "EdgeProxy(:$GWPORT)"'),
    ('"$ROOT/edge_proxy.py" --port 8000', '"$ROOT/edge_proxy.py" --port "$GWPORT"'),
]
n = 0
for old, new in subs:
    if old in s:
        s = s.replace(old, new, 1)
        n += 1
if n:
    open(p, "w", encoding="utf-8").write(s)
    print("  ✅ 已应用 %d 处替换" % n)
else:
    print("  （无需替换 / 已修过）")
PYEOF

echo "--- 修后网关段 ---"
grep -n -A3 'GWPORT=\${WEAVEORA_GATEWAY_PORT' "$P" | head -6
grep -n 'edge_proxy.py" --port' "$P"
bash -n "$P" && echo "  ✅ 语法检查通过"
