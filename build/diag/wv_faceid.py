#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_faceid.py —— 关键帧「谁在哪」客观判定（多主体出图的身份/站位验收）

用法（在 GPU 盒子上跑，用带 insightface 的解释器）：
    /opt/weaveora/ComfyUI/venv/bin/python wv_faceid.py --img keyframe.png \
        --ref 宝玉=baoyu.png --ref 可卿=keqing.png --ref 警幻=jinghuan.png

输出：每张检出人脸的脸宽 / 归一化中心 / 与每张参考图的 cos 相似度 / 最近匹配。

为什么要有这个脚本（2026-09-21）：
  第 4 镜「关键帧少了一个人 / 警幻和可卿位置翻了」一直是靠肉眼争论。用检测+识别把它变成数字后，
  立刻看出真因：**image3 的警幻从未出现在画面里**（三版最高 cos 仅 0.13–0.18 = “不像”），
  而第 3 张脸最像**可卿**（0.38）→ 也就是“画了两个可卿”。位置也偏中间（宝玉 x=0.39 而非 0.17）。
  cos 参考：>0.5 同一人；0.35–0.5 偏弱（可疑）；<0.3 基本不是同一人。

⚠️ 模型 root 的坑（本文件就是踩坑记录）：
  face_server 用的 AUX_ROOT = $WEAVEORA_LATENTSYNC_DIR/checkpoints/auxiliary
  —— 完整 buffalo_l（含识别模型 w600k_r50.onnx）在
  `/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/auxiliary`。
  别用 `/opt/weaveora/models/face_aux`：那里只有 det_10g.onnx（只有检测、没有识别），
  会得到 `normed_embedding=None` → 只能“数脸”不能“认人”。
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
    ap.add_argument("--img", required=True, help="待判定的关键帧/出图")
    ap.add_argument("--ref", action="append", default=[],
                    help="参考图，格式 名字=路径（可重复；顺序=送入模型的槽位顺序）")
    ap.add_argument("--aux", default=DEFAULT_AUX, help="insightface 模型 root")
    ap.add_argument("--device", default="cpu", choices=["cpu", "cuda"])
    args = ap.parse_args()

    if not args.ref:
        print("!! 至少给一个 --ref 名字=路径", file=sys.stderr)
        return 2

    providers = ["CUDAExecutionProvider"] if args.device == "cuda" else ["CPUExecutionProvider"]
    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=args.aux, providers=providers)
    app.prepare(ctx_id=(0 if args.device == "cuda" else -1), det_size=(640, 640))

    def faces(path):
        img = cv2.imread(path)
        if img is None:
            raise SystemExit("读不到图：%s" % path)
        fs = app.get(img)
        return sorted(fs, key=lambda f: -float((f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1])))

    refs, emb = [], {}
    for spec in args.ref:
        if "=" not in spec:
            print("!! --ref 需要 名字=路径 形式：%s" % spec, file=sys.stderr)
            return 2
        name, path = spec.split("=", 1)
        fs = faces(path)
        if not fs:
            print("[参考图] %-12s 检不到脸（这张不可能是有效定妆照）" % name)
            continue
        if fs[0].normed_embedding is None:
            print("!! %s 没有 embedding —— aux root 里缺 w600k_r50.onnx？当前 root=%s" % (name, args.aux),
                  file=sys.stderr)
            return 3
        emb[name] = fs[0].normed_embedding
        refs.append(name)
        print("[参考图] %-12s 脸宽=%.0f" % (name, float(fs[0].bbox[2] - fs[0].bbox[0])))

    img = cv2.imread(args.img)
    h, w = img.shape[:2]
    fs = faces(args.img)
    print("== 待判定：%s (%dx%d) 检出人脸 %d 张 ==" % (args.img, w, h, len(fs)))
    if not fs:
        print("   （没有人脸：要么真的没人，要么脸太小/侧转）")
    for i, f in enumerate(fs, 1):
        x0, y0, x1, y1 = [float(v) for v in f.bbox]
        sims = {k: float(np.dot(f.normed_embedding, v)) for k, v in emb.items()}
        best = max(sims, key=sims.get) if sims else "-"
        print("   #%d 脸宽=%3.0f x中心=%.2f y中心=%.2f → 最像 %-10s %s"
              % (i, x1 - x0, (x0 + x1) / 2 / w, (y0 + y1) / 2 / h, best,
                 {k: round(v, 3) for k, v in sims.items()}))
    # 缺席检查：某张参考图在画面里完全找不到（最高 cos 低于阈值）
    for name in refs:
        top = max([float(np.dot(f.normed_embedding, emb[name])) for f in fs], default=-1.0)
        if top < 0.30:
            print("   ⚠ 缺席/不像：参考图「%s」在画面里最高 cos 只有 %.3f —— 这个角色很可能没被画出来" % (name, top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
