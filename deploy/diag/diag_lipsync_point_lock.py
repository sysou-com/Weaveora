#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P9 核对：对口型「点选锁定 + 调试绿框坐标系」离线复现（**纯 CPU，不占 GPU**）。

为什么离线做：worker 在 API 机、节点在 GPU 机，线上只能靠产物上的绿框回看；而绿框
坐标系 2026-09-22 才修对（`_crop_box_to_frame_quad`）。本脚本用**节点自己的代码**
（FaceDetector + ImageProcessor.affine_transform + _crop_box_to_frame_quad）在真实底片上
复现「点选 → 选脸 → 裁剪 box/仿射」全过程，然后把三件事画进同一张图：

  · 蓝框 = 画面里**所有**通过上游过滤的候选脸（含绘制序号、脸宽、det_score）
  · 红点 = 用户点选坐标（faceHints，归一化）
  · 黄四边形 = **修好后**的调试框（仿射逆变换把裁剪矩形映射回原帧）
  · 灰虚线 = **修之前**的画法（[0,0,裁剪宽,裁剪高] 当原帧坐标 → 恒贴左上角）

顺带打印「点到各候选脸中心的距离（按画面对角线归一化）」与 POINT_MAX_DIST 阈值 ——
这组数字就是 H1（点选是否跑偏）的直接判据。

用法（GPU 盒上）：
  python3 diag_lipsync_point_lock.py <video.mp4> <outdir> '<hints_json>'
  例：python3 diag_lipsync_point_lock.py a.mp4 /opt/weaveora/diag_out '{"可卿":[0.5457,0.439],"宝玉":[0.5099,0.3792]}'
