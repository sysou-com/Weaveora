#!/usr/bin/env python3
"""W8 长片机械证明（A）：60s 视频项目 → 逐镜 still → render(concat) → 断言时长≈60s。"""
import json, os, subprocess, sys, time, urllib.request, urllib.error

B = "http://127.0.0.1:8080"
PASS = "Passw0rd!23"
DUR = 60

def call(m, p, pay=None, tok=None, ws=None, timeout=300):
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

email = "w8long.%d@weaveora.dev" % int(time.time() * 1000)
_, r = call("POST", "/api/v1/auth/register", {"email": email, "password": PASS, "displayName": "W8L"})
at = r["accessToken"]
_, me = call("GET", "/api/v1/me", tok=at)
ws = me["workspaces"][0]["id"]
_, p = call("POST", "/api/v1/projects", {"title": "W8 long", "mode": "video", "aspectRatio": "16:9", "durationSec": DUR}, at, ws)
pid = p["id"]
_, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
             {"rawText": "城市从清晨到夜晚的延时蒙太奇，60 秒，不要人脸，电影感。", "mode": "video"}, at, ws)
g = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
if g[0] != 200:
    print("generate1:", g); time.sleep(3)
    g = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
rid = g[1]["revisionId"]
plan = g[1]["plan"]
shots = plan.get("shots") or []
print("shots:", len(shots), "sum:", round(sum(x.get("duration_sec", 0) for x in shots), 2))
call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
_, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "still"}, at, ws)
print("still jobs:", len(jobs))
env = dict(os.environ); env["WEAVEORA_WORKER_MODE"] = "stub"
proc = subprocess.Popen([sys.executable, "/opt/weaveora/stub_worker.py"], env=env,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(300):
        _, jobs = call("GET", "/api/v1/projects/%s/jobs" % pid, tok=at, ws=ws)
        if jobs and all(j["state"] in ("succeeded", "failed") for j in jobs):
            break
        time.sleep(2)
finally:
    proc.terminate()
states = [j["state"] for j in jobs]
print("still states:", len(states), "succeeded:", states.count("succeeded"))
st, res = call("POST", "/api/v1/projects/%s/render" % pid, {"revisionId": rid, "transition": "cut"}, at, ws, timeout=900)
print("render:", st, res.get("kind") if isinstance(res, dict) else res)
_, assets = call("GET", "/api/v1/projects/%s/assets" % pid, tok=at, ws=ws)
m = [a for a in assets if a["kind"] == "master"]
mp4 = None
if m:
    req = urllib.request.Request(B + "/api/v1/assets/%s/download" % m[0]["id"],
                                 headers={"Authorization": "Bearer " + at, "X-Workspace-Id": ws})
    with urllib.request.urlopen(req, timeout=300) as r:
        mp4 = r.read()
print("master bytes:", len(mp4) if mp4 else 0)
ok = False
if mp4:
    with open("/opt/weaveora/w8long.mp4", "wb") as f:
        f.write(mp4)
    po = subprocess.Popen(["ffmpeg", "-hide_banner", "-i", "/opt/weaveora/w8long.mp4"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    _, oe = po.communicate()
    out = oe.decode(errors="replace")
    dur = None
    import re
    mm = re.search(r"Duration:\s*(\d+):(\d+):([\d.]+)", out)
    if mm:
        dur = int(mm.group(1)) * 3600 + int(mm.group(2)) * 60 + float(mm.group(3))
    has_v = "Video:" in out
    has_a = "Audio:" in out
    print("duration_s=%.2f video=%s audio=%s" % (dur or -1, has_v, has_a))
    ok = dur is not None and abs(dur - DUR) <= 3 and has_v and has_a
print("RESULT", "PASS" if ok else "FAIL")
