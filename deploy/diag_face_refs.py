#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""人脸探针诊断：比对「送进 ComfyUI 的那几张参考图」与「出图结果」的人脸占比/身份相似度。

用途（2026-09-16 用户反馈「某主体的定妆图没被参考」时定位真因）：
  ① 逐张打印参考图的人脸统计（face_ratio 越大越像"标准角色设定图"；远小于 0.1 = 画面式远景/场景帧，
     这种图当定妆照喂 Edit 工作流时身份信号极弱 —— 看起来就像"没被参考"）；
  ② 给 --target 时，用目标图的 embedding 与结果图比对（best = 余弦相似度，
     ≥0.5 基本可认作同一人，<0.3 基本不是同一张脸）。

在 VPS 上跑（py3.6 兼容：不用 text=True）：
  python3 diag_face_refs.py http://180.127.11.167:31058 \
      --target /opt/weaveora/data/storage/.../portrait/cf25253c....jpg \
      /opt/weaveora/data/storage/.../portrait/08d7fb46....png \
      /opt/weaveora/data/storage/.../0a000003-.../10541d55....png
"""

import base64
import json
import os
import sys
import urllib.request

SUFFIX = {".png": ".png", ".jpg": ".jpg", ".jpeg": ".jpeg", ".webp": ".webp"}


def img_size(path):
    """不依赖 PIL 读图片尺寸（VPS 上没装 PIL）：PNG 直接读 IHDR；JPEG 扫 SOF。"""
    ext = os.path.splitext(path)[1].lower()
    try:
        with open(path, "rb") as f:
            head = f.read(32)
            if ext == ".png" and head[:8] == b"\x89PNG\r\n\x1a\n":
                return "%dx%d" % (int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big"))
            if ext in (".jpg", ".jpeg"):
                f.seek(2)
                while True:
                    b = f.read(1)
                    while b and b != b"\xff":
                        b = f.read(1)
                    marker = f.read(1)
                    while marker == b"\xff":
                        marker = f.read(1)
                    if marker in (b"\xd8", b"\xd9") or not marker:
                        continue
                    ln = int.from_bytes(f.read(2), "big")
                    if b"\xc0" <= marker <= b"\xcf" and marker not in (b"\xc4", b"\xc8", b"\xcc"):
                        data = f.read(5)
                        return "%dx%d" % (int.from_bytes(data[3:5], "big"), int.from_bytes(data[1:3], "big"))
                    f.seek(ln - 2, 1)
    except Exception:
        pass
    return "?"


def post(url, body, timeout=600):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def b64(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("ascii")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    target_path = None
    paths = []
    argv = sys.argv[2:]
    i = 0
    while i < len(argv):
        if argv[i] == "--target":
            target_path = argv[i + 1]
            i += 2
        else:
            paths.append(argv[i])
            i += 1

    target = None
    if target_path:
        emb = post(base + "/face/embed", {"image_b64": b64(target_path),
                                         "suffix": SUFFIX.get(os.path.splitext(target_path)[1].lower(), ".png")})
        target = emb.get("embedding")
        print("目标身份 = %s（face_px=%s）" % (os.path.basename(target_path), emb.get("face_px")))
        print("-" * 100)

    for p in paths:
        suffix = SUFFIX.get(os.path.splitext(p)[1].lower(), ".png")
        body = {"media_b64": b64(p), "suffix": suffix}
        if target:
            body["target_embedding"] = target
        try:
            r = post(base + "/face/probe", body)
        except Exception as e:
            print("%-46s ✗ %s" % (os.path.basename(p)[:44], e))
            continue
        size = img_size(p)
        print("%-46s %-10s 帧%s 命中%s 脸占比=%-7s 脸宽=%-7s 张嘴=%-6s 相似度=%s"
              % (os.path.basename(p)[:44], size,
                 r.get("total"), r.get("hits"),
                 ("%.3f" % r["face_ratio"]) if r.get("face_ratio") is not None else "-",
                 r.get("face_px"), r.get("mouth_open"),
                 ("%.3f" % r["best"]) if r.get("best") is not None else "（未给 --target）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
