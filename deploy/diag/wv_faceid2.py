#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_faceid2.py —— wv_faceid.py 的加诊断版：
  ① 参考图互相之间的 cos 矩阵（定妆照本身是否可分）
  ② 每张脸 bbox 的 宽/高/宽高比（脸型是否被拉扁/拉长）
用法：wv_faceid2.py --ref A=a.png --ref B=b.png --img t.png [--img t2.png]
"""
import argparse
import sys

import cv2
import numpy as np
from insightface.app import FaceAnalysis

DEFAULT_AUX = ("/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper"
               "/checkpoints/auxiliary")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--img", action="append", default=[])
    ap.add_argument("--ref", action="append", default=[])
    ap.add_argument("--aux", default=DEFAULT_AUX)
    ap.add_argument("--device", default="cpu", choices=["cpu", "cuda"])
    a = ap.parse_args()

    prov = ["CUDAExecutionProvider"] if a.device == "cuda" else ["CPUExecutionProvider"]
    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=a.aux, providers=prov)
    app.prepare(ctx_id=(0 if a.device == "cuda" else -1), det_size=(640, 640))

    def faces(p):
        img = cv2.imread(p)
        if img is None:
            raise SystemExit("读不到图：%s" % p)
        fs = app.get(img)
        return img.shape[1], img.shape[0], sorted(
            fs, key=lambda f: -float((f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1])))

    refs, emb, info = [], {}, {}
    for spec in a.ref:
        n, p = spec.split("=", 1)
        w, h, fs = faces(p)
        if not fs:
            print("[参考图] %-10s 检不到脸" % n)
            continue
        b = fs[0].bbox
        emb[n] = fs[0].normed_embedding
        refs.append(n)
        info[n] = (w, h, fs[0].bbox)
        print("[参考图] %-10s 图%dx%d 脸宽=%.0f 脸高=%.0f 宽高比=%.3f 占图高=%.1f%%"
              % (n, w, h, b[2] - b[0], b[3] - b[1], (b[2] - b[0]) / (b[3] - b[1]),
                 100.0 * (b[3] - b[1]) / h))

    if len(refs) > 1:
        print("-- 参考图互相 cos 矩阵（>0.35 = 两张定妆照本身就难分，模型必然串） --")
        print("            " + "".join("%-10s" % n for n in refs))
        for i in refs:
            row = "".join("%-10.3f" % float(np.dot(emb[i], emb[j])) for j in refs)
            print("%-12s%s" % (i, row))

    for p in a.img:
        w, h, fs = faces(p)
        print("== %s (%dx%d) 脸 %d 张 ==" % (p, w, h, len(fs)))
        for i, f in enumerate(fs, 1):
            x0, y0, x1, y1 = [float(v) for v in f.bbox]
            sims = {k: float(np.dot(f.normed_embedding, v)) for k, v in emb.items()}
            best = max(sims, key=sims.get)
            print("   #%d 宽=%3.0f 高=%3.0f 宽高比=%.3f x=%.2f y=%.2f 占图高=%.1f%% → %-8s %s"
                  % (i, x1 - x0, y1 - y0, (x1 - x0) / (y1 - y0), (x0 + x1) / 2 / w,
                     (y0 + y1) / 2 / h, 100.0 * (y1 - y0) / h, best, {k: round(v, 3) for k, v in sims.items()}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
