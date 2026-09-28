#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_faceswap.py —— 换脸后处理 PoC（**方案 3**，本机 RTX 3070Ti / CPU 皆可）

为什么要它（2026-09-26，用户裁定）：
  · 方案 2（逐脸「单参考重绘 + 羽化贴回」）实测不可用：出图被当成小图贴上去、接缝明显。
    已核实的两条技术根因（来源 cxy-2/Flux2-klein-9B-face-swapping-workflow，2026-08-25 实读）：
      ① 面部**亮度/色相**与身体不匹配 → 「假脸感」；② Flux2 VAE **16× 压缩**导致像素错位。
  · 用户要求改走 **换脸后处理**：身份由「换脸模型 + ArcFace 身份向量」注入，与底模无关；
    好处是 **逐脸精确指定源脸**（3 个人各拿自己的定妆照），这正是「张冠李戴」的对症解。

与 insightface 官方实现的差异（本脚本**不依赖 insightface 包**，原因：本机只有 Python 3.13，
  insightface 无 cp313 wheel 且需 MSVC 编译）：
  · 检测：OpenCV 自带 `cv2.FaceDetectorYN`（YuNet）+ 本机 GPU/CPU 版 onnxruntime
  · 识别：`w600k_r50.onnx`（ArcFace，和 buffalo_l 同一份）→ 512 维身份向量
  · 换脸：`inswapper_128.onnx`，**逐行照搬** insightface `model_zoo/inswapper.py`（emap/latent/
    贴回混合逻辑），只额外加了「亮度对齐」（cxy-2 的第 ① 条）

校验（本脚本自带，不靠肉眼）：
  `--crosscos` 打印三张定妆照两两 ArcFace cos，与 `docs/第4镜-脸型错乱-排查-2026-09-25.md` §1.2
  的实测值对照（宝玉↔可卿 0.126 / 警幻↔可卿 0.260 / 宝玉↔警幻 0.324）；
  数值量级吻合 ⇒ 检测+对齐+识别这条链是对的，后面的 cos 才可信。

用法：
  python wv_faceswap.py --crosscos
  python wv_faceswap.py --img assets/shot4_1250.png \
      --ref 宝玉=assets/baoyu.png --ref 可卿=assets/keqing.png --ref 警幻=assets/jinghuan.png \
      --map 宝玉,可卿,警幻 --out out/shot4  [--no-brightness] [--det-scale 2]
