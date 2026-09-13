#!/usr/bin/env python3
"""真机验证：按配音校准 → 就地保存（放宽时长校验）→ 镜头时长/分段是否落库并还原。py3.6 兼容。"""
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
PJ = sh("sudo -u postgres psql -d weaveora -tAc \"select project_id from assets where kind='voice' order by created_at desc limit 1\"")
RV = sh("sudo -u postgres psql -d weaveora -tAc \"select approved_revision_id from projects where id='%s'\"" % PJ)
WS = sh("sudo -u postgres psql -d weaveora -tAc \"select workspace_id from projects where id='%s'\"" % PJ)
H = {"Authorization": "Bearer " + TOK, "X-Workspace-Id": WS, "Content-Type": "application/json"}


def api(method, path, body=None):
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request("http://127.0.0.1:8080" + path, method=method, data=data, headers=H)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:260]


st, det = api("GET", "/api/v1/projects/%s/revisions/%s" % (PJ, RV))
print("GET detail:", st)
plan = det["plan"]
ep = plan.get("edit_plan") or {}
print("  edit_plan: cap=%s tail=%s subtitle=%s" % (ep.get("video_model_max_sec"), ep.get("tail_sec"), ep.get("subtitle")))

s0 = plan["shots"][0]
orig = s0.get("duration_sec")
s0["duration_sec"] = 8.2
s0["segments"] = [{"index": 0, "start_sec": 0, "duration_sec": 5.0},
                  {"index": 1, "start_sec": 5.0, "duration_sec": 3.2}]
plan["duration_sec"] = round(sum(float(x.get("duration_sec", 0) or 0) for x in plan["shots"]), 1)

st, res = api("PATCH", "/api/v1/projects/%s/revisions/%s/plan/inplace" % (PJ, RV), {"plan": plan})
print("PATCH inplace (8.2s + 2 segments):", st, "ok" if st == 200 else res)
if st == 200:
    p2 = res["plan"]["shots"][0]
    print("  shot1 duration=%s segments=%s" % (p2.get("duration_sec"), len(p2.get("segments") or [])))
    plan["shots"][0]["duration_sec"] = orig
    plan["shots"][0].pop("segments", None)
    st2, _ = api("PATCH", "/api/v1/projects/%s/revisions/%s/plan/inplace" % (PJ, RV), {"plan": plan})
    print("  restored:", st2)
