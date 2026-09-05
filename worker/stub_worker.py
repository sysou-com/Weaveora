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
MODE = os.environ.get("WEAVEORA_WORKER_MODE", "stub")  # stub|comfy


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


FALLBACK_WEBP = "UklGRtIAAABXRUJQVlA4WAoAAAACAAAALwAALwAAQU5JTQYAAAAAAAAAAABBTk1GVAAAAAAAAAAAAC8AAC8AAMgAAAJWUDggPAAAAHADAJ0BKjAAMAA+bTaYSSQjIqEjiACADYlpAAIDiGGv401UAAD+9PQ/90n6n+i/6qp6/G7/eG4v7wwAAEFOTUZKAAAAAAAAAAAALwAALwAAyAAAAFZQOCAyAAAA9AIAnQEqMAAwAD5tMJFIgjgAANiWkAAgOKK/T4J4hgAA/vKpfzse4OezX/ELc/xCQAA="


def make_animated_webp(width, height, seed, frames=14, duration_ms=110):
    """stub motion 占位：优先 PIL 生成动画 WebP；无 PIL（精简环境）用内置动图回退。"""
    try:
        from PIL import Image, ImageDraw
    except Exception:
        import base64 as _b64
        return _b64.b64decode(FALLBACK_WEBP)
    import io as _io
    w, h = max(8, width or 320), max(8, height or 320)
    imgs = []
    for i in range(frames):
        img = Image.new("RGB", (w, h), ((seed * 3 + i * 7) % 256, (seed // 7 + i * 5) % 256, (seed // 13) % 256))
        d = ImageDraw.Draw(img)
        step = (w * 2) // max(1, frames)
        x0 = (i * step) % (w + 40) - 20
        d.rectangle([x0, h // 3, x0 + max(10, w // 6), h - h // 3], fill=(255, 240, 200))
        imgs.append(img)
    buf = _io.BytesIO()
    imgs[0].save(buf, format="WEBP", save_all=True, append_images=imgs[1:],
                 duration=duration_ms, loop=0)
    return buf.getvalue()

def _complete(jid, payload, media):
    """media: list[(bytes, mime, w, h, dur_ms)]；上传第一个产物并 complete。"""
    if not media:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "EMPTY_OUTPUT", "message": "引擎没有输出"})
        return False
    data, mime, w, h, dur = media[0]
    ext = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp",
           "video/mp4": "mp4", "video/webm": "webm"}.get(mime, "bin")
    seed = int(payload.get("seed") or random.randint(1, 2 ** 31))
    st, up = _req("POST", "/internal/jobs/%s/assets" % jid, files={
        "file": ("out_%s.%s" % (jid[:8], ext), data, mime)})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "UPLOAD_FAIL", "message": str(up)[:200]})
        return False
    st, done = _req("POST", "/internal/jobs/%s/complete" % jid, {
        "assets": [{"key": up["key"], "mime": mime, "width": w, "height": h, "seed": seed,
                    "durationMs": dur}]})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "COMPLETE_FAIL", "message": str(done)[:200]})
        return False
    print("[%s] job %s ok kind=%s mime=%s key=%s" % (MODE, jid[:8], payload.get("kind"), mime, up["key"]),
          flush=True)
    return True


def execute_job(job):
    jid = job["jobId"]
    payload = job.get("payload") or {}
    kind = payload.get("kind") or "still"
    seed = int(payload.get("seed") or random.randint(1, 2 ** 31))
    params = payload.get("params") or {}
    width = int(params.get("width") or 1024)
    height = int(params.get("height") or 1024)
    _req("POST", "/internal/jobs/%s/progress" % jid, {"progress": 15, "stage": "loading_model"})

    if MODE == "cloud":
        if kind == "clip":
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "CLOUD_MOTION_UNSUPPORTED",
                  "message": "运动(clip)暂需本地 Wan 引擎或云视频适配器（尚未接线）"})
            return False
        import cloud_client as cloud
        try:
            outs = cloud.generate_still(payload, progress_fn=lambda p, st: _req(
                "POST", "/internal/jobs/%s/progress" % jid,
                {"progress": p, "stage": st}))
            media = [(o[0], o[1], o[2], o[3], o[4]) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "CLOUD_ERROR", "message": str(e)[:500]})
            return False

    if MODE == "comfy":
        import comfy_client as engine
        try:
            if kind == "clip":
                outs = engine.generate_motion("weaveora-stub-worker", payload,
                                              progress_fn=lambda p, s: _req(
                                                  "POST", "/internal/jobs/%s/progress" % jid,
                                                  {"progress": p, "stage": s}))
                media = [(o["bytes"], o.get("mime") or "image/webp",
                          o.get("width") or width, o.get("height") or height,
                          int(float(payload.get("duration_sec", 3.0)) * 1000)) for o in outs]
            else:
                outs = engine.generate("weaveora-stub-worker", payload,
                                       progress_fn=lambda p, s: _req(
                                           "POST", "/internal/jobs/%s/progress" % jid,
                                           {"progress": p, "stage": s}))
                media = [(o["bytes"], "image/png", width, height, None) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "COMFY_ERROR", "message": str(e)[:500]})
            return False

    # stub：still → 占位 PNG；clip → 动画 WebP（motion 占位）
    _req("POST", "/internal/jobs/%s/progress" % jid, {"progress": 40, "stage": "sampling"})
    time.sleep(0.2)
    if kind == "clip":
        webp = make_animated_webp(width, height, seed)
        return _complete(jid, payload, [(webp, "image/webp", width, height,
                                         int(float(payload.get("duration_sec", 3.0)) * 1000))])
    png = make_png(width, height, seed)
    return _complete(jid, payload, [(png, "image/png", width, height, None)])


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
    print("[%s] done jobs=%d" % (MODE, worked), flush=True)


if __name__ == "__main__":
    main()
