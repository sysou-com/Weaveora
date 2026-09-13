#!/usr/bin/env python3
"""验证 kind=lipsync：任务能被创建（画面+配音具备时），且未配置工作流时给出明确错误。py3.6 兼容。"""
import json
import subprocess
import urllib.request
import urllib.error


def sh(cmd):
    return subprocess.run(["bash", "-lc", cmd], stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, universal_newlines=True).stdout.strip()


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
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


st, res = api("POST", "/api/v1/projects/%s/jobs" % PJ, {"revisionId": RV, "kind": "lipsync"})
print("POST kind=lipsync ->", st)
if isinstance(res, list):
    print("  created:", len(res))
    for j in res[:3]:
        pl = j.get("payload") or {}
        print("   shot=%s videoKey=%s… voiceKeys=%s still=%s"
              % (pl.get("shot_no"), (pl.get("videoKey") or "")[-12:], pl.get("voiceCount"), pl.get("isStill")))
    ids = [j["id"] for j in res]
    print("  等待 worker 处理（预期：未装口型节点 → LIPSYNC_ERROR 明确报错）…")
    import time
    time.sleep(25)
    for jid in ids[:2]:
        s2, stt = api("GET", "/api/v1/jobs/%s" % jid)
        print("   job %s -> %s" % (jid[:8], stt.get("state") if isinstance(stt, dict) else stt))
else:
    print("  ", res)
