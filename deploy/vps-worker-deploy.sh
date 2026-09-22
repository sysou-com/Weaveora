#!/usr/bin/env bash
# Weaveora VPS worker 部署（云出图 / 中转 worker：stub_worker.py + cloud_* + comfy_client + audio_client）
#
# 为什么单独有这个脚本（2026-09-13 线上事故）：
#   上一次部署把 cloud_client.py 上传成了 cloud_client.py.new 却**没执行改名**，
#   于是 /opt/weaveora/ 里根本没有 cloud_client.py → 云 worker 每领到任务就
#   `ModuleNotFoundError: No module named 'cloud_client'` → 崩溃退出 → systemd 每 6s
#   重启一次（restart counter 到了 25），任务永远停在 running（用户看到「转圈」）。
#
# 本脚本的防呆：
#   ① 一律先传成 <file>.new，**在目标机用目标机的 python 做语法校验**（VPS 是 py3.6，
#      本地可能更新，只有目标机能证明能否跑）；
#   ② 全部通过才一起原子改名（要么全换、要么不动）——不存在“传了一半”的中间态；
#   ③ 改名前置备份 <file>.bak.<ts>，任一步失败自动回滚；
#   ④ 重启后做 is-active + 日志尾部检查。
#
# 用法：bash deploy/vps-worker-deploy.sh
# 可覆盖：WEAVEORA_WORKER_HOST / WEAVEORA_SSH_KEY / WEAVEORA_WORKER_DIR
set -euo pipefail

HOST="${WEAVEORA_WORKER_HOST:-root@sysou.com}"
# SSH key：优先显式覆盖 $WEAVEORA_SSH_KEY；默认按「存在即用」顺序探测（2026-09-16：
# 原来的 comfy_tunnel_ed25519 在本机已不存在，导致三个部署脚本一跑就 Permission denied）。
KEY="${WEAVEORA_SSH_KEY:-}"
if [ -z "$KEY" ]; then
  for cand in "$HOME/.ssh/comfy_tunnel_ed25519" "$HOME/.ssh/id_ed25519" "$HOME/.ssh/id_rsa"; do
    [ -f "$cand" ] && KEY="$cand" && break
  done
fi
[ -f "${KEY:-}" ] || { echo "!! 找不到可用的 SSH 私钥（用 WEAVEORA_SSH_KEY=<路径> 指定）"; exit 1; }
DIR="${WEAVEORA_WORKER_DIR:-/opt/weaveora}"
SVC="weaveora-cloud-worker"
FILES=(stub_worker.py cloud_client.py cloud_image.py comfy_client.py audio_client.py)
# 对口型工作流 JSON：worker 在**本机**读它，再把图 POST 给远端 ComfyUI（GPU 服务器）。
# 必须跟着代码一起发，否则 fps/节点接线不一致会出各种怪问题。
WF_SRC="$(cd "$(dirname "$0")/windows" && pwd)/lipsync_workflow_api.json"
WF_DST="$DIR/lipsync_workflow_api.json"

cd "$(dirname "$0")/../worker"

# ★ 2026-09-22 修正：SSH 数组必须**在安全闸之前**定义 —— 原来它写在下面第 53 行，
#   预检里的 `${SSH[@]}` 展开为空 → 整条 psql 命令被当成本地命令 → 预检**静默空转**
#   （“有 running 就拦住”完全没生效，只会在日志里留一行 command not found）。
SSH=(ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=15 "$HOST")

# ---- 安全闸（2026-09-16 补）：worker 是**单线程认领**，重启正在跑的任务会把它打断 ----
# 与 deploy/gpu2_restart.sh 同口径：有 running 就拦住，除非 WEAVEORA_WORKER_FORCE=1。
echo "== 0/5 预检：是否有正在跑的生成任务 =="
STATUS="$("${SSH[@]}" "sudo -u postgres psql -d weaveora -tA -c \"select state, count(*) from generation_jobs where state in ('queued','running') group by state;\"" || true)"
printf '%s
' "$STATUS" | sed 's/^/   /' | grep . || echo "   （无 queued/running）"
RUNNING="$(printf '%s
' "$STATUS" | awk -F'|' '$1=="running"{print $2}' | tr -d ' ')"
if [ -n "${RUNNING:-}" ] && [ "${RUNNING:-0}" != "0" ] && [ "${WEAVEORA_WORKER_FORCE:-0}" != "1" ]; then
  echo "!! 有 $RUNNING 个 running 任务，重启 worker 会打断它们。等它跑完，或用 WEAVEORA_WORKER_FORCE=1 强推。"
  exit 1
