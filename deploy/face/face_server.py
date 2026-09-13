#!/usr/bin/env python3
"""Weaveora 人脸服务（GPU 机器上常驻，默认 :8093）。

为什么单独抽一个服务：
  worker 做人脸预检/锁人（对口型「谁在哪张脸」）时，过去只能在**自己进程里**起子进程跑
  insightface —— 一旦 worker 不在 GPU 机器上、或想把人脸算力集中到新买的 GPU 机器，
  就没有统一切换点。抽成 HTTP 服务后，「生成引擎配置 → 服务地址 → 人脸」填这个地址即可。

接口（都是 POST + JSON，UTF-8）：
  GET  /health                     → {"ok":true,"device":"cpu|cuda"}
  POST /face/probe                 → 抽样若干帧看有没有脸 / 最像目标特征多少
       body: {"media_b64": "<视频或图片字节 base64>", "target_embedding": [512 floats]?}
       resp: {"hits": 6, "total": 6, "best": 0.6537}
  POST /face/embed                 → 取一张图里最大的人脸特征（给「锁人」做参考）
       body: {"image_b64": "<图片字节 base64>"}
       resp: {"embedding": [512 floats], "face_px": 321}

依赖：insightface + onnxruntime(+可选 GPU) + opencv + numpy（GPU 机器上装 ComfyUI 时已有）。
启动：python3 face_server.py [--port 8093] [--device cpu|cuda] [--det-size 512]
"""
import argparse
import base64
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

NODE_DIR = os.environ.get("WEAVEORA_LATENTSYNC_DIR") or \
    os.path.join("D:\\", "ComfyUI", "custom_nodes", "ComfyUI-LatentSyncWrapper")
if os.path.isdir(NODE_DIR):
    sys.path.insert(0, NODE_DIR)
AUX_ROOT = os.path.join(NODE_DIR, "checkpoints", "auxiliary")

_APP = None
_DEVICE = "cpu"
_LOCK = threading.Lock()


def _providers(device: str):
    if device == "cuda":
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return ["CPUExecutionProvider"]


def _app(need_recognition: bool):
    """懒加载 FaceAnalysis（需要识别时才加载 recognition，省显存）。"""
    from insightface.app import FaceAnalysis

    global _APP
    with _LOCK:
        if _APP is None:
            mods = ["detection", "landmark_2d_106"]
            _APP = {"base": FaceAnalysis(allowed_modules=mods, root=AUX_ROOT, providers=_providers(_DEVICE))}
            _APP["base"].prepare(ctx_id=(0 if _DEVICE == "cuda" else -1), det_size=(512, 512))
            _APP["rec"] = None
        if need_recognition and _APP.get("rec") is None:
            _APP["rec"] = FaceAnalysis(allowed_modules=["detection", "recognition"],
                                       root=AUX_ROOT, providers=_providers(_DEVICE))
            _APP["rec"].prepare(ctx_id=(0 if _DEVICE == "cuda" else -1), det_size=(512, 512))
    return _APP["rec"] if need_recognition else _APP["base"]


def _read_media(b64: str, suffix: str):
    """字节 → ndarray 帧列表（视频抽 6 帧；图片 1 帧）。"""
    import cv2
    raw = base64.b64decode(b64)
    d = tempfile.mkdtemp(prefix="wv_face_")
    fp = os.path.join(d, "in" + suffix)
    with open(fp, "wb") as fh:
        fh.write(raw)
    frames = []
    if suffix.lower() in (".png", ".jpg", ".jpeg", ".webp"):
        img = cv2.imdecode(np.fromfile(fp, dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is not None:
            frames.append(img)
    else:
        cap = cv2.VideoCapture(fp)
        n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        idx = sorted({int(i * (n - 1) / 5) for i in range(6)}) if n > 1 else [0]
        for i in idx:
            cap.set(cv2.CAP_PROP_POS_FRAMES, i)
            ok, fr = cap.read()
            if ok:
                frames.append(fr)
        cap.release()
    return frames


def probe(media_b64: str, suffix: str, target=None):
    """抽样帧的人脸统计：(hits, total, best_sim)。"""
    need_rec = target is not None
    app = _app(need_rec)
    frames = _read_media(media_b64, suffix)
    hits, total, best = 0, 0, None
    for fr in frames:
        total += 1
        faces = app.get(fr)
        if len(faces) > 0:
            hits += 1
        if need_rec:
            t = np.asarray(target, dtype=np.float32).reshape(-1)
            t = t / (float(np.linalg.norm(t)) or 1.0)
            for f in faces:
                emb = getattr(f, "normed_embedding", None)
                if emb is None:
                    continue
                v = np.asarray(emb, dtype=np.float32)
                v = v / (float(np.linalg.norm(v)) or 1.0)
                s = float(np.dot(v, t))
                if best is None or s > best:
                    best = s
    return hits, total, best


def embed(image_b64: str, suffix: str):
    """取图里最大脸的特征（给「锁人」当参考）。"""
    app = _app(True)
    frames = _read_media(image_b64, suffix)
    if not frames:
        return None, 0
    faces = app.get(frames[0])
    if not faces:
        return None, 0
    f = max(faces, key=lambda x: (x.bbox[2] - x.bbox[0]) * (x.bbox[3] - x.bbox[1]))
    v = np.asarray(f.normed_embedding, dtype=np.float32)
    v = v / (float(np.linalg.norm(v)) or 1.0)
    return [float(x) for x in v], float(f.bbox[2] - f.bbox[0])


class Handler(BaseHTTPRequestHandler):
    server_version = "weaveora-face/1.0"

    def log_message(self, fmt, *args):   # 静默（避免刷屏）
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            return self._json(200, {"ok": True, "device": _DEVICE})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except Exception as e:
            return self._json(400, {"error": "bad json: %s" % e})
        try:
            if self.path.startswith("/face/probe"):
                media = body.get("media_b64") or ""
                suffix = body.get("suffix") or ".mp4"
                hits, total, best = probe(media, suffix, body.get("target_embedding"))
                return self._json(200, {"hits": hits, "total": total, "best": best})
            if self.path.startswith("/face/embed"):
                emb, px = embed(body.get("image_b64") or "", body.get("suffix") or ".png")
                return self._json(200, {"embedding": emb, "face_px": px})
        except Exception as e:
            return self._json(500, {"error": str(e)[:300]})
        return self._json(404, {"error": "not found"})


def main():
    global _DEVICE
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=int(os.environ.get("WEAVEORA_FACE_PORT", "8093")))
    ap.add_argument("--device", default=os.environ.get("WEAVEORA_FACE_DEVICE", "cpu"))
    ap.add_argument("--warm", action="store_true", help="启动时预加载检测器（首次请求更快）")
    a = ap.parse_args()
    _DEVICE = "cuda" if str(a.device).lower().startswith("cuda") else "cpu"
    if a.warm:
        try:
            _app(False)
            print("[face] 检测器已预加载", flush=True)
        except Exception as e:
            print("[face] 预加载失败（首次请求会再试）：%s" % e, flush=True)
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    print("[face] listening :%d  device=%s  aux=%s" % (a.port, _DEVICE, AUX_ROOT), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
