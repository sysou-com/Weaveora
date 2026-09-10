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
import socket as _socket
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

# VPS 无 IPv6 路由，而 api.replicate.com 等 CDN 域名偶发返回 AAAA → urllib 报
# [Errno 101] Network is unreachable。进程级强制 IPv4（失败再回退默认解析）。
_orig_getaddrinfo = _socket.getaddrinfo


def _ipv4_first(host, port, family=0, type=0, proto=0, flags=0):
    try:
        infos = _orig_getaddrinfo(host, port, _socket.AF_INET, type, proto, flags)
        if infos:
            return infos
    except Exception:
        pass
    return _orig_getaddrinfo(host, port, family, type, proto, flags)


_socket.getaddrinfo = _ipv4_first

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
           "video/mp4": "mp4", "video/webm": "webm",
           "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav",
           "audio/mpeg": "mp3", "audio/mp3": "mp3",
           "audio/mp4": "m4a", "audio/aac": "m4a", "audio/ogg": "ogg"}.get(mime, "bin")
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

    # P7 自托管音频：配音(CosyVoice) / 配乐(音乐生成) —— 与 MODE 无关，走本机音频服务
    if kind in ("voice", "bgm"):
        import audio_client as audio
        try:
            if kind == "voice":
                data, mime, dur_ms = audio.tts(payload)
            else:
                data, mime, dur_ms = audio.music(payload)
            return _complete(jid, payload, [(data, mime, None, None, dur_ms)])
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "AUDIO_ERROR", "message": str(e)[:500]})
            return False

    if MODE == "cloud":
        import cloud_client as cloud
        # 用户云凭据（图片=OpenAI-compatible；视频=Replicate），经 internal 通道拉取
        cfg = {}
        uid = (job or {}).get("userId") or payload.get("userId")
        if uid:
            try:
                _, cfg = _req("GET", "/internal/users/%s/cloud-config" % uid)
            except Exception:
                cfg = {}
        try:
            if kind == "clip":
                vcfg = cfg.get("video") or {}
                outs = cloud.generate_motion_via_replicate(
                    payload, vcfg.get("apiKey") or "", vcfg.get("model") or "",
                    progress_fn=lambda p, st: _req(
                        "POST", "/internal/jobs/%s/progress" % jid,
                        {"progress": p, "stage": st}))
                media = [(o[0], o[1], o[2], o[3], o[4]) for o in outs]
            else:
                import cloud_image
                icfg = cfg.get("image") or {}
                base = icfg.get("baseUrl") or ""
                if base.startswith("http"):
                    pngs = cloud_image.generate(
                        payload.get("positive_prompt", ""), payload.get("params") or {}, icfg)
                    params = payload.get("params") or {}
                    w = int(params.get("width") or 1024)
                    h = int(params.get("height") or 1024)
                    media = [(pngs[0], "image/png", w, h, None)]
                else:
                    # BaseURL 留空 = Replicate 通道（apiKey 为 Replicate token）
                    if not (icfg.get("apiKey") or ""):
                        raise RuntimeError("未配置云图片凭据（图片云 API：填 BaseURL+Key 或 Replicate Key）")
                    params = payload.get("params") or {}
                    outs = cloud.replicate_image(
                        payload, icfg.get("apiKey") or "", icfg.get("model") or "",
                        progress_fn=lambda p, st: _req(
                            "POST", "/internal/jobs/%s/progress" % jid,
                            {"progress": p, "stage": st}))
                    media = [(o[0], "image/png",
                              int(params.get("width") or 1024), int(params.get("height") or 1024), None)
                             for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
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
    engine = "cloud" if MODE == "cloud" else "gpu"
    caps = {"engine": engine, "gpu": MODE, "workflows": ["stub_txt2img", "stub_motion"]}
    if MODE == "comfy":
        caps = {"engine": "gpu", "gpu": "comfy", "audio": True,
                "workflows": ["sdxl_txt2img", "wan_i2v", "ipadapter", "cosyvoice_tts", "ace_step_music"]}
    st, body = _req("POST", "/internal/nodes/register", {
        "name": NAME,
        "workspaceId": WORKSPACE or None,
        "capabilities": caps,
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
