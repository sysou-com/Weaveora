#!/usr/bin/env python3
"""真机验证 D：分段镜头应建出 N 个 clip 任务（每段一个，带 segment_index）。py3.6 兼容。"""
import json
import subprocess
import urllib.request
import urllib.error


def sh(cmd):
    p = subprocess.run(["bash", "-lc", cmd], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, universal_newlines=True)
    return p.stdout.strip()


UU = sh("sudo -u postgres psql -d weaveora -tAc \"select id from users where email='sysou.com@outlook.com'\"")
TOK = sh("python3 /tmp/mint_token.py " + UU)
PJ = sh("sudo -u postgres psql -d weaveora -tAc \"select project_id from generation_jobs where kind='clip' order by created_at desc limit 1\"")
RV = sh("sudo -u postgres psql -d weaveora -tAc \"select approved_revision_id from projects where id='%s'\"" % PJ)
WS = sh("sudo -u postgres psql -d weaveora -tAc \"select workspace_id from projects where id='%s'\"" % PJ)
H = {"Authorization": "Bearer " + TOK, "X-Workspace-Id": WS, "Content-Type": "application/json"}


def api(method, path, body=None):
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request("http://127.0.0.1:8080" + path, method=method, data=data, headers=H)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


st, det = api("GET", "/api/v1/projects/%s/revisions/%s" % (PJ, RV))
plan = det["plan"]
# 找一个有 still 的镜（motion 需要关键帧）
shot = plan["shots"][0]
shotNo = shot["shot_no"]
print("target shot:", shotNo, "duration:", shot.get("duration_sec"))
shot["duration_sec"] = 8.2
shot["segments"] = [{"index": 0, "start_sec": 0, "duration_sec": 5.0},
                    {"index": 1, "start_sec": 5.0, "duration_sec": 3.2}]
st, _ = api("PATCH", "/api/v1/projects/%s/revisions/%s/plan/inplace" % (PJ, RV), {"plan": plan})
print("write segments:", st)

before = len(sh("sudo -u postgres psql -d weaveora -tAc \"select id from generation_jobs where project_id='%s' and kind='clip'\"" % PJ).split())
st, res = api("POST", "/api/v1/projects/%s/jobs" % PJ,
              {"revisionId": RV, "kind": "clip", "shotNos": [shotNo]})
n = len(res) if isinstance(res, list) else -1
print("POST clip jobs:", st, "created:", n)
if isinstance(res, list):
    for j in res:
        pl = j.get("payload") or {}
        print("   seg=%s/%s frames=%s dur=%s" % (pl.get("segment_index"), pl.get("segment_count"),
                                                 pl.get("frames"), pl.get("segment_duration_sec")))
after = len(sh("sudo -u postgres psql -d weaveora -tAc \"select id from generation_jobs where project_id='%s' and kind='clip'\"" % PJ).split())
print("clip jobs in DB: %d -> %d" % (before, after))
