#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LoRA A/B 的**画质对账**（跑在 GPU 盒子上，venv 里有 cv2/numpy/insightface）。

为什么必须单独量这步（2026-09-19 用户提问："LoRA 不是影响画质/会闪烁吗？"）：
  运动链路当年**实测**过同一类现象 —— 零蒸馏砍到 6 步就"欠采样变糊"（高频能量 1.35 vs 2.47），
  挂上 4 步蒸馏 LoRA 则观感"冲"。所以「快了多少」必须和「高频细节掉了多少 / 色调漂了多少」
  一起看，而且要用**当年同一把尺子**，否则无法和已有结论对照。

量什么：
  1. 高频能量 = Laplacian 方差（同尺寸同内容可直接比；这就是当年 motion 用的"清晰度"指标）
  2. 高频占比 = 高通（原图 - 高斯模糊）能量 / 原图能量  → 对亮度差异不敏感，跨档更可比
  3. 平均亮度 / 直方图 L1 距离（vs 基线档）→ 对应"批次色调漂移"（用户否掉按镜分档的理由）
  4. 人脸：张数、最大脸宽、与定妆照的相似度（身份有没有被蒸馏掉）

用法（盒子）：
  /opt/weaveora/ComfyUI/venv/bin/python diag_lora_ab_measure.py /opt/weaveora/_xfer/lora_ab \
      --portrait /tmp/portrait_jh_1942.png
"""

import argparse
import glob
import os
import sys

import cv2
import numpy as np

AUX = "/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/auxiliary"


def high_freq(img_bgr):
    """返回 (Laplacian 方差, 高频能量占比)。"""
    g = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
    lap = cv2.Laplacian(g, cv2.CV_32F)
    lap_var = float(lap.var())
    blur = cv2.GaussianBlur(g, (0, 0), 2.0)
    hi = g - blur
    denom = float((g ** 2).sum()) or 1.0
    return lap_var, float((hi ** 2).sum()) / denom


def hist_l1(a, b):
    ha = cv2.calcHist([cv2.cvtColor(a, cv2.COLOR_BGR2GRAY)], [0], None, [64], [0, 256])
    hb = cv2.calcHist([cv2.cvtColor(b, cv2.COLOR_BGR2GRAY)], [0], None, [64], [0, 256])
    cv2.normalize(ha, ha)
    cv2.normalize(hb, hb)
    return float(np.abs(ha - hb).sum() / 2.0)      # 0=完全相同, 1=完全不同


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", help="A/B 输出目录（*.png）")
    ap.add_argument("--baseline", default="", help="基线文件名（默认取文件名里 steps 最大且无 lora 的那个）")
    ap.add_argument("--portrait", default="", help="该主体的定妆照，用来量身份相似度（可选）")
    a = ap.parse_args()

    files = sorted(glob.glob(os.path.join(a.dir, "*.png")))
    if not files:
        print("目录里没有 png：%s" % a.dir)
        return 1
    base = a.baseline
    if not base:
        cand = [f for f in files if "lora" not in os.path.basename(f).lower()]
        base = max(cand, key=lambda f: int("".join(c for c in os.path.basename(f) if c.isdigit()) or 0)) if cand else files[0]
    else:
        base = os.path.join(a.dir, base)

    ref = cv2.imread(base)
    print("基线：%s\n" % os.path.basename(base))
    print("%-34s %8s %8s %9s %9s %8s" % ("文件", "体积MB", "亮度", "高频能量", "高频占比", "直方图距"))
    rows = []
    for f in files:
        im = cv2.imread(f)
        if im is None:
            continue
        if im.shape != ref.shape:
            im = cv2.resize(im, (ref.shape[1], ref.shape[0]))
        lap, ratio = high_freq(im)
        bright = float(cv2.cvtColor(im, cv2.COLOR_BGR2GRAY).mean())
        d = 0.0 if os.path.abspath(f) == os.path.abspath(base) else hist_l1(ref, im)
        mb = os.path.getsize(f) / 1048576.0
        print("%-34s %8.2f %8.1f %9.2f %9.5f %8.3f"
              % (os.path.basename(f)[:34], mb, bright, lap, ratio, d))
        rows.append((f, lap, ratio, bright, d))

    if len(rows) > 1:
        b_lap, b_ratio, b_bright = rows[0][1], rows[0][2], rows[0][3]
        print("\n相对基线的变化（关键看**高频能量↓多少**=清晰度损失；%d%% 以上就要放大图细看）：" % 10)
        for f, lap, ratio, bright, d in rows[1:]:
            print("  %-30s 高频 %+6.1f%%   占比 %+6.1f%%   亮度 %+5.1f%%"
                  % (os.path.basename(f)[:30],
                     100.0 * (lap / b_lap - 1) if b_lap else 0.0,
                     100.0 * (ratio / b_ratio - 1) if b_ratio else 0.0,
                     100.0 * (bright / b_bright - 1) if b_bright else 0.0))

    if a.portrait and os.path.exists(a.portrait):
        try:
            from insightface.app import FaceAnalysis
            app = FaceAnalysis(name="buffalo_l", root=AUX, providers=["CPUExecutionProvider"])
            app.prepare(ctx_id=-1, det_size=(640, 640))
            pf = app.get(cv2.imread(a.portrait))
            pe = max(pf, key=lambda x: (x.bbox[2] - x.bbox[0])).normed_embedding if pf else None
            print("\n人脸（最大脸宽 / 与定妆照相似度；相似度量级对比比绝对值可靠）：")
            for f in files:
                fs = app.get(cv2.imread(f))
                if not fs:
                    print("  %-34s 未检出人脸" % os.path.basename(f)[:34])
                    continue
                big = max(fs, key=lambda x: (x.bbox[2] - x.bbox[0]) * (x.bbox[3] - x.bbox[1]))
                w = int(big.bbox[2] - big.bbox[0])
                sim = float(np.dot(pe, big.normed_embedding)) if pe is not None else float("nan")
                print("  %-34s 脸数=%d 最大脸宽=%3dpx 相似度=%.3f" % (os.path.basename(f)[:34], len(fs), w, sim))
        except Exception as e:
            print("人脸量测跳过：%s" % e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
