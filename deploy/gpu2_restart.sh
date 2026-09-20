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
#   GW=http://1.2.3.4:5678 bash deploy/gpu2_restart.sh   # 手动覆盖网关地址（默认从 DB 读）
# 退出码：0 成功 / 1 有在跑任务被拦下 / 2 重启后自检失败
#
# ★ 网关地址一律从 DB（user_engine_settings）读，不在脚本里写死 —— 平台每次重开容器
#   HTTP 端口都会变（实测 10558→21270→12476→15276），写死会在换端口后静默失效（铁律①）。
# =============================================================================
set -u
GPU_HOST="${GPU_HOST:-weaveora-gpu-a14b}"
SVC="${SVC:-weaveora-stack.service}"
API_HOST="${API_HOST:-sysou}"   # ★ 必须用 ssh 别名：别名（Host sysou）里才绑了 IdentityFile，写成 root@sysou.com 会绕过 config → Permission denied → 查询静默返回空 → 护栏被当成“空闲”
DRY="${DRY:-0}"
FORCE="${FORCE:-0}"

echo "== 1/3 检查是否有 queued/running 任务（在 API 机上查库）=="
BUSY_RAW=$("$(command -v ssh)" -o ConnectTimeout=15 -o BatchMode=yes "$API_HOST" '
  set -a; . /etc/weaveora/weaveora-api.env; set +a; export PGPASSWORD="$WEAVEORA_DB_PASSWORD"
  psql -h 127.0.0.1 -U weaveora -d weaveora -tA -F"|" -c \
   "select kind, state, coalesce(payload->>'"'"'shot_no'"'"','"'"'-'"'"') from generation_jobs where state in ('"'"'queued'"'"','"'"'running'"'"') order by created_at;"' 2>&1)
RC=$?
# ★ 关键：“查不到” ≠ “空闲”。查询失败一律拦下（重启撞车是最大生产风险，坑 42）
if [ "$RC" != "0" ]; then
  echo "  !! 无法在 API 机查库（ssh/psql 退出码 $RC）—— 查不到就不敢重启："
  echo "$BUSY_RAW" | sed 's/^/     /' | head -5
  if [ "$FORCE" != "1" ]; then
    echo "  确认确实空闲请用 FORCE=1。"
    exit 1
  fi
  echo "  FORCE=1 → 仍继续重启（跳过空闲判据）"
else
  BUSY="$BUSY_RAW"
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
fi

if [ "$DRY" = "1" ]; then echo "DRY=1 → 不重启"; exit 0; fi

echo "== 2/3 重启 $SVC =="
ssh -o ConnectTimeout=20 -o BatchMode=yes "$GPU_HOST" "systemctl restart $SVC && sleep 45 && systemctl is-active $SVC"

echo "== 3/3 自检（端口 + ComfyUI 就绪）=="
# ★ 网关地址从 DB 读（不写死端口）：user_engine_settings 的 gpu_server_url + gpu_server_port 是唯一权威入口
GW="${GW:-}"
if [ -z "$GW" ]; then
  GW_RAW=$("$(command -v ssh)" -o ConnectTimeout=15 -o BatchMode=yes "$API_HOST" '
    set -a; . /etc/weaveora/weaveora-api.env; set +a; export PGPASSWORD="$WEAVEORA_DB_PASSWORD"
    psql -h 127.0.0.1 -U weaveora -d weaveora -tA -F"|" -c \
     "select gpu_server_url, gpu_server_port from user_engine_settings where gpu_server_url is not null and gpu_server_port is not null order by updated_at desc limit 1;"' 2>&1 | tr -d "\r" | head -1)
  if [ -n "$GW_RAW" ]; then
    GW="${GW_RAW%%|*}"; GW="${GW%/}:${GW_RAW##*|}"
    echo "  网关地址（DB）: $GW"
  else
    echo "  ! 未从 DB 读到网关地址（gpu_server_url/port 为空？）→ 跳过网关自检"
  fi
fi
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
  if [ -n "$GW" ]; then
    echo "  ComfyUI 就绪 ✓（网关 $GW/system_stats: $(curl -s -m 8 -o /dev/null -w '%{http_code}' "$GW/system_stats" 2>/dev/null)）"
  else
    echo "  ComfyUI 就绪 ✓（无网关地址，未做网关自检；请在平台「生成引擎配置」确认端口）"
  fi
  exit 0
fi
echo "  !! 重启后 ComfyUI 未就绪，请查 journalctl -u $SVC"; exit 2