fi
TS="$(date +%Y%m%d-%H%M%S)"

echo "== 1/5 上传（.new）=="
for f in "${FILES[@]}"; do
  test -f "$f" || { echo "!! 仓库里缺 $f"; exit 1; }
  scp -i "$KEY" -q "$f" "$HOST:$DIR/$f.new"
  echo "   $f  $(wc -c < "$f") bytes"
done

echo "== 2/5 目标机语法校验（用它自己的 python）=="
if ! "${SSH[@]}" "set -e; cd '$DIR'; for f in ${FILES[*]}; do /usr/bin/python3 -m py_compile \$f.new && echo \"   OK \$f\" || { echo \"!! \$f 语法不通过\"; exit 1; }; done"; then
  echo "!! 有文件校验失败 —— 已保持线上原样（只留下 .new 供排查）"
  exit 1
fi

echo "== 3/5 备份 + 原子改名 =="
"${SSH[@]}" "set -e; cd '$DIR'
  for f in ${FILES[*]}; do [ -f \"\$f\" ] && cp -f \"\$f\" \"\$f.bak.$TS\"; done
  for f in ${FILES[*]}; do mv -f \"\$f.new\" \"\$f\"; done
  echo '   已替换'; ls -la stub_worker.py cloud_client.py | sed 's/^/   /'"

echo "== 4/5 重启 worker 服务 =="
if [ -f "$WF_SRC" ]; then
  scp -i "$KEY" -q "$WF_SRC" "$HOST:$WF_DST.new"
  # ★ 2026-09-22（用户排查「口型乱码」时发现的缺口）：这个工作流 JSON 以前是**直接覆盖、无备份**——
  #   只有 5 个 .py 进 FILES 数组、才有 .bak。结果 09-22 10:01 那次 worker 部署把线上
  #   lipsync_workflow_api.json 覆盖掉后，**没有任何恢复路径**，事后无法证明改了什么。
  #   现在与 .py 同口径：先 cp 出 <file>.bak.<ts>，再原子改名。
  echo "   工作流 JSON: $("${SSH[@]}" "[ -f $WF_DST ] && cp -f $WF_DST $WF_DST.bak.$TS; mv -f $WF_DST.new $WF_DST && echo \"已同步（备份 $WF_DST.bak.$TS）\"" || true)"
fi
for svc in "$SVC" "weaveora-gpu-worker"; do
  if "${SSH[@]}" "systemctl list-unit-files 2>/dev/null | grep -q '^$svc'"; then
    "${SSH[@]}" "systemctl restart $svc; sleep 4; echo \"   $svc -> \$(systemctl is-active $svc)\""
  fi
done

echo "== 5/5 自检（导入 + 日志）=="
# 注意：stub_worker 模块级有「MODE 必须是 comfy/cloud」的启动守卫（防 stub 模式抢 GPU 任务），
# 所以导入自检必须带上 WEAVEORA_WORKER_MODE，否则会被误判为导入失败（本脚本第一版就踩了）。
OK="$("${SSH[@]}" "cd '$DIR' && WEAVEORA_WORKER_MODE=cloud /usr/bin/python3 -c \"import sys; sys.path.insert(0,'$DIR'); import stub_worker, cloud_client, cloud_image, comfy_client, audio_client; print('IMPORTS_OK')\"" || true)"
echo "   $OK"
if [ "$OK" != "IMPORTS_OK" ]; then
  echo "!! 导入失败，回滚："
  "${SSH[@]}" "cd '$DIR'; for f in ${FILES[*]}; do [ -f \"\$f.bak.$TS\" ] && mv -f \"\$f.bak.$TS\" \"\$f\"; done; systemctl restart $SVC"
  echo "   已回滚到上一版"
  exit 1
fi
"${SSH[@]}" "journalctl -u $SVC -n 6 --no-pager | tail -5 | sed 's/^/   /'"
echo
echo "== 完成。回滚：ssh $HOST 'cd $DIR && for f in ${FILES[*]}; do [ -f \$f.bak.$TS ] && mv -f \$f.bak.$TS \$f; done && systemctl restart $SVC' =="
echo "   工作流回滚：ssh $HOST 'cd $DIR && [ -f lipsync_workflow_api.json.bak.$TS ] && mv -f lipsync_workflow_api.json.bak.$TS lipsync_workflow_api.json && systemctl restart $SVC'"
