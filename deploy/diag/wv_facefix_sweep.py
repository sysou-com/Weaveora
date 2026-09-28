#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_facefix_sweep.py —— 方案2 参数扫描：denoise 要多大才真的把身份压进去？

为什么要单独扫（2026-09-25 第一版结果）：
  12:50 那张关键帧跑 denoise=0.55 逐脸重绘，三张脸在**最终 1664 图**上的 cos 只从
  -0.003/0.170/0.206 提到 0.186/0.263/0.292（都 <0.35）。两个疑点：
   ① 0.55 保留了 45% 的原像素（那 45% 恰恰是"没身份"的），可能压不动；
   ② **在 65–85px 的小脸上量 ArcFace 本身就是噪声**，改进可能被量没了。
  所以本脚本：同一张脸 → 只扫 denoise；**cos 在 1024px 的重绘图上量**（那才是模型真正画出来的脸），
  同时对"放大后的输入裁切"也量一次当基线（区别"模型改没改"和"我们量不出来"）。

用法：
  /opt/weaveora/ComfyUI/venv/bin/python wv_facefix_sweep.py \
      --img /tmp/wvf/s4_1250.png --face 1 --who 宝玉 \
      --ref 宝玉=/tmp/wvf/ref_baoyu.png --ref 可卿=/tmp/wvf/ref_keqing.png --ref 警幻=/tmp/wvf/ref_jinghuan.png \
      --denoise 0.55,0.75,0.9 [--px 1024] [--expand 2.0] [--dump /tmp/wvf/sweep]
"""
import argparse
import os
import sys
import time

import cv2
import numpy as np
from insightface.app import FaceAnalysis

sys.path.insert(0, "/opt/weaveora/diag")
import wv_facefix as F  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--img", required=True)
    ap.add_argument("--ref", action="append", default=[])
    ap.add_argument("--face", type=int, default=1, help="第几张脸（按 x 从左到右，1 起）")
    ap.add_argument("--who", required=True, help="这张脸用谁的参考图")
    ap.add_argument("--denoise", default="0.55,0.75,0.9")
    ap.add_argument("--px", type=int, default=1024)
    ap.add_argument("--expand", type=float, default=2.0)
    ap.add_argument("--seed", type=int, default=20260925)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--dump", default="/tmp/wvf/sweep")
    a = ap.parse_args()
    os.makedirs(a.dump, exist_ok=True)

    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=F.AUX,
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))
    emb, ref_path = {}, {}
    for spec in a.ref:
        n, p = spec.split("=", 1)
        img = cv2.imread(p)
        fs = app.get(img)
        emb[n] = fs[0].normed_embedding
        ref_path[n] = p
    ref = ref_path[a.who]

    canvas = cv2.imread(a.img)
    H, W = canvas.shape[:2]
    fs = sorted(app.get(canvas), key=lambda f: float(f.bbox[0]))
    f = fs[a.face - 1]
    x0, y0, x1, y1 = [float(v) for v in f.bbox]
    side = int(round(max(x1 - x0, y1 - y0) * a.expand))
    side = max(64, min(side, W, H))
    x = max(0, min(int(round((x0 + x1) / 2 - side / 2)), W - side))
    y = max(0, min(int(round((y0 + y1) / 2 - side / 2)), H - side))
    crop = canvas[y:y + side, x:x + side]
    big = cv2.resize(crop, (a.px, a.px), interpolation=cv2.INTER_LANCZOS4)
    cv2.imwrite(os.path.join(a.dump, "input_%dpx.png" % a.px), big)

    def cos_on(tag, img):
        g = app.get(img)
        if not g:
            print("   %-18s 检不到脸" % tag)
            return None
        e = g[0].normed_embedding
        sims = {k: round(float(np.dot(e, v)), 3) for k, v in emb.items()}
        print("   %-18s → %s" % (tag, sims))
        return sims

    print("== 脸 #%d（图内宽=%.0fpx，裁切边长=%dpx → 放大到 %dpx）认领=%s ==" % (a.face, x1 - x0, side, a.px, a.who))
    print("-- 基线：把裁切直接放大后的脸（= 模型看到的输入，未重绘）--")
    cos_on("input_%dpx" % a.px, big)
    for d in [float(v) for v in a.denoise.split(",")]:
        t0 = time.time()
        out, fn = F.run_face(big, ref, a.px, d, a.seed + a.face, a.steps, 4.0)
        cv2.imwrite(os.path.join(a.dump, "denoise_%s.png" % d), out)
        print("-- denoise=%.2f（%.0fs，%s）--" % (d, time.time() - t0, fn))
        cos_on("render_%s" % d, out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
