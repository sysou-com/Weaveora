#!/usr/bin/env python3
"""GPU comfy 引擎端到端验证：场景→job→comfy worker→资产(>100KB 真出图)。GPU 容器内运行。"""
import json, os, subprocess, sys, time, urllib.request, urllib.error
B = "https://sysou.com/weaveora"
PASS = "Passw0rd!23"
HERE = "/data/weaveora"

# 清掉可能残留的 worker
for pat in ("[s]tub_worker.py", "[g]pu_run.sh"):
    subprocess.run(["pkill", "-f", pat], stdout=subprocess.DEVNULL)
time.sleep(2)

def call(m, p, pay=None, tok=None, ws=None, timeout=500):
    h = {"Content-Type": "application/json"}
    if tok: h["Authorization"] = "Bearer " + tok
    if ws: h["X-Workspace-Id"] = ws
    data = json.dumps(pay).encode() if pay is not None else None
    req = urllib.request.Request(B + p, data=data, headers=h, method=m)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

email = "gpue2e.%d@weaveora.dev" % int(time.time() * 1000)
_, r = call("POST", "/api/v1/auth/register", {"email": email, "password": PASS, "displayName": "GPU"})
at = r["accessToken"]
_, me = call("GET", "/api/v1/me", tok=at)
ws = me["workspaces"][0]["id"]
_, p = call("POST", "/api/v1/projects", {"title": "GPU e2e", "mode": "image", "aspectRatio": "1:1"}, at, ws)
pid = p["id"]
_, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
             {"rawText": "青瓷盘子与一枝白梅，窗光，产品海报，1:1。", "mode": "image"}, at, ws)
st, g = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
if st != 200:
    print("generate:", st, str(g)[:150]); sys.exit(1)
rid = g["revisionId"]
call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
_, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "still", "count": 1}, at, ws)
print("job created:", len(jobs))

env = dict(os.environ)
env.update({"WEAVEORA_WORKER_MODE": "comfy", "WEAVEORA_API_BASE": B,
            "WEAVEORA_COMFY_URL": "http://127.0.0.1:8188", "WEAVEORA_WORKER_NAME": "gpu-e2e"})
proc = subprocess.Popen([sys.executable, os.path.join(HERE, "stub_worker.py")], env=env,
                        stdout=open(os.path.join(HERE, "gpu_e2e_worker.log"), "w"),
                        stderr=subprocess.STDOUT)
final = None
try:
    for _ in range(180):
        _, jobs = call("GET", "/api/v1/projects/%s/jobs" % pid, tok=at, ws=ws)
        if jobs and all(j["state"] in ("succeeded", "failed", "cancelled") for j in jobs):
            final = jobs; break
        time.sleep(3)
finally:
    proc.terminate()
    try: proc.wait(timeout=5)
    except Exception: proc.kill()
print("states:", [j["state"] for j in (final or [])])
if final:
    j = final[0]
    print("err:", j.get("errorCode"), str(j.get("errorMessage") or "")[:160])
_, assets = call("GET", "/api/v1/projects/%s/assets" % pid, tok=at, ws=ws)
still = [a for a in assets if a["kind"] == "still"]
print("stills:", len(still))
size = 0
if still:
    req = urllib.request.Request(B + "/api/v1/assets/%s/download" % still[0]["id"],
                                 headers={"Authorization": "Bearer " + at, "X-Workspace-Id": ws})
    with urllib.request.urlopen(req, timeout=200) as r:
        size = len(r.read())
print("download bytes:", size)
ok = bool(final) and all(j["state"] == "succeeded" for j in final) and size > 100000
print("RESULT", "PASS" if ok else "FAIL")
