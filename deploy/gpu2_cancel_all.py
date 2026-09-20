# -*- coding: utf-8 -*-
"""一键停：杀掉本地编排 + 取消所有 queued/running 任务 + 中断 ComfyUI 队列。"""
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, '/tmp')
import wv  # noqa: E402

# ① 杀编排脚本
out = subprocess.run(['bash', '-lc', 'p=$(ps -eo pid,args | grep "[p]ipeline_run.py" | awk \'{print $1}\' | head -1); '
                                '[ -n "$p" ] && kill $p && echo "killed orchestrator $p" || echo "no orchestrator"'],
                     stdout=subprocess.PIPE).stdout.decode().strip()
print("编排:", out)

# ② 取消所有非终态任务
js = wv.req('GET', '/projects/%s/jobs?limit=100' % wv.PID)
if isinstance(js, dict):
    js = js.get('items') or js.get('jobs') or []
live = [j for j in js if j.get('state') in ('queued', 'running')]
print("待取消任务: %d" % len(live))
for j in live:
    try:
        wv.req('POST', '/jobs/%s/cancel' % j.get('id'))
        print("  取消 %-8s shot=%-3s %s" % (j.get('kind'), (j.get('payload') or {}).get('shot_no'), j.get('id')))
    except Exception as e:
        print("  取消失败 %s: %s" % (j.get('id'), str(e)[:100]))

# ③ 中断 ComfyUI（清队列）
GW = 'http://180.127.11.167:12476'   # 端口每次平台重开都可能变（2026-09-19 = 12476）
for _ in range(2):
    try:
        req = urllib.request.Request(GW + '/interrupt', method='POST')
        urllib.request.urlopen(req, timeout=20).read()
        print("已中断 ComfyUI")
    except Exception as e:
        print("中断失败:", str(e)[:120])
    time.sleep(2)

# ④ 复核
time.sleep(3)
try:
    q = urllib.request.urlopen(GW + '/queue', timeout=20).read().decode()
    print("ComfyUI 队列:", q[:160])
except Exception as e:
    print("队列查询失败:", str(e)[:120])
js = wv.req('GET', '/projects/%s/jobs?limit=100' % wv.PID)
if isinstance(js, dict):
    js = js.get('items') or js.get('jobs') or []
left = [j for j in js if j.get('state') in ('queued', 'running')]
print("仍非终态任务: %d" % len(left))
