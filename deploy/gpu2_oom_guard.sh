#!/bin/bash
# =============================================================================
# Weaveora · GPU 盒「预 OOM 守护」（2026-09-24）
#
# 为什么需要（实测事故，不是推测）：
#   2026-09-24 10:52:56 worker 在出片前调 `POST /free` → 把 FLUX.2 的 33 GB 权重从显存**搬进内存**
#   → 10:54:48 内核 OOM killer 动手：`Killed process (python) anon-rss 47,820,620 kB`，
#   `task_memcg=/system.slice/weaveora-stack.service` → **整个 cgroup（ComfyUI + TTS + face + talk + 网关）
#   一起死**，worker 只看到 Connection refused，之后所有任务全失败。
#   根因（/free）已在 worker 侧修掉；本守护是**兜底**：万一还有别的路径把内存顶上去，
#   也要让"死"的范围限制在 ComfyUI 一个进程，而不是整栈。
#
# 阈值怎么定（用户问：44 GB 是不是太容易触发？47 GB 行不行？）—— 用实测数字说话：
#   · 盒子总内存 48,168 MB（≈47 GiB 可用），**内核实测在 anon-rss 47.8 GB 时开杀**。
#   · 正常业务峰值（实测）：空载 anon ≈ 1 GB；出图（FLUX.2 家族）worker 日志报"可用内存 ~25 GB"
#     ⇒ anon ≈ 22 GB 上下。也就是说 44 GB 距正常峰值有 20 GB 余量，不会误伤。
#   · 47 GB **不行**：只剩 ~0.8 GB 余量，与本守护 5s 轮询 + 内核回收延迟同量级 ⇒ 还没轮到我们动手
#     就已经 OOM；而且到了 47 GB 页缓存已被榨干，连 ssh/写日志都可能分配不到内存（今天现场就是这样）。
#   ⇒ 两档：**44 GB 只报警不动作**（记录下来，用于以后调参）；**46 GB 才动手**（留 ~1.8 GB 余量）。
#
# 动谁的手：只 `pkill -f '[C]omfyUI/main.py'`（内存大头就是它），TTS/face/talk/网关全部保住；
#   ComfyUI 随后由 worker 侧的跨家族守卫自愈（它发现 ComfyUI 不可达会走 `POST /__edge/reload_comfy`）。
#
# 用法：systemd 单元 weaveora-oom-guard.service（Restart=always），或手工
#   setsid nohup bash /opt/weaveora/oom_guard.sh </dev/null >>/opt/weaveora/logs/oom_guard.log 2>&1 &
# 调参：WEAVEORA_OOM_GUARD_WARN_MB（默认 44000）/ WEAVEORA_OOM_GUARD_KILL_MB（默认 46000）/
#       WEAVEORA_OOM_GUARD_INTERVAL（默认 5 秒）
# =============================================================================
set -u
WARN_MB="${WEAVEORA_OOM_GUARD_WARN_MB:-44000}"
KILL_MB="${WEAVEORA_OOM_GUARD_KILL_MB:-46000}"
INTERVAL="${WEAVEORA_OOM_GUARD_INTERVAL:-5}"
LOG="/opt/weaveora/logs/oom_guard.log"
mkdir -p "$(dirname "$LOG")"

anon_mb() { awk '/^AnonPages/{print int($2/1024)}' /proc/meminfo; }
avail_mb() { awk '/^MemAvailable/{print int($2/1024)}' /proc/meminfo; }

echo "[$(date '+%F %T')] oom_guard 启动：warn=${WARN_MB}MB kill=${KILL_MB}MB interval=${INTERVAL}s（pid $$）" >> "$LOG"
LAST_WARN=0
while true; do
  A=$(anon_mb); AV=$(avail_mb)
  if [ "${A:-0}" -ge "$KILL_MB" ]; then
    echo "[$(date '+%F %T')] ⛔ anon=${A}MB >= ${KILL_MB}MB（avail=${AV}MB）→ 主动杀 ComfyUI 以保住整栈" >> "$LOG"
    pkill -f '[C]omfyUI/main.py' || true
    sleep 30          # 给它时间真的退出，别连杀
    LAST_WARN=0
  elif [ "${A:-0}" -ge "$WARN_MB" ]; then
    now=$(date +%s)
    if [ $((now - LAST_WARN)) -ge 60 ]; then   # 每分钟最多记一条，别刷日志
      echo "[$(date '+%F %T')] ⚠️ anon=${A}MB >= ${WARN_MB}MB（avail=${AV}MB）—— 仅告警，未动手" >> "$LOG"
      LAST_WARN=$now
    fi
  fi
  sleep "$INTERVAL"
done
