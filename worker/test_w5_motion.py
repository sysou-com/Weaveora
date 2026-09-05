#!/usr/bin/env python3
"""W5 motion 流程测试（stub：still→PNG、clip→动画 WebP）。用法：起本地 api 后 python3 test_w5_motion.py"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
PASS = "Passw0rd!23"
HERE = os.path.dirname(__file__)


def call(method, path, payload=None, token=None, ws=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if ws:
        h["X-Workspace-Id"] = ws
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def run_worker_until_done(token, ws, pid):
    """后台常驻 stub worker，等本批次任务全到终态后终止。"""
    env = dict(os.environ)
    env["WEAVEORA_WORKER_NAME"] = "w5-worker"
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "stub_worker.py")],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        return wait_states(token, ws, pid, ("succeeded", "failed"))
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


def wait_states(token, ws, pid, until):
    deadline = time.time() + 120
    while time.time() < deadline:
        _, jobs = call("GET", "/api/v1/projects/%s/jobs" % pid, token=token, ws=ws)
        if jobs and all(j["state"] in until for j in jobs):
            return jobs
        time.sleep(2)
    return None


def main():
    email = "w5.%d@weaveora.dev" % int(time.time() * 1000)
    st, reg = call("POST", "/api/v1/auth/register", {"email": email, "password": PASS, "displayName": "W5"})
    assert st == 200, reg
    at = reg["accessToken"]
    _, me = call("GET", "/api/v1/me", token=at)
    ws = me["workspaces"][0]["id"]
    _, p = call("POST", "/api/v1/projects", {"title": "W5 运动", "mode": "video", "aspectRatio": "16:9", "durationSec": 6},
                at, ws)
    pid = p["id"]
    _, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
                 {"rawText": "一片叶子在雨夜城市灯光里旋转，6 秒，孤独，不要人脸。", "mode": "video"}, at, ws)
    _, gen = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
    rid = gen["revisionId"]
    _, ap = call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
    print("scene ready approve=%s shots=%s" % (ap.get("projectStatus"), len(gen["plan"].get("shots") or [])))

    # still 关键帧
    _, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "still"}, at, ws)
    print("still created:", len(jobs))
    jobs = run_worker_until_done(at, ws, pid)
    print("stills:", [j["state"] for j in (jobs or [])])

    # motion clip：现在仍可直接请求（服务端要求每镜有 still 产物）
    _, clips = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "clip"}, at, ws)
    if clips and "message" in clips:
        print("clip gate:", clips["message"])
        raise SystemExit(1)
    print("clips created:", len(clips))
    jobs = run_worker_until_done(at, ws, pid)
    final = jobs or []
    print("final:", [j["state"] for j in final])

    _, assets = call("GET", "/api/v1/projects/%s/assets" % pid, token=at, ws=ws)
    kinds = {}
    for a in assets:
        kinds.setdefault(a["kind"] + "/" + (a["mime"] or ""), 0)
        kinds[a["kind"] + "/" + (a["mime"] or "")] += 1
    print("assets:", kinds)
    ok = bool(final) and all(j["state"] == "succeeded" for j in final) and \
         any(k.startswith("clip/") for k in kinds)
    print("RESULT", "PASS" if ok else "FAIL")


if __name__ == "__main__":
    main()
