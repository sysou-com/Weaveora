#!/usr/bin/env python3
"""清理验证副作用：取消刚才为验证建的分段 clip 任务 + 还原 shot1 的时长/分段。py3.6 兼容。"""
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
        return e.code, e.read().decode()[:200]


# 1) 取消最近 10 分钟内的分段 clip 任务（我验证时建的）
ids = sh("sudo -u postgres psql -d weaveora -tAc \"select id from generation_jobs where project_id='%s' "
         "and kind='clip' and payload ? 'segment_index' and created_at > now() - interval '10 minutes' "
         "and state in ('queued','running')\"" % PJ).split()
print("cancel candidates:", len(ids))
for jid in ids:
    print("  cancel", jid[:8], api("POST", "/api/v1/jobs/%s/cancel" % jid)[0])

# 2) 还原 shot1（去掉测试写入的 segments / 恢复原时长 5.3s）
st, det = api("GET", "/api/v1/projects/%s/revisions/%s" % (PJ, RV))
plan = det["plan"]
s0 = plan["shots"][0]
if s0.get("segments"):
    s0.pop("segments", None)
    s0["duration_sec"] = 5.3
    plan["duration_sec"] = round(sum(float(x.get("duration_sec", 0) or 0) for x in plan["shots"]), 1)
    st2, _ = api("PATCH", "/api/v1/projects/%s/revisions/%s/plan/inplace" % (PJ, RV), {"plan": plan})
    print("restore shot1:", st2, "-> duration", s0["duration_sec"], "segments", len(s0.get("segments") or []))
else:
    print("shot1 无 segments，无需还原")
