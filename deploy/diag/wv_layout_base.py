#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_layout_base.py —— 方案1-C 概念验证：**「构图底图」通路**（在 GPU 盒上跑）

要验证的假设（2026-09-25 用户裁定「先试方案1的C」）：
  第4镜身份绑不住的直接原因是「三张脸各占画面 10–13% 高、且 3 路参考挤一条 conditioning」。
  而**文字管不了构图**（模板自己禁了坐标/百分比；实测各种写法无效）。
  ⇒ 换思路：**用图像控几何、用参考控身份、文字只管动作/环境**。

做法：
  1) 取"主脸角色"的定妆照当**构图底图**：按目标构图把它仿射变换到 1664×928 画布上
     —— 令这张脸**正好**落在 (face-at) 且**占画面高度 face-ratio**（默认 0.22 ≈ 现在 10–13% 的两倍）
  2) 底图之外用暗色渐变铺满（避免白底摄影棚背景硬边泄漏）
  3) 送 ComfyUI：**img2img（SplitSigmasDenoise denoise=0.7）** 底图=构图底图
     + **1–2 条 ReferenceLatent**（只给"需要看清脸"的其他人）
  4) 量：输出里每张脸的**占帧高**与**身份 cos**（与 A0 基线 7–9% / 0.126/0.110/0.293 比）

用法（盒上）：
  /opt/weaveora/ComfyUI/venv/bin/python wv_layout_base.py \
     --key 可卿=/tmp/wvf/ref_keqing.png \
     --ref 宝玉=/tmp/wvf/ref_baoyu.png --ref 警幻=/tmp/wvf/ref_jinghuan.png \
     --canvas 1664x928 --face-ratio 0.22 --face-at 0.62,0.33 --denoise 0.7 \
     --prompt-file /tmp/wvf/layout_prompt.txt --dump /tmp/wvf/layout --tag C1
