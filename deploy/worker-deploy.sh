#!/usr/bin/env bash
# Weaveora GPU Worker 部署（2026-09-19 新增）
#
# 背景：worker 实际跑在 **VPS** 上（systemd unit `weaveora-gpu-worker`，ExecStart=stub_worker.py，
# 由它 import /opt/weaveora/comfy_client.py），不是 GPU 盒子上 —— 盒子上只有
# ComfyUI/TTS/face/talk/edge_proxy（见 deploy/gpu2_restart.sh）。把 comfy_client.py scp 到盒子上是白干。
#
# 护栏（学 api-deploy.sh 的教训）：
#   · 有 queued/running 生成任务 → 直接拒停（worker 一停，正在跑的任务会变成僵尸 running）
#   · 部署前把线上文件与仓库文件 diff 打印出来（防止用旧仓库副本覆盖线上独有的改动）
#   · 备份带时间戳，回滚一条命令
#
# 用法：
#   bash deploy/worker-deploy.sh              # 部署 comfy_client.py + stub_worker.py
#   FORCE=1 bash deploy/worker-deploy.sh      # 有任务在跑也强推（不建议）
set -uo pipefail

HOST="${HOST:-sysou}"
TS="$(date +%Y%m%d-%H%M%S)"
DST_DIR=/opt/weaveora
SERVICES=(weaveora-gpu-worker)

say() { printf '%s\n' "$*"; }

# ---------- 0/5 预检 ----------
say "== 0/5 预检：是否有正在跑的生成任务 =="
QL="select state||' '||count(*) from generation_jobs where state in ('queued','running') group by state"
BUSY="$(ssh -o BatchMode=yes "$HOST" "sudo -u postgres psql -d weaveora -At -c \"$QL\"" 2>/dev/null | tr -d '\r')"
if [ -z "$BUSY" ]; then
  say "   队列为空 ✓"
elif [ -n "${FORCE:-}" ]; then
  say "   ⚠ 仍有任务在跑（$BUSY），FORCE=1 → 强推"
else
  say "   ✗ 仍有任务在跑：$BUSY"
  say "   worker 停掉会让这些任务卡在 running（僵尸）。等它们结束再跑，或 FORCE=1 强推。"
  exit 1
fi

# ---------- 1/5 差异预览 ----------
say "== 1/5 差异预览（线上 → 仓库）=="
ssh -o BatchMode=yes "$HOST" "cat $DST_DIR/comfy_client.py" > /tmp/wv_prod_comfy.py 2>/dev/null || true
if [ -s /tmp/wv_prod_comfy.py ]; then
  diff /tmp/wv_prod_comfy.py worker/comfy_client.py | head -40 || true
  say "   （上面是即将生效的全部改动；确认没有“线上独有”的改动被覆盖）"
fi

# ---------- 2/5 上传 ----------
say "== 2/5 上传 =="
ssh -o BatchMode=yes "$HOST" "mkdir -p $DST_DIR"
scp -q -o BatchMode=yes worker/comfy_client.py "$HOST:/tmp/wv_comfy_new.py"
scp -q -o BatchMode=yes worker/stub_worker.py "$HOST:/tmp/wv_stub_new.py"
ssh -o BatchMode=yes "$HOST" "python3 -m py_compile /tmp/wv_comfy_new.py /tmp/wv_stub_new.py" \
  && say "   语法检查通过 ✓" || { say "   ✗ 语法检查失败，中止"; exit 1; }

# ---------- 3/5 备份 + 替换 ----------
say "== 3/5 备份 + 替换（ts=$TS）=="
ssh -o BatchMode=yes "$HOST" bash -s <<EOF
set -e
cd $DST_DIR
cp -f comfy_client.py comfy_client.py.bak.$TS
cp -f stub_worker.py  stub_worker.py.bak.$TS 2>/dev/null || true
mv -f /tmp/wv_comfy_new.py comfy_client.py
[ -s /tmp/wv_stub_new.py ] && mv -f /tmp/wv_stub_new.py stub_worker.py || true
ls -la comfy_client.py | awk '{print "   comfy_client.py " \$5 " bytes"}'
EOF

# ---------- 4/5 重启 ----------
say "== 4/5 重启 ${SERVICES[*]} =="
ssh -o BatchMode=yes "$HOST" "systemctl restart ${SERVICES[*]}; sleep 3; systemctl is-active ${SERVICES[*]}"

# ---------- 5/5 验证 ----------
say "== 5/5 验证（启动日志里的图片配置行）=="
ssh -o BatchMode=yes "$HOST" "journalctl -u ${SERVICES[0]} --since '-60s' --no-pager | grep -E '文生图配置|服务地址|LoRA' | tail -5" || true
say ""
say "== 完成。回滚：ssh $HOST 'cd $DST_DIR && mv comfy_client.py.bak.$TS comfy_client.py && systemctl restart ${SERVICES[*]}' =="
