#!/usr/bin/env python3
"""本地 E2E（W3）：注册→项目→Brief→导演(stub)→整版确认→建任务→stub worker 回执→资产。
用法：先起 api（dev），再 python e2e_local.py [--workers 4]"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
PASS = "Passw0rd!23"


def call(method, path, payload=None, token=None, ws=None, files=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if ws:
        headers["X-Workspace-Id"] = ws
    data = None
    boundary = None
    if files:
        boundary = "----wv" + str(int(time.time() * 1e6))
        parts = []
        for field, (fname, content) in files.items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                          "Content-Type: application/octet-stream\r\n\r\n" % (boundary, field, fname)).encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode())
        data = b"".join(parts)
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
        if token:
            headers["Authorization"] = "Bearer " + token
        if ws:
            headers["X-Workspace-Id"] = ws
    elif payload is not None:
        data = json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode() or "{}"
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=2)
    args = ap.parse_args()

    email = "e2e.%d@weaveora.dev" % int(time.time() * 1000)
    st, reg = call("POST", "/api/v1/auth/register", {"email": email, "password": PASS, "displayName": "E2E"})
    assert st == 200, reg
    at = reg["accessToken"]
    _, me = call("GET", "/api/v1/me", token=at)
    ws = me["workspaces"][0]["id"]
    print("registered", ws[:8])

    st, proj = call("POST", "/api/v1/projects", {"title": "E2E 纸船", "mode": "video", "aspectRatio": "16:9", "durationSec": 12}, at, ws)
    pid = proj["id"]
    _, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
                 {"rawText": "一只纸船在暴雨城市的运河里漂过霓虹，12 秒，孤独，不要人脸。", "mode": "video"}, at, ws)
    st, gen = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
    assert st == 200, gen
    rid = gen["revisionId"]
    print("generate", gen.get("source"), "shots", len(gen["plan"].get("shots") or []))

    st, ap = call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
    print("approve", st, ap.get("projectStatus"))

    st, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "still"}, at, ws)
    assert st == 200, jobs
    print("jobs created", len(jobs), "states", sorted({j["state"] for j in jobs}))

    # 起 stub worker 常驻，等所有任务到终态
    env = dict(os.environ)
    env["WEAVEORA_API_BASE"] = API
    env["WEAVEORA_WORKER_NAME"] = "e2e-worker-" + str(int(time.time()))
    proc = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), "stub_worker.py")],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    deadline = time.time() + 60
    result = None
    try:
        while time.time() < deadline:
            st, jobs = call("GET", "/api/v1/projects/%s/jobs" % pid, token=at, ws=ws)
            states = [j["state"] for j in jobs]
            print("  jobs", states, flush=True)
            if states and all(s in ("succeeded", "failed", "cancelled") for s in states):
                result = jobs
                break
            time.sleep(2)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

    if result is None:
        print("TIMEOUT waiting jobs; logs:")
        out, _ = proc.communicate(timeout=2)
        print((out or b"").decode()[-1500:])
        sys.exit(1)
    ok = all(j["state"] == "succeeded" for j in result)
    print("final", [j["state"] for j in result])

    st, assets = call("GET", "/api/v1/projects/%s/assets" % pid, token=at, ws=ws)
    print("assets", st, [(a["kind"], a["mime"]) for a in assets])
    if assets:
        dl = urllib.request.Request(API + "/api/v1/assets/%s/download" % assets[0]["id"],
                                    headers={"Authorization": "Bearer " + at, "X-Workspace-Id": ws})
        with urllib.request.urlopen(dl, timeout=30) as r:
            body = r.read()
        print("download-bytes", len(body))
    print("RESULT", "PASS" if ok and assets else "FAIL")


if __name__ == "__main__":
    main()