"""
import json
import os
import sys

import cv2
import numpy as np
import torch

NODE_ROOT = "/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper"
if NODE_ROOT not in sys.path:
    sys.path.insert(0, NODE_ROOT)

from latentsync.utils.image_processor import ImageProcessor                      # noqa: E402
from latentsync.utils import face_detector as fdm                                # noqa: E402
from latentsync.pipelines.lipsync_pipeline import _crop_box_to_frame_quad        # noqa: E402


def make_cpu_detector(point):
    """FaceDetector 的 __init__ 硬编码 CUDA provider 且 cuda_to_int('cpu') 会抛错，
    所以这里手工组装一个 **CPU** 实例（纯几何/识别，结果与 GPU 上一致，只是慢一点）。"""
    fd = fdm.FaceDetector.__new__(fdm.FaceDetector)
    fd.target = None
    fd.point = (float(point[0]), float(point[1]))
    fd._box = None
    fd._lmk = None
    fd._emb = None
    fd._lost = 0
    fd._reject_streak = 0
    fd.last_driven = False
    fd.last_reason = ""
    fd.stats = {}
    from insightface.app import FaceAnalysis
    fd.app = FaceAnalysis(allowed_modules=["detection", "landmark_2d_106"],
                          root=fdm.INSIGHTFACE_AUX_ROOT,
                          providers=["CPUExecutionProvider"])
    fd.app.prepare(ctx_id=-1, det_size=(fdm.INSIGHTFACE_DETECT_SIZE, fdm.INSIGHTFACE_DETECT_SIZE))
    return fd


def read_frames(path):
    cap = cv2.VideoCapture(path)
    out = []
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        out.append(fr)
    cap.release()
    return out


def annotate(frame, hint_name, hint_xy, ip, det_all, log):
    f = np.ascontiguousarray(frame.copy())
    fh, fw = f.shape[0], f.shape[1]
    diag = float(np.hypot(fw, fh))
    hx, hy = int(round(hint_xy[0] * fw)), int(round(hint_xy[1] * fh))

    # ① 所有候选脸
    cands = det_all._candidates(frame)
    log["candidates"] = []
    for i, c in enumerate(cands):
        x1, y1, x2, y2 = [int(v) for v in c.bbox]
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        d = float(np.hypot(cx - hx, cy - hy)) / diag
        log["candidates"].append({
            "idx": i, "bbox": [x1, y1, x2, y2], "w": x2 - x1, "h": y2 - y1,
            "center": [round(cx, 1), round(cy, 1)], "det": round(float(c.det_score), 3),
            "dist_to_point": round(d, 4), "dist_px": round(float(np.hypot(cx - hx, cy - hy)), 1),
        })
        cv2.rectangle(f, (x1, y1), (x2, y2), (255, 120, 0), 2)
        cv2.putText(f, "#%d w=%d d=%.2f" % (i, x2 - x1, d), (x1, max(14, y1 - 6)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 120, 0), 2, cv2.LINE_AA)
        cv2.circle(f, (int(cx), int(cy)), 3, (255, 120, 0), -1)

    # ② 点选命中（节点真实判据：中心距 / 画面对角线 ≤ POINT_MAX_DIST）
    picked = fdm._pick_by_point(cands, (hint_xy[0], hint_xy[1]), fw, fh)
    log["point_max_dist"] = fdm.POINT_MAX_DIST
    log["picked"] = None
    if picked is not None:
        x1, y1, x2, y2 = [int(v) for v in picked.bbox]
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        log["picked"] = {"bbox": [x1, y1, x2, y2], "w": x2 - x1,
                         "dist": round(float(np.hypot(cx - hx, cy - hy)) / diag, 4)}
        cv2.rectangle(f, (x1, y1), (x2, y2), (0, 220, 0), 3)
        cv2.line(f, (hx, hy), (int(cx), int(cy)), (0, 220, 0), 2)
    else:
        log["picked_note"] = "点太远（>%.2f）→ 不按点选播种，退到轨迹/定妆照/最大脸" % fdm.POINT_MAX_DIST

    # ③ 真实管线路径：点选 → 裁剪 → box + affine → 反算四边形（修好的画法）
    ip.face_detector = make_cpu_detector((hint_xy[0], hint_xy[1]))
    face, box, affine = ip.affine_transform(frame)
    quad = _crop_box_to_frame_quad(box, affine)
    log["crop_box"] = [int(v) for v in box]
    log["crop_size"] = [int(face.shape[1]), int(face.shape[0])]
    if quad is not None:
        q = np.round(quad).astype(np.int32)
        log["quad_bbox_norm"] = [round(float(min(q[:, 0])) / fw, 3), round(float(min(q[:, 1])) / fh, 3),
                                 round(float(max(q[:, 0])) / fw, 3), round(float(max(q[:, 1])) / fh, 3)]
        cv2.polylines(f, [q], True, (0, 235, 235), 2)
    # ④ 修之前的画法（对照）
    cv2.rectangle(f, (0, 0), (int(box[2]), int(box[3])), (130, 130, 130), 1)

    cv2.circle(f, (hx, hy), 6, (0, 0, 255), -1)
    cv2.drawMarker(f, (hx, hy), (0, 0, 255), cv2.MARKER_CROSS, 18, 2)
    cv2.putText(f, "%s  hint=(%.3f,%.3f)%s" % (hint_name, hint_xy[0], hint_xy[1],
                                               "  [点选命中]" if picked is not None else "  [点选落空]"),
                (8, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2, cv2.LINE_AA)
    cv2.putText(f, "green-fixed quad | blue=candidates | gray=OLD buggy box",
                (8, fh - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 235, 235), 1, cv2.LINE_AA)
    return f


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    video, outdir, hints_json = sys.argv[1], sys.argv[2], sys.argv[3]
    hints = json.loads(hints_json)
    os.makedirs(outdir, exist_ok=True)
    frames = read_frames(video)
    print("[p9] 底片 %s：%d 帧 %dx%d" % (video, len(frames), frames[0].shape[1], frames[0].shape[0]), flush=True)
    det_all = make_cpu_detector((0.5, 0.5))   # 只用来枚举候选（point 不影响 _candidates）
    sel = sorted(set([0, len(frames) // 2, len(frames) - 1]))
    summary = []
    for fi in sel:
        for name, xy in hints.items():
            ip = ImageProcessor(resolution=512, device="cpu")   # 走真实 mask.png（与线上一致）
            log = {"frame": fi, "hint": name, "xy": xy}
            img = annotate(frames[fi], name, xy, ip, det_all, log)
            log["stats"] = dict(ip.face_detector.stats)
            p = os.path.join(outdir, "p9_f%03d_%s.png" % (fi, name))
            cv2.imwrite(p, img)
            log["png"] = p
            summary.append(log)
            print("[p9] %s -> %s" % (json.dumps(log, ensure_ascii=False), p), flush=True)
    sp = os.path.join(outdir, "p9_summary.json")
    with open(sp, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=1)
    print("[p9] 汇总 %s" % sp, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
