#!/usr/bin/env python3
"""Weaveora stub worker（§19：出站连 API，不反向连中间件）。

行为：注册节点 → 心跳（线程）→ 轮询 claim → 取到 job 后用纯 Python 生成一张占位 PNG
      → 上传 /internal/jobs/{id}/assets → complete。WEAVEORA_WORKER_MODE=stub|comfy 的语义在本文件=stub。

环境变量：
  WEAVEORA_API_BASE   默认 http://localhost:8080
  WEAVEORA_WORKER_TOKEN 默认 dev-worker-token（与 weaveora.worker.token 对应）
  WEAVEORA_WORKER_NAME  默认 stub-worker
  WEAVEORA_WORKER_WORKSPACE 可选 uuid（BYO）；不填 = 节点池
用法：python stub_worker.py            # 常驻循环
      python stub_worker.py --once    # 认领并完成一个任务后退出（便于联调）
"""
import argparse
import json
import os
import random
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
TOKEN = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")
NAME = os.environ.get("WEAVEORA_WORKER_NAME", "stub-worker")
WORKSPACE = os.environ.get("WEAVEORA_WORKER_WORKSPACE")  # None = 节点池


def _req(method, path, payload=None, files=None, timeout=30):
    headers = {"X-Worker-Token": TOKEN}
    data = None
    if files:
        boundary = "----wv" + str(random.randint(10 ** 10, 10 ** 12))
        parts = []
        for field, (fname, content, ctype) in files.items():
            parts.append(
                ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                 "Content-Type: %s\r\n\r\n" % (boundary, field, fname, ctype)).encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode())
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            return r.status, json.loads(body.decode()) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode() or "{}"
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"error": body[:300]}


def make_png(width, height, seed):
    """纯标准库占位 PNG：按 seed 取色的横向渐变块。"""
    w, h = max(8, int(width or 1024)), max(8, int(height or 1024))
    r = (seed or 7) % 256
    g = (seed or 7) // 256 % 256
    b = (seed or 7) // 65536 % 256
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        t = (y * 255) // max(1, h - 1)
        for _ in range(w):
            raw += bytes(((r + t) % 256, (g + 255 - t) % 256, b))
    comp = zlib.compress(bytes(raw), 6)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")


def execute_job(job):
    jid = job["jobId"]
    payload = job.get("payload") or {}
    seed = int(payload.get("seed") or random.randint(1, 2 ** 31))
    params = payload.get("params") or {}
    width = int(params.get("width") or 1024)
    height = int(params.get("height") or 1024)
    # 模拟阶段进度
    _req("POST", "/internal/jobs/%s/progress" % jid, {"progress": 40, "stage": "sampling"})
    png = make_png(width, height, seed)
    time.sleep(0.2)  # 模拟算力耗时
    st, up = _req("POST", "/internal/jobs/%s/assets" % jid, files={
        "file": ("stub_%s.png" % jid[:8], png, "image/png")})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "UPLOAD_FAIL", "message": str(up)[:200]})
        return False
    key = up["key"]
    st, done = _req("POST", "/internal/jobs/%s/complete" % jid, {
        "assets": [{"key": key, "mime": "image/png", "width": width, "height": height, "seed": seed}]})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "COMPLETE_FAIL", "message": str(done)[:200]})
        return False
    print("[stub] job %s ok key=%s" % (jid[:8], key), flush=True)
    return True


def register():
    st, body = _req("POST", "/internal/nodes/register", {
        "name": NAME,
        "workspaceId": WORKSPACE or None,
        "capabilities": {"gpu": "stub-cpu", "workflows": ["stub_txt2img", "stub_motion"]},
    })
    if st != 200:
        raise SystemExit("register failed: %s %s" % (st, body))
    return body["nodeId"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true", help="完成一个任务即退出")
    args = ap.parse_args()

    node_id = register()
    print("[stub] node %s registered (workspace=%s)" % (node_id[:8], WORKSPACE or "pool"), flush=True)

    stop = threading.Event()

    def heartbeat():
        while not stop.is_set():
            _req("POST", "/internal/nodes/%s/heartbeat" % node_id, {})
            time.sleep(25)

    threading.Thread(target=heartbeat, daemon=True).start()

    worked = 0
    try:
        while not stop.is_set():
            st, body = _req("POST", "/internal/nodes/%s/claim" % node_id, {}, timeout=35)
            job = (body or {}).get("job")
            if not job:
                if args.once and worked == 0:
                    time.sleep(1)
                if args.once:
                    print("[stub] no job; exit", flush=True)
                    break
                time.sleep(2)
                continue
            if execute_job(job):
                worked += 1
            if args.once:
                break
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
    print("[stub] done jobs=%d" % worked, flush=True)


if __name__ == "__main__":
    main()