"""
import argparse
import json
import os
import sys
import time

import cv2
import numpy as np
import onnx
import onnxruntime
from onnx import numpy_helper

HERE = os.path.dirname(os.path.abspath(__file__))
# 模型目录：默认与本脚本同级的 models/；盒上可用环境变量指到 /opt/weaveora/faceswap/models
MDIR = os.environ.get("WEAVEORA_FACESWAP_MODELS") or os.path.join(HERE, "models")
ARC_DST = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                    [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)


# ---------------------------------------------------------------------------
# insightface utils/face_align.estimate_norm 的等价实现（umeyama，去掉反射）
# ---------------------------------------------------------------------------
def _umeyama(src, dst, estimate_scale=True):
    num, dim = src.shape
    src_mean, dst_mean = src.mean(axis=0), dst.mean(axis=0)
    src_demean = src - src_mean
    dst_demean = dst - dst_mean
    A = dst_demean.T @ src_demean / num
    d = np.ones(dim)
    if np.linalg.det(A) < 0:
        d[dim - 1] = -1
    T = np.eye(dim)
    T[dim - 1, dim - 1] = d[dim - 1]
    U, S, Vt = np.linalg.svd(A)
    rank = np.linalg.matrix_rank(A)
    if rank == 0:
        return np.eye(dim + 1)
    if rank == dim - 1:
        if np.linalg.det(U) * np.linalg.det(Vt) > 0:
            U = U @ T
        else:
            S = S * d
    else:
        U = U @ T
        S = S * d
    if estimate_scale:
        scale = 1.0 / src_demean.var(axis=0).sum() * (S @ d)
    else:
        scale = 1.0
    T = np.eye(dim + 1)
    T[:dim, :dim] = U @ Vt
    T[:dim, dim] = dst_mean - scale * (U @ Vt) @ src_mean
    T[:dim, :dim] *= scale
    return T


def estimate_norm(lmk, image_size=112):
    """insightface face_align.estimate_norm（image_size 必须 112/128 的倍数）。"""
    assert lmk.shape == (5, 2)
    ratio = float(image_size) / 112.0
    dst = ARC_DST * ratio
    tform = _umeyama(lmk.astype(np.float64), dst.astype(np.float64))
    return tform[0:2, :]


def norm_crop(img, landmark, image_size=112, mode="arcface"):
    M = estimate_norm(landmark, image_size)
    return cv2.warpAffine(img, M, (image_size, image_size), borderValue=0.0), M


# ---------------------------------------------------------------------------
# 检测：OpenCV YuNet
# ---------------------------------------------------------------------------
class YuNet:
    """cv2.FaceDetectorYN 包装。landmark 顺序 = [右眼, 左眼, 鼻尖, 右嘴角, 左嘴角]，
    需重排成 ArcFace 模板顺序 [左眼, 右眼, 鼻尖, 左嘴角, 右嘴角]。"""

    def __init__(self, model=None, score=0.5, nms=0.3, topk=5000):
        self.det = cv2.FaceDetectorYN.create(model or os.path.join(MDIR, "yunet.onnx"),
                                             "", (320, 320), score, nms, topk)

    def detect(self, img, scale=1.0):
        h, w = img.shape[:2]
        if scale != 1.0:
            img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)
        h, w = img.shape[:2]
        self.det.setInputSize((w, h))
        _, faces = self.det.detect(img)
        out = []
        for f in (faces if faces is not None else []):
            bbox = [float(v) / scale for v in f[:4]]
            kps = np.array(f[4:14], dtype=np.float32).reshape(5, 2) / scale
            kps = kps[[1, 0, 2, 4, 3]]            # 右左 → 左右
            out.append({"bbox": bbox, "kps": kps, "score": float(f[14])})
        out.sort(key=lambda d: d["bbox"][0])
        return out, img


# ---------------------------------------------------------------------------
# 识别：ArcFace w600k_r50
# ---------------------------------------------------------------------------
class ArcFace:
    def __init__(self, model=None, providers=None):
        self.sess = onnxruntime.InferenceSession(
            model or os.path.join(MDIR, "w600k_r50.onnx"),
            providers=providers or onnxruntime.get_available_providers())
        self.iname = self.sess.get_inputs()[0].name

    def embed(self, img, kps):
        crop, _ = norm_crop(img, kps, 112)
        blob = cv2.dnn.blobFromImage(crop, 1.0 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True)
        v = self.sess.run(None, {self.iname: blob})[0].reshape(-1).astype(np.float32)
        return v / (np.linalg.norm(v) + 1e-9), crop

    def embed_face(self, img, face, det_scale=1.0):
        """直接对整图跑检测+识别（用于参考图）。"""
        return self.embed(img, face["kps"])


def cos(a, b):
    return float(np.dot(a, b))


# ---------------------------------------------------------------------------
# 表情保真：用 5 点几何圈出「表情区」（眉间纹/眉形/嘴形）
#   身份 vs 表情的分工（inswapper 只搬身份，会把源脸的中性表情带进来）：
#     身份：脸轮廓/下颌/鼻/眼形/颊骨   ← 必须来自**换脸结果**
#     表情：眉（抬/蹙）、眉间纹、唇形开合、牙齿、法令纹 ← 必须来自**原图**
#   所以：在表情区里把原图「高频细节」加回去（restore），或整块用原图（replace）。
# ---------------------------------------------------------------------------
def expr_regions(kps, region="tight"):
    """kps = [左眼, 右眼, 鼻尖, 左嘴角, 右嘴角]（原图坐标）→ 椭圆列表 [(中心, 轴, 角度)]。
    tight（默认）：只圈「眉间竖纹 + 嘴/唇线/牙齿」——它们是表情最直接、且与身份冲突最小的区。
    wide：另加眉带、法令纹、嘴角（表情更全，但身份代价明显）。
    """
    le, re, nose, lm, rm = [np.asarray(p, dtype=np.float32) for p in kps]
    d = float(np.linalg.norm(le - re)) or 40.0          # 瞳距作尺度
    mid = (le + re) / 2.0
    regs = [
        # 眉间（蹙眉的竖纹就在这）
        (mid + np.array([0, -0.30 * d]), (0.30 * d, 0.24 * d), 0),
        # 嘴（唇形/开合/牙齿/嘴角）
        ((lm + rm) / 2.0 + np.array([0, 0.06 * d]), (0.88 * d, 0.48 * d), 0),
    ]
    if region == "wide":
        regs += [
            (le + np.array([0, -0.40 * d]), (0.66 * d, 0.30 * d), 0),
            (re + np.array([0, -0.40 * d]), (0.66 * d, 0.30 * d), 0),
            (lm + np.array([-0.10 * d, 0.30 * d]), (0.42 * d, 0.45 * d), 0),
            (rm + np.array([0.10 * d, 0.30 * d]), (0.42 * d, 0.45 * d), 0),
        ]
    return regs


def expr_mask(shape, kps, grow=1.0, feather=None, region="tight"):
    """表情区软遮罩（0..1，float32，与原图同尺寸）。"""
    h, w = shape[:2]
    m = np.zeros((h, w), np.float32)
    for (c, ax, ang) in expr_regions(kps, region):
        cv2.ellipse(m, (int(round(c[0])), int(round(c[1]))),
                    (max(1, int(round(ax[0] * grow))), max(1, int(round(ax[1] * grow)))),
                    ang, 0, 360, 1.0, -1)
    if m.max() <= 0:
        return m
    k = int(feather or max(3, (np.linalg.norm(np.asarray(kps[0]) - np.asarray(kps[1])) * 0.10)))
    k = k if k % 2 == 1 else k + 1
    return cv2.GaussianBlur(m, (k, k), 0)


def expr_apply(orig, swapped, kps, mode="restore", strength=1.0, region_grow=1.0, sep=None, freq=None,
               region="tight", gate=True):
    """在表情区把原图信息加回换脸结果。
    mode=restore（频带替换，推荐）：**低频用换脸结果（身份）、高频用原图（皱纹/唇线/牙齿）**
        out = blur(swap, σ) + (orig - blur(orig, σ))     σ = --expr-freq（越大 = 越多原图结构回来）
        ⚠️ 不能用「swap + orig_detail」：那等于把两套纹理叠在一起，实测高频反而离原图更远（keep_hf 跑成负值）
    mode=replace：整块用原图（表情最完整，但嘴/眉区的身份贡献会丢掉）
    返回 (新图, (keep, keep_hf))
    """
    m = expr_mask(orig.shape, kps, region_grow, region=region)
    if m.max() <= 0:
        return swapped, None
    m3 = m[:, :, None]
    o = orig.astype(np.float32)
    s = swapped.astype(np.float32)
    if mode == "replace":
        out = m3 * o + (1 - m3) * s
    else:                                   # restore（频带替换 + 增益闸门）
        sg = float(freq or max(2.0, 0.06 * float(np.linalg.norm(np.asarray(kps[0]) - np.asarray(kps[1])))))
        hp_o = o - cv2.GaussianBlur(orig, (0, 0), sg).astype(np.float32)
        if gate:
            # 只回注「原图有结构、换脸把它磨平了」的像素：
            #   gain = (|hp_o| - |hp_s|)/|hp_o|，夹到 [0,1]；否则整块换频带会把身份细节一起冲掉（实测 cos 0.81→0.56）
            hp_s = s - cv2.GaussianBlur(swapped, (0, 0), sg).astype(np.float32)
            e_o = np.abs(hp_o).mean(axis=2)
            e_s = np.abs(hp_s).mean(axis=2)
            gain = np.clip((e_o - e_s) / (e_o + 1e-3), 0.0, 1.0)[:, :, None]
            band = hp_o * gain * float(strength)
        else:
            band = hp_o * float(strength)
        prev = cv2.GaussianBlur(swapped, (0, 0), sg).astype(np.float32) + band
        out = m3 * prev + (1 - m3) * s
    idx = m > 0.25
    keep = keep_hf = None
    if idx.sum() > 0:
        d_swap = float(np.abs(s - o).mean(axis=2)[idx].mean())
        d_out = float(np.abs(out - o).mean(axis=2)[idx].mean())
        keep = 0.0 if d_swap <= 1e-6 else float(1.0 - d_out / d_swap)
        # 高频保留度：只比「皱纹/唇线/牙齿」这类高频（低频是颜色/形状，restore 本来就不碰）
        sg = max(1.2, float(sep or 2.5))
        hp = lambda x: x - cv2.GaussianBlur(x, (0, 0), sg)
        e_swap = float(np.abs(hp(s) - hp(o)).mean(axis=2)[idx].mean())
        e_out = float(np.abs(hp(out) - hp(o)).mean(axis=2)[idx].mean())
        keep_hf = None if e_swap <= 1e-6 else float(1.0 - e_out / e_swap)
    return np.clip(out, 0, 255).astype(np.uint8), (keep, keep_hf)


# ---------------------------------------------------------------------------
# 换脸：照搬 insightface INSwapper
# ---------------------------------------------------------------------------
class Swapper:
    def __init__(self, model=None, providers=None):
        path = model or os.path.join(MDIR, "inswapper_128.onnx")
        graph = onnx.load(path).graph
        self.emap = numpy_helper.to_array(graph.initializer[-1])
        self.input_mean, self.input_std = 0.0, 255.0
        self.sess = onnxruntime.InferenceSession(
            path, providers=providers or onnxruntime.get_available_providers())
        ins = self.sess.get_inputs()
        self.input_names = [i.name for i in ins]
        self.output_names = [o.name for o in self.sess.get_outputs()]
        self.input_size = tuple(ins[0].shape[2:4][::-1])
        self.iname = [i.name for i in ins]

    def forward(self, img, latent):
        img = (img - self.input_mean) / self.input_std
        return self.sess.run(self.output_names, {self.input_names[0]: img, self.input_names[1]: latent})[0]

    def swap(self, img, kps, source_emb, brightness_align=True, mask_out=False, alpha=1.0):
        """返回 (新图, 统计)。逐行照搬 insightface，唯一附加：亮度对齐（cxy-2 第①条）。"""
        aimg, M = norm_crop(img, kps, self.input_size[0])
        blob = cv2.dnn.blobFromImage(aimg, 1.0 / self.input_std, self.input_size,
                                     (self.input_mean, self.input_mean, self.input_mean), swapRB=True)
        latent = source_emb.reshape((1, -1))
        latent = np.dot(latent, self.emap)
        latent /= np.linalg.norm(latent)
        pred = self.sess.run(self.output_names, {self.input_names[0]: blob, self.input_names[1]: latent})[0]
        img_fake = pred.transpose((0, 2, 3, 1))[0]
        bgr_fake = np.clip(255 * img_fake, 0, 255).astype(np.uint8)[:, :, ::-1]
        stat = {}
        # ---- 亮度对齐（在 128 对齐裁切里做：把源脸整体亮度/色相校到目标脸）----
        if brightness_align:
            hsv_t = cv2.cvtColor(aimg, cv2.COLOR_BGR2HSV).astype(np.float32)
            hsv_f = cv2.cvtColor(bgr_fake, cv2.COLOR_BGR2HSV).astype(np.float32)
            vt = float(hsv_t[:, :, 2][hsv_t[:, :, 2] > 8].mean() or 1.0)
            vf = float(hsv_f[:, :, 2].mean() or 1.0)
            ratio = vt / max(vf, 1.0)
            stat["v_target"], stat["v_fake"], stat["v_ratio"] = vt, vf, ratio
            hsv_f[:, :, 2] = np.clip(hsv_f[:, :, 2] * ratio, 0, 255)
            ht = float(hsv_t[:, :, 0].mean())
            hf = float(hsv_f[:, :, 0].mean())
            dh = (ht - hf + 90) % 180 - 90          # 色相差（±90 内取最短弧）
            stat["h_shift"] = dh
            hsv_f[:, :, 0] = np.mod(hsv_f[:, :, 0] + dh * 0.5, 180)
            bgr_fake = cv2.cvtColor(hsv_f.astype(np.uint8), cv2.COLOR_HSV2BGR)
        # ---- 贴回（insightface 原逻辑）----
        fake_diff = bgr_fake.astype(np.float32) - aimg.astype(np.float32)
        fake_diff = np.abs(fake_diff).mean(axis=2)
        fake_diff[:2, :] = 0
        fake_diff[-2:, :] = 0
        fake_diff[:, :2] = 0
        fake_diff[:, -2:] = 0
        IM = cv2.invertAffineTransform(M)
        img_white = np.full((aimg.shape[0], aimg.shape[1]), 255, dtype=np.float32)
        bgr_fake_w = cv2.warpAffine(bgr_fake, IM, (img.shape[1], img.shape[0]), borderValue=0.0)
        img_white_w = cv2.warpAffine(img_white, IM, (img.shape[1], img.shape[0]), borderValue=0.0)
        fake_diff_w = cv2.warpAffine(fake_diff, IM, (img.shape[1], img.shape[0]), borderValue=0.0)
        img_white_w[img_white_w > 20] = 255
        thresh = 10
        fake_diff_w[fake_diff_w < thresh] = 0
        fake_diff_w[fake_diff_w >= thresh] = 255
        m = img_white_w
        hs, ws = np.where(m == 255)
        if len(hs) == 0:
            return img, stat
        mask_size = int(np.sqrt((np.max(hs) - np.min(hs)) * (np.max(ws) - np.min(ws))))
        k = max(mask_size // 10, 10)
        m = cv2.erode(m, np.ones((k, k), np.uint8), iterations=1)
        fake_diff_w = cv2.dilate(fake_diff_w, np.ones((2, 2), np.uint8), iterations=1)
        k2 = max(mask_size // 20, 5)
        bs = tuple(2 * i + 1 for i in (k2, k2))
        m = cv2.GaussianBlur(m, bs, 0)
        fake_diff_w = cv2.GaussianBlur(fake_diff_w, (11, 11), 0)
        m = (m / 255).reshape(m.shape[0], m.shape[1], 1)
        if alpha < 1.0:
            # alpha<1：换脸结果与原脸混合 → 保留原表情/皮肤质感/妆面（inswapper 会洗掉表情）
            bgr_fake_w = alpha * bgr_fake_w + (1.0 - alpha) * img.astype(np.float32)
        merged = m * bgr_fake_w + (1 - m) * img.astype(np.float32)
        if mask_out:
            stat["mask"] = m
        return merged.astype(np.uint8), stat


# ---------------------------------------------------------------------------
def load_refs(pairs, det, arc):
    refs = {}
    for name, path in pairs:
        img = cv2.imread(path, cv2.IMREAD_COLOR)
        if img is None:
            sys.exit("读不到参考图 %s" % path)
        faces, _ = det.detect(img, 1.0)
        if not faces:
            sys.exit("参考图 %s 没检出人脸" % path)
        f = max(faces, key=lambda d: d["bbox"][2] * d["bbox"][3])
        emb, crop = arc.embed(img, f["kps"])
        refs[name] = {"emb": emb, "img": img, "crop": crop, "path": path, "bbox": f["bbox"]}
        print("  ref %-4s %s  face=%dx%d score=%.3f" %
              (name, os.path.basename(path), int(f["bbox"][2]), int(f["bbox"][3]), f["score"]))
    return refs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--img")
    ap.add_argument("--ref", action="append", default=[], help="名字=路径")
    ap.add_argument("--map", default="", help="从左到右的源脸名字（逗号分隔）；不给则按 cos 最近自动认领")
    ap.add_argument("--out", default=os.path.join(HERE, "out", "run"))
    ap.add_argument("--det-scale", type=float, default=1.0, help="检测放大倍数（小脸用 2）")
    ap.add_argument("--no-brightness", action="store_true")
    ap.add_argument("--blend", type=float, default=1.0, help="换脸强度 alpha（<1 = 与原脸混合，保表情/皮肤质感）")
    ap.add_argument("--expr", choices=["none", "restore", "replace"], default="restore",
                    help="表情保真：restore=只在眉/嘴区回注原图高频细节(默认)；replace=整块用原图；none=不做")
    ap.add_argument("--expr-strength", type=float, default=1.0)
    ap.add_argument("--expr-freq", type=float, default=0.0, help="频带分离的 σ（px）；0=按脸宽自动 ≈0.06×瞳距")
    ap.add_argument("--expr-grow", type=float, default=1.0, help="表情区放大倍数")
    ap.add_argument("--expr-region", choices=["tight", "wide"], default="tight", help="表情区范围：tight=眉间+嘴（默认）；wide=另加眉带/法令纹")
    ap.add_argument("--no-gate", action="store_true", help="关掉增益闸门（整块换频带，更保表情但更丢身份）")
    ap.add_argument("--dump-mask", action="store_true", help="导出表情区遮罩叠加图（04_expr_mask_faceN.png）")
    ap.add_argument("--crosscos", action="store_true", help="只打印参考图两两 cos（校验用）")
    ap.add_argument("--cpu", action="store_true")
    ap.add_argument("--seed-only", action="store_true", help="不换脸，只跑检测+识别（基线）")
    a = ap.parse_args()

    providers = ["CPUExecutionProvider"] if a.cpu else onnxruntime.get_available_providers()
    print("onnxruntime providers:", providers)
    det, arc = YuNet(), ArcFace()
    sw = None                     # 换脸模型**懒加载**（--crosscos / --seed-only 不需要它）

    pairs = []
    for s in a.ref or ([] if a.crosscos else []):
        if "=" in s:
            n, p = s.split("=", 1)
            pairs.append((n, p))
    if not pairs and a.crosscos:
        for n in ("宝玉", "可卿", "警幻"):
            for ext in (".png", ".jpg"):
                p = os.path.join(HERE, "assets", {"宝玉": "baoyu", "可卿": "keqing", "警幻": "jinghuan"}[n] + ext)
                if os.path.exists(p):
                    pairs.append((n, p))
                    break

    print("== 参考图 ==")
    refs = load_refs(pairs, det, arc)

    if a.crosscos:
        names = list(refs)
        print("\n== 定妆照两两 ArcFace cos（对照 docs §1.2：宝玉↔可卿 0.126 / 警幻↔可卿 0.260 / 宝玉↔警幻 0.324）==")
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                print("  %s ↔ %s = %.3f" % (names[i], names[j], cos(refs[names[i]]["emb"], refs[names[j]]["emb"])))
        return

    img = cv2.imread(a.img, cv2.IMREAD_COLOR)
    if img is None:
        sys.exit("读不到 --img %s" % a.img)
    H, W = img.shape[:2]
    faces, _ = det.detect(img, a.det_scale)
    print("\n== 目标图 %s (%dx%d)：检出 %d 张脸 ==" % (os.path.basename(a.img), W, H, len(faces)))
    for i, f in enumerate(faces):
        x, y, w, h = f["bbox"]
        sug = max(refs, key=lambda n: cos(arc.embed(img, f["kps"])[0], refs[n]["emb"]))
        row = ["  脸%d x=%.3f y=%.3f 宽%dx高%d score=%.3f" % (i + 1, x / W, y / H, int(w), int(h), f["score"])]
        for n in refs:
            row.append("%s=%.3f" % (n, cos(arc.embed(img, f["kps"])[0], refs[n]["emb"])))
        print(" ".join(row) + "  → 最近: %s" % sug)

    order = [s for s in a.map.split(",") if s] if a.map else None
    if order and len(order) < len(faces):
        order += list(refs)[:0]
    os.makedirs(a.out, exist_ok=True)
    cv2.imwrite(os.path.join(a.out, "00_before.png"), img)

    if a.seed_only:
        return

    cur = img.copy()
    if sw is None:
        sw = Swapper()
        print("inswapper input_size:", sw.input_size, " emap:", sw.emap.shape)
    report = []
    for i, f in enumerate(faces):
        name = order[i] if order and i < len(order) else max(
            refs, key=lambda n: cos(arc.embed(cur, f["kps"])[0], refs[n]["emb"]))
        if name not in refs:
            print("  ⚠️ 脸%d 指定源「%s」不在参考图里 → 跳过" % (i + 1, name))
            continue
        before = {n: cos(arc.embed(cur, f["kps"])[0], refs[n]["emb"]) for n in refs}
        t0 = time.time()
        cur, st = sw.swap(cur, f["kps"], refs[name]["emb"], brightness_align=not a.no_brightness, alpha=a.blend)
        keep = None
        if a.expr != "none":
            cur, keep = expr_apply(img, cur, f["kps"], mode=a.expr,
                                   strength=a.expr_strength, region_grow=a.expr_grow,
                                   freq=(a.expr_freq or None),
                                   region=a.expr_region, gate=(not a.no_gate),
                                   sep=max(1.5, 0.035 * float(np.linalg.norm(f["kps"][0] - f["kps"][1]))))
        if a.dump_mask:
            mm = expr_mask(img.shape, f["kps"], a.expr_grow, region=a.expr_region)
            ov = img.copy()
            ov[mm > 0.25] = (0.4 * ov[mm > 0.25] + 0.6 * np.array([0, 255, 0])).astype(np.uint8)
            cv2.imwrite(os.path.join(a.out, "04_expr_mask_face%d.png" % (i + 1)), ov)
        after = {n: cos(arc.embed(cur, f["kps"])[0], refs[n]["emb"]) for n in refs}
        best = max(after, key=after.get)
        report.append({"face": i + 1, "x": round(f["bbox"][0] / W, 3), "assigned": name,
                       "before": {k: round(v, 3) for k, v in before.items()},
                       "after": {k: round(v, 3) for k, v in after.items()},
                       "argmax_after": best, "sec": round(time.time() - t0, 2),
                       "brightness": {k: (round(v, 3) if isinstance(v, float) else v) for k, v in st.items() if k != "mask"},
                       "expr": a.expr, "expr_keep": (None if keep is None else round(keep[0], 3)),
                       "expr_keep_hf": (None if (keep is None or keep[1] is None) else round(keep[1], 3))})
        print("  脸%d → %s : %s 前 %.3f → 后 %.3f（argmax=%s） %.1fs %s%s" %
              (i + 1, name, name, before[name], after[name], best, time.time() - t0,
               ("亮度比=%.3f 色相=%+.1f" % (st.get("v_ratio", 0), st.get("h_shift", 0))) if st else "",
               ("  表情保留=%.2f/高频=%.2f" % keep) if isinstance(keep, tuple) else ""))
    cv2.imwrite(os.path.join(a.out, "01_after.png"), cur)
    # ---- 每张脸的 4× 放大 before|after 对比条（肉眼判定用）----
    assigned = {r["face"]: r["assigned"] for r in report}
    rows = []
    for i, f in enumerate(faces):
        x, y, w, h = f["bbox"]
        pad = 0.6 * max(w, h)
        x0, y0 = int(max(0, x - pad)), int(max(0, y - pad))
        x1, y1 = int(min(W, x + w + pad)), int(min(H, y + h + pad))
        if x1 <= x0 or y1 <= y0:
            continue
        b = cv2.resize(img[y0:y1, x0:x1], None, fx=4, fy=4, interpolation=cv2.INTER_NEAREST)
        a2 = cv2.resize(cur[y0:y1, x0:x1], None, fx=4, fy=4, interpolation=cv2.INTER_NEAREST)
        sep = np.full((b.shape[0], 10, 3), 255, np.uint8)
        row = np.hstack([b, sep, a2])
        lab = np.zeros((30, row.shape[1], 3), np.uint8)
        cv2.putText(lab, "face%d -> %s   |  left=BEFORE  right=AFTER" % (i + 1, assigned.get(i + 1, "-")),
                    (6, 21), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
        rows.append(np.vstack([lab, row]))
        cv2.imwrite(os.path.join(a.out, "face%d_zoom_4x.png" % (i + 1)), np.vstack([lab, row]))
    if rows:
        wmax = max(r.shape[1] for r in rows)
        rows = [np.pad(r, ((0, 0), (0, wmax - r.shape[1]), (0, 0)), constant_values=255) for r in rows]
        cv2.imwrite(os.path.join(a.out, "03_faces_zoom.png"), np.vstack(rows))
    # 三连对比图
    cmp_img = np.hstack([img, cur])
    cv2.imwrite(os.path.join(a.out, "02_side_by_side.png"), cmp_img)
    with open(os.path.join(a.out, "report.json"), "w", encoding="utf-8") as fp:
        json.dump({"img": a.img, "faces": report}, fp, ensure_ascii=False, indent=2)
    print("\n产物: %s (00_before / 01_after / 02_side_by_side / report.json)" % a.out)


if __name__ == "__main__":
    main()
