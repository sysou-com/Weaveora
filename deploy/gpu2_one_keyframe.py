# -*- coding: utf-8 -*-
"""只跑一张关键帧（第3镜 起始帧）供检查：先停编排、取消所有在跑/排队任务，再只建 1 个 still 任务。"""
import json
import subprocess
import sys
import time

sys.path.insert(0, '/tmp')
import wv  # noqa: E402

# ① 停后台编排
out = subprocess.run(['bash', '-lc', 'p=$(ps -eo pid,args | grep "[p]ipeline_run.py" | awk \'{print $1}\' | head -1); '
                                '[ -n "$p" ] && kill $p && echo "killed $p" || echo "no orchestrator"'],
                     stdout=subprocess.PIPE).stdout.decode()
print("编排：", out.strip())

# ② 取消所有 queued/running 任务
js = wv.req('GET', '/projects/%s/jobs?limit=50' % wv.PID)
if isinstance(js, dict):
    js = js.get('items') or js.get('jobs') or []
live = [j for j in js if j.get('state') in ('queued', 'running')]
print("要取消的在跑/排队任务：%d 个" % len(live))
for j in live:
    try:
        wv.req('POST', '/jobs/%s/cancel' % j.get('id'))
        print("  取消 %s %s shot=%s" % (j.get('kind'), j.get('id'), (j.get('payload') or {}).get('shot_no')))
    except Exception as e:
        print("  取消失败 %s: %s" % (j.get('id'), str(e)[:120]))

# ③ 只建第3镜（会生成 起始帧+结束帧 两个任务）→ 立刻把「结束帧」取消，只留起始帧跑
revs = wv.req('GET', '/projects/%s/revisions' % wv.PID)
rid = ([r for r in revs if r.get('approved')] or revs)[0].get('id')
created = wv.req('POST', '/projects/%s/jobs' % wv.PID, {"revisionId": rid, "kind": "still", "shotNos": [3]})
keep = None
for j in (created or []):
    p = j.get('payload') or {}
    label = p.get('frame_label')
    print("  建 still shot=%s frame=%s label=%s id=%s" % (p.get('shot_no'), p.get('keyframe_index'), label, j.get('id')))
    if p.get('keyframe_index') == 0:
        keep = j.get('id')
    else:
        try:
            wv.req('POST', '/jobs/%s/cancel' % j.get('id'))
            print("    已取消（只留起始帧）")
        except Exception as e:
            print("    取消失败：%s" % str(e)[:120])
print("观察任务：", keep)
