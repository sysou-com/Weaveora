#!/usr/bin/env python3
"""W4 Comfy 引擎流程测试：假 ComfyUI（/prompt /history /view /upload/image）+ 参考图锚定。

场景：注册→图片项目→上传参考图→带 ref 的 Brief→导演(stub)→approve→建任务→
MODE=comfy worker(--once)→假 Comfy 收 txt2img/IP-Adapter 请求→回 PNG→complete→任务成功。
用法：先起本地 api，再 python3 test_comfy_flow.py
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(__file__))
from stub_worker import make_png

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
PASS = "Passw0rd!23"
FAKE_PORT = int(os.environ.get("FAKE_COMFY_PORT", "18188"))
COMFY_LOG = []


class FakeComfy(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path == "/prompt":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            doc = json.loads(body)
            COMFY_LOG.append(doc)
            self._json({"prompt_id": "p1"})
        elif self.path == "/upload/image":
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)
            COMFY_LOG.append({"uploaded": True})
            self._json({"name": "ref.png", "subfolder": "", "type": "input"})
        else:
            self.send_response(404); self.end_headers()

    def do_GET(self):
        if self.path.startswith("/history/"):
            rec = {"p1": {"status": {"status_str": "success"},
                          "outputs": {"save": {"images": [
                              {"filename": "weaveora_x.png", "subfolder": "", "type": "output"}]}}}}
            self._json(rec)
        elif self.path.startswith("/view?"):
            png = make_png(512, 512, 77)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.end_headers()
            self.wfile.write(png)
        else:
            self.send_response(404); self.end_headers()


def call(method, path, payload=None, token=None, ws=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if ws:
        h["X-Workspace-Id"] = ws
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def upload_ref(token, ws, pid):
    png = make_png(320, 320, 5)
    bd = "----wv" + str(int(time.time() * 1e6))
    body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r.png\"\r\n"
             "Content-Type: image/png\r\n\r\n" % bd).encode() + png + b"\r\n" +
            (("--%s--\r\n" % bd).encode()))
    h = {"Authorization": "Bearer " + token, "X-Workspace-Id": ws,
         "Content-Type": "multipart/form-data; boundary=" + bd}
    req = urllib.request.Request(API + "/api/v1/projects/%s/assets" % pid,
                                 data=body, headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def main():
    srv = HTTPServer(("127.0.0.1", FAKE_PORT), FakeComfy)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    email = "w4.%d@weaveora.dev" % int(time.time() * 1000)
    st, reg = call("POST", "/api/v1/auth/register", {"email": email, "password": PASS, "displayName": "W4"})
    assert st == 200, reg
    at = reg["accessToken"]
    _, me = call("GET", "/api/v1/me", token=at)
    ws = me["workspaces"][0]["id"]
    _, p = call("POST", "/api/v1/projects", {"title": "W4 一致性", "mode": "image", "aspectRatio": "1:1"}, at, ws)
    pid = p["id"]
    ref = upload_ref(at, ws, pid)
    _, br = call("POST", "/api/v1/projects/%s/briefs" % pid,
                 {"rawText": "青瓷盘子与一枝白梅，窗光，产品海报，用参考图保持一致。", "mode": "image",
                  "referenceAssetIds": [ref["id"]]}, at, ws)
    _, gen = call("POST", "/api/v1/projects/%s/director/generate" % pid, {"briefId": br["id"]}, at, ws)
    rid = gen["revisionId"]
    _, ap = call("POST", "/api/v1/projects/%s/revisions/%s/approve" % (pid, rid), {}, at, ws)
    _, jobs = call("POST", "/api/v1/projects/%s/jobs" % pid, {"revisionId": rid, "kind": "still", "count": 1}, at, ws)
    print("registered/approve/jobs:", st, ap.get("projectStatus"), len(jobs))

    env = dict(os.environ)
    env.update({"WEAVEORA_WORKER_MODE": "comfy", "WEAVEORA_COMFY_URL": "http://127.0.0.1:%d" % FAKE_PORT,
                "WEAVEORA_WORKER_NAME": "w4-comfy"})
    proc = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), "stub_worker.py"), "--once"],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out, _ = proc.communicate(timeout=120)
    print((out or b"").decode().strip().splitlines()[-1] if out else "no worker output")

    deadline = time.time() + 30
    final = None
    while time.time() < deadline:
        _, jobs = call("GET", "/api/v1/projects/%s/jobs" % pid, token=at, ws=ws)
        if jobs and all(j["state"] in ("succeeded", "failed") for j in jobs):
            final = jobs
            break
        time.sleep(2)
    print("job states:", [j["state"] for j in (final or [])])

    has_ref_in_prompt = any(
        "referenceKeys" in (doc.get("prompt", {}).get("inputs", {}) or {}) or
        ("prompt" in doc and "load_ref" in doc["prompt"].get("prompt", {}))
        for doc in COMFY_LOG)
    has_ipadapter = any("ip_apply" in doc.get("prompt", {}).get("prompt", {})
                        for doc in COMFY_LOG if "prompt" in doc and isinstance(doc["prompt"].get("prompt"), dict))
    print("comfy got ipadapter graph:", has_ipadapter, "| requests:", len(COMFY_LOG))
    ok = bool(final) and all(j["state"] == "succeeded" for j in final)
    print("RESULT", "PASS" if ok else "FAIL")


if __name__ == "__main__":
    main()
