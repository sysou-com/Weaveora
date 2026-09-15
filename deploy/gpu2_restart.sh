#!/usr/bin/env bash
# =============================================================================
# GPU 机「安全重启」唯一入口 —— 重启前先确认没有在跑/排队的生成任务。
#
# 为什么必须有（2026-09-15 实际事故）：为部署 talk 服务的 /health 改动，直接
# `systemctl restart weaveora-stack.service`，把用户 21:49:02 正在跑的对口型任务撞死：
#   `对口型推理失败：comfy GET /history/... -> 502 edge proxy: upstream error:
#      Cannot connect to host 127.0.0.1:8001`（ComfyUI 那一刻正在重启）。
# worker 是单线程认领（任务在 GPU 上不会并发），所以**"重启撞车"才是最大的生产风险**。
#
# 用法：
#   bash deploy/gpu2_restart.sh            # 检查空闲 → 重启 weaveora-stack.service → 自检端口
#   SVC=weaveora-api bash deploy/gpu2_restart.sh
#   DRY=1 bash deploy/gpu2_restart.sh      # 只检查不重启
#   FORCE=1 bash deploy/gpu2_restart.sh    # 明知有任务也要重启（谨慎）
# 退出码：0 成功 / 1 有在跑任务被拦下 / 2 重启后自检失败
# =============================================================================
set -u
GPU_HOST="${GPU_HOST:-weaveora-gpu-a14b}"
SVC="${SVC:-weaveora-stack.service}"
API_HOST="${API_HOST:-root@sysou.com}"
DRY="${DRY:-0}"
FORCE="${FORCE:-0}"

echo "== 1/3 检查是否有 queued/running 任务（在 API 机上查库）=="
BUSY=$("$(command -v ssh)" -o ConnectTimeout=15 -o BatchMode=yes "$API_HOST" '
  set -a; . /etc/weaveora/weaveora-api.env; set +a; export PGPASSWORD="$WEAVEORA_DB_PASSWORD"
  psql -h 127.0.0.1 -U weaveora -d weaveora -tA -F"|" -c \
   "select kind, state, coalesce(payload->>'"'"'shot_no'"'"','"'"'-'"'"') from generation_jobs where state in ('"'"'queued'"'"','"'"'running'"'"') order by created_at;"' 2>/dev/null)
if [ -n "$BUSY" ]; then
  echo "  !! 有任务在跑/排队："
  echo "$BUSY" | sed 's/^/     /'
  if [ "$FORCE" != "1" ]; then
    echo "  已拦下。等它跑完再重启（或 FORCE=1 强制）。"
    exit 1
  fi
  echo "  FORCE=1 → 仍继续重启"
else
  echo "  空闲 ✓"
fi

if [ "$DRY" = "1" ]; then echo "DRY=1 → 不重启"; exit 0; fi

echo "== 2/3 重启 $SVC =="
ssh -o ConnectTimeout=20 -o BatchMode=yes "$GPU_HOST" "systemctl restart $SVC && sleep 45 && systemctl is-active $SVC"

echo "== 3/3 自检（端口 + ComfyUI 就绪）=="
GW="${GW:-http://180.127.11.167:21264}"
OK=0
for i in $(seq 1 12); do
  CODE=$(ssh -o ConnectTimeout=15 -o BatchMode=yes "$GPU_HOST" \
    'curl -s -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8001/system_stats' 2>/dev/null || echo 000)
  [ "$CODE" = "200" ] && { OK=1; break; }
  sleep 10
done
ssh -o ConnectTimeout=15 -o BatchMode=yes "$GPU_HOST" \
  'ss -ltn | grep -E ":(8001|8091|8093|8094|8800) " | awk "{print \$4}" | sort | tr "\n" " "; echo' || true
if [ "$OK" = "1" ]; then
  echo "  ComfyUI 就绪 ✓（网关 $GW/system_stats: $(curl -s -m 8 -o /dev/null -w '%{http_code}' "$GW/system_stats" 2>/dev/null)）"
  exit 0
fi
echo "  !! 重启后 ComfyUI 未就绪，请查 journalctl -u $SVC"; exit 2
