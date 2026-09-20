#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_stats.py —— 关键帧对比统计：尺寸/亮度/上下黑带 + 人脸数/脸像素/与各定妆照 cos
用法：/root/venv/bin/python wv_stats.py <ref1.png> <ref2.png> [...] -- <target1.png> <target2.png> [...]
"""
import sys
import numpy as np
from PIL import Image
from insightface.app import FaceAnalysis

app = FaceAnalysis(name="buffalo_l", root="/tmp/diag/fa", providers=["CPUExecutionProvider"])
app.prepare(ctx_id=-1, det_size=(640, 640))


def embs(path):
    import cv2
    img = cv2.imread(path)
    if img is None:
        img = np.array(Image.open(path).convert("RGB"))[:, :, ::-1]
    return app.get(img)


def main():
    argv = sys.argv[1:]
    cut = argv.index("--")
    ref_paths, tgt_paths = argv[:cut], argv[cut + 1:]
    refs = {}
    for p in ref_paths:
        fs = embs(p)
        if fs:
            refs[p.split("/")[-1]] = fs[0].normed_embedding
    print("定妆照参考：", ", ".join(refs.keys()))
    for p in tgt_paths:
        im = Image.open(p)
        g = np.asarray(im.convert("L"), dtype=np.float32)
        rmax = g.max(axis=1)
        top = 0
        for v in rmax:
            if v <= 1: top += 1
            else: break
        bot = 0
        for v in rmax[::-1]:
            if v <= 1: bot += 1
            else: break
        fs = embs(p)
        print("--- %s  %s  λ=%.1f 上黑=%d 下黑=%d 脸=%d" % (p.split("/")[-1], im.size, g.mean(), top, bot, len(fs)))
        for f in sorted(fs, key=lambda x: -min(x.bbox[2] - x.bbox[0], x.bbox[3] - x.bbox[1]))[:6]:
            x1, y1, x2, y2 = f.bbox
            px = int(min(x2 - x1, y2 - y1))
            best, bs = "?", -2
            if f.normed_embedding is not None:
                for n, e in refs.items():
                    c = float(np.dot(f.normed_embedding, e))
                    if c > bs:
                        best, bs = n[:8], c
            print("      px=%4d @(%.2f,%.2f) 最像 %s cos=%.3f" % (px, (x1 + x2) / 2 / im.width, (y1 + y2) / 2 / im.height, best, bs))


if __name__ == "__main__":
    main()