"""
import argparse
import json
import os
import sys
import time

import cv2
import numpy as np
from insightface.app import FaceAnalysis

sys.path.insert(0, "/opt/weaveora/diag")
import wv_facefix as F  # noqa: E402  （复用：上传/提交/轮询/取图 + AUX + 羽化）


def build_graph(n_ref, w, h, steps, guidance, denoise, seed):
    """img2img（底图=构图底图，不做缩放）+ n_ref 条 ReferenceLatent 串链。"""
    g = {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux2_dev_fp8mixed.safetensors",
                                                     "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "mistral_3_small_flux2_fp8.safetensors",
                                                     "type": "flux2", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "full_encoder_small_decoder.safetensors"}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": ""}},
        "7": {"class_type": "FluxGuidance", "inputs": {"conditioning": ["6", 0], "guidance": guidance}},
        "10": {"class_type": "LoadImage", "inputs": {"image": ""}},          # 构图底图（已是目标尺寸）
        "12": {"class_type": "VAEEncode", "inputs": {"pixels": ["10", 0], "vae": ["3", 0]}},
        "41": {"class_type": "Flux2Scheduler", "inputs": {"steps": steps, "width": w, "height": h}},
        "42": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "43": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "44": {"class_type": "SplitSigmasDenoise", "inputs": {"sigmas": ["41", 0], "denoise": denoise}},
        "45": {"class_type": "SamplerCustomAdvanced",
               "inputs": {"noise": ["43", 0], "guider": ["40", 0], "sampler": ["42", 0],
                          "sigmas": ["44", 1], "latent_image": ["12", 0]}},
        "46": {"class_type": "VAEDecode", "inputs": {"samples": ["45", 0], "vae": ["3", 0]}},
        "47": {"class_type": "SaveImage", "inputs": {"images": ["46", 0], "filename_prefix": "weaveora_layout"}},
    }
    prev = ["7", 0]
    for i in range(n_ref):
        li, si, ei, ri = str(20 + i * 4), str(21 + i * 4), str(22 + i * 4), str(30 + i)
        g[li] = {"class_type": "LoadImage", "inputs": {"image": ""}}
        g[si] = {"class_type": "ImageScaleToTotalPixels",
                 "inputs": {"image": [li, 0], "upscale_method": "area", "megapixels": 1.0, "resolution_steps": 1}}
        g[ei] = {"class_type": "VAEEncode", "inputs": {"pixels": [si, 0], "vae": ["3", 0]}}
        g[ri] = {"class_type": "ReferenceLatent", "inputs": {"conditioning": prev, "latent": [ei, 0]}}
        prev = [ri, 0]
    g["40"] = {"class_type": "BasicGuider", "inputs": {"model": ["1", 0], "conditioning": prev}}
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True, help="主脸角色=定妆照（当构图底图）")
    ap.add_argument("--ref", action="append", default=[], help="其它角色=定妆照（当 ReferenceLatent 参考）")
    ap.add_argument("--canvas", default="1664x928")
    ap.add_argument("--face-ratio", type=float, default=0.22, help="主脸占画面高度比例")
    ap.add_argument("--face-at", default="0.62,0.33", help="主脸中心 (x,y)，归一化")
    ap.add_argument("--denoise", type=float, default=0.7)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--guidance", type=float, default=4.0)
    ap.add_argument("--seed", type=int, default=20260925)
    ap.add_argument("--prompt-file", required=True)
    ap.add_argument("--tag", default="C")
    ap.add_argument("--dump", default="/tmp/wvf/layout")
    ap.add_argument("--px-ref", type=float, default=1.0, help="参考图预算（MP）")
    a = ap.parse_args()
    W, H = [int(v) for v in a.canvas.lower().split("x")]
    os.makedirs(a.dump, exist_ok=True)

    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=F.AUX,
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))

    emb, ref_path, refs = {}, {}, []
    kname, kpath = a.key.split("=", 1)
    for spec in a.ref:
        n, p = spec.split("=", 1)
        fs = app.get(cv2.imread(p))
        emb[n] = fs[0].normed_embedding
        ref_path[n] = p
        refs.append(n)
    kimg = cv2.imread(kpath)
    kfs = app.get(kimg)
    if not kfs:
        raise SystemExit("!! 主脸定妆照检不到脸：%s" % kpath)
    emb[kname] = kfs[0].normed_embedding
    x0, y0, x1, y1 = [float(v) for v in kfs[0].bbox]
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    fh = y1 - y0
    tx, ty = [float(v) for v in a.face_at.split(",")]
    s = (a.face_ratio * H) / fh
    print("== 构图底图：%s 定妆照 脸高=%.0fpx(占原图 %.1f%%) → 目标 脸高=%.0fpx(占 %.0f%%) 位置=(%.2f,%.2f) 缩放=%.2f×"
          % (kname, fh, 100.0 * fh / kimg.shape[0], a.face_ratio * H, 100 * a.face_ratio, tx, ty, s))
    M = np.float32([[s, 0, tx * W - s * cx], [0, s, ty * H - s * cy]])
    warp = cv2.warpAffine(kimg, M, (W, H), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)
    # 软边：把画面四周 12% 用暗色渐变压掉，避免白底摄影棚硬边
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.minimum.reduce([xx / (0.12 * W), (W - 1 - xx) / (0.12 * W),
                           yy / (0.12 * H), (H - 1 - yy) / (0.12 * H)])
    alpha = np.clip(d, 0, 1)[:, :, None]
    grad = np.zeros((H, W, 3), np.float32)
    grad[:, :] = np.array([34, 26, 20], np.float32)          # 暗蓝灰（冷月色）
    base = (warp.astype(np.float32) * alpha + grad * (1 - alpha)).astype(np.uint8)
    bpath = os.path.join(a.dump, "%s_base.png" % a.tag)
    cv2.imwrite(bpath, base)
    print("   底图已写：%s" % bpath)

    prompt = open(a.prompt_file, encoding="utf-8").read().strip()
    g = build_graph(len(refs), W, H, a.steps, a.guidance, a.denoise, a.seed)
    ts = int(time.time() * 1000)
    g["10"]["inputs"]["image"] = F.upload_image(cv2.imencode(".png", base)[1].tobytes(),
                                                "layout_base_%d.png" % ts)
    for i, n in enumerate(refs):
        with open(ref_path[n], "rb") as fh:
            nm = F.upload_image(fh.read(), "layout_ref_%d_%s" % (ts, os.path.basename(ref_path[n])))
        g[str(20 + i * 4)]["inputs"]["image"] = nm
        g[str(21 + i * 4)]["inputs"]["megapixels"] = a.px_ref
    g["6"]["inputs"]["text"] = prompt
    cid = "layout-%d" % ts
    pid = F.post_prompt(g, cid)
    print("   提交：prompt_id=%s 参考图 %d 张 denoise=%.2f 等出图…" % (pid, len(refs), a.denoise), flush=True)
    t0 = time.time()
    rec = F.poll_history(pid, poll=3.0, timeout=2400)
    if not rec:
        raise SystemExit("!! 超时")
    st = ((rec.get("status") or {}).get("status_str")) or "?"
    if st != "success":
        raise SystemExit("!! status=%s %s" % (st, json.dumps(rec.get("status"))[:400]))
    fn, data = F.fetch_first_image(rec)
    out = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    opath = os.path.join(a.dump, "%s_out.png" % a.tag)
    cv2.imwrite(opath, out)
    print("   ✓ 出图 %s（%.0fs）" % (opath, time.time() - t0))

    fs = sorted(app.get(out), key=lambda f: float(f.bbox[0]))
    print("   == 输出：脸 %d 张 ==" % len(fs))
    for i, f in enumerate(fs, 1):
        bx0, by0, bx1, by1 = [float(v) for v in f.bbox]
        e = f.normed_embedding
        sims = {k: float(np.dot(e, v)) for k, v in emb.items()}
        best = max(sims, key=sims.get)
        print("      #%d 宽=%.0f 高=%.0f 占帧高=%.1f%% x=%.2f → %s"
              % (i, bx1 - bx0, by1 - by0, 100.0 * (by1 - by0) / H, (bx0 + bx1) / 2 / W, best),
              {k: round(v, 3) for k, v in sims.items()}, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
