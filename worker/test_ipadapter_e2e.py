#!/usr/bin/env python3
"""W4 一致性锚定验收（GPU comfy + IP-Adapter）：同参考图 → 4 张 still，主体一致。

引擎侧强制 FALLBACK_TXT2IMG=0：若 IPAdapter 工作流失败会显式报错，杜绝静默降级。
用法：在 GPU 容器 /data/weaveora 内运行（worker 代码与 comfy 在同一台）。
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

B = "https://sysou.com/weaveora"
PASS = "Passw0rd!23"
HERE = "/data/weaveora"
OUT = os.path.join(HERE, "ipa_accept")

os.makedirs(OUT, exist_ok=True)
sys.path.insert(0, HERE)
import comfy_client as C  # noqa: E402

def call(m, p, pay=None, tok=None, ws=None, to=400):
    h = {"Content-Type": "application/json"}
    if tok: h["Authorization"] = "Bearer " + tok
    if ws: h["X-Workspace-Id"] = ws
    data = json.dumps(pay).encode() if pay is not None else None
    req = urllib.request.Request(B + p, data=data, headers=h, method=m)
    try:
        with urllib.request.urlopen(req, timeout=to) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")
    except Exception as e:
        return -1, {"err": type(e).__name__ + ":" + str(e)[:100]}

def upload_file(at, ws, project_id, path):
    data = open(path, "rb").read()
    boundary = "----wf" + str(int(time.time() * 1e6))
    body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ref.png\"\r\n"
             "Content-Type: image/png\r\n\r\n") % boundary).encode() + data + b"\r\n--" + boundary.encode() + b"--\r\n"
    req = urllib.request.Request(B + "/api/v1/projects/%s/assets" % project_id,
                                 data=body, headers={
                                     "Content-Type": "multipart/form-data; boundary=" + boundary,
                                     "Authorization": "Bearer " + at, "X-Workspace-Id": ws}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

def download(at, ws, asset_id):
    req = urllib.request.Request(B + "/api/v1/assets/%s/download" % asset_id,
                                 headers={"Authorization": "Bearer " + at, "X-Workspace-Id": ws})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()

# ---------- 0. 用 comfy 直出参考图（唯一主体：青花小茶壶，米白背景） ----------
print("make-reference", flush=True)
ref_payload = {
    "kind": "still", "mode": "image",
    "positive_prompt": ("a small celadon ceramic teapot with white plum blossom pattern, "
                        "single product, centered, soft warm studio light, plain warm-gray background, 1:1"),
    "negative_prompt": "text, watermark, person, multiple objects, shadow on floor",
    "params": {"width": 768, "height": 768, "steps": 20, "cfg": 5.0,
               "sampler": "dpmpp_2m", "scheduler": "normal"},
    "seed": 11,
}
ref = C.generate("ipa-ref", ref_payload)
ref_bytes = ref[0]["bytes"]
open(os.path.join(OUT, "00_ref.png"), "wb").write(ref_bytes)
print("reference bytes:", len(ref_bytes), flush=True)

# ---------- 1. 公共 API 链路：项目 + 参考图 + brief(带 referenceAssetIds) + 方案 ----------
email = "ipa.%d@weaveora.dev" % int(time.time() * 1000)
_, r = call("POST", "/api/v1/auth/register",
            {"email": email, "password": PASS, "displayName": "IPA"})
if "accessToken" not in r:
    print("register fail:", str(r)[:150]); sys.exit(1)
at = r["accessToken"]
_, me = call("GET", "/api/v1/me", tok=at)
ws = me["workspaces"][0]["id"]
_, p = call("POST", "/api/v1/projects", {"title": "ipa-consistency", "mode": "image",
                                         "aspectRatio": "1:1"}, at, ws)
pid = p["id"]
st, asr = upload_file(at, ws, pid, os.path.join(OUT, "00_ref.png"))
print("upload ref:", st, asr.get("kind"), asr.get("id", "")[:8], flush=True)
ref_id = asr["id"]
_, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
             {"rawText": "同一只青瓷白梅茶壶的四种镜头表现，风格统一、主体一致。",
              "mode": "image", "referenceAssetIds": [ref_id]}, at, ws)
st, g = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
print("generate:", st, "rev:", g.get("revisionNo") if isinstance(g, dict) else g, flush=True)
if st != 200:
    print("generate err:", str(g)[:200]); sys.exit(1)
rid = g["revisionId"]
_, ap = call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
print("approve:", ap.get("state") if isinstance(ap, dict) else ap, flush=True)

# ---------- 2. 拉起引擎 worker（FALLBACK=0：ipa 失败必须显式报错） ----------
env = dict(os.environ)
env.update({"WEAVEORA_WORKER_MODE": "comfy", "WEAVEORA_API_BASE": B,
            "WEAVEORA_COMFY_URL": "http://127.0.0.1:8188",
            "WEAVEORA_COMFY_FALLBACK_TXT2IMG": "0",
            "WEAVEORA_WORKER_NAME": "ipa-accept"})
proc = subprocess.Popen([sys.executable, os.path.join(HERE, "stub_worker.py")], env=env,
                        stdout=open(os.path.join(HERE, "ipa_worker.log"), "w"),
                        stderr=subprocess.STDOUT)

# ---------- 3. 4 张 still 任务 ----------
st3, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid,
                 {"revisionId": rid, "kind": "still", "count": 4}, at, ws)
print("jobs create:", st3, len(jobs) if isinstance(jobs, list) else jobs, flush=True)
if st3 != 200:
    print("RESULT FAIL jobs", str(jobs)[:200]); sys.exit(1)
jids = [j["id"] for j in jobs]
final = None
try:
    for _ in range(200):  # <=10min
        _, jl = call("GET", "/api/v1/projects/%s/jobs" % pid, tok=at, ws=ws)
        if all(x["state"] in ("succeeded", "failed", "cancelled") for x in jl):
            final = jl; break
        time.sleep(3)
finally:
    proc.terminate()
    try: proc.wait(timeout=5)
    except Exception: proc.kill()

by_state = {}
for j in (final or []):
    by_state[j["id"]] = j
print("states:", [(j["state"], (j.get("errorCode") or "")) for j in (final or [])], flush=True)
fail = [j for j in (final or []) if j["state"] != "succeeded"]
if fail:
    print("FAIL reason:", fail[0].get("errorCode"), str(fail[0].get("errorMessage") or "")[:200], flush=True)
    print("RESULT FAIL", flush=True); sys.exit(1)

_, assets = call("GET", "/api/v1/projects/%s/assets" % pid, tok=at, ws=ws)
stills = [a for a in assets if a["kind"] == "still"]
print("stills:", len(stills), flush=True)
sizes = []
for i, a in enumerate(stills[:4]):
    b = download(at, ws, a["id"])
    open(os.path.join(OUT, "%02d_still.png" % (i + 1)), "wb").write(b)
    sizes.append(len(b))
    print("  still%d bytes=%d" % (i + 1, len(b)), flush=True)
ok = len(stills) >= 4 and all(s > 100000 for s in sizes)
print("RESULT", "PASS" if ok else "FAIL", flush=True)
print("OUTDIR", OUT, flush=True)
