#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""faceswap.py —— 关键帧「换脸后处理」（身份锚定），worker 侧模块。

为什么有它（2026-09-26/27）：
  FLUX.2 的参考图通道「绑不住人」：同镜 8 发实测均值 宝玉 0.404 / 可卿 0.212 / 警幻 0.279，
  且 3 人同框时每张脸只占画面 7–13%（≈70px）→ 脸型错乱、张冠李戴。
  改动方案见 docs/换脸后处理-首测-2026-09-26.md：**身份不走参考图，走换脸模型**（inswapper_128），
  逐脸按显式映射换（谁在哪一格由参考图顺序决定），与底模无关。

⚠️ 已知物理限制（2026-09-27 实测，必须写在最前面）：
  换脸 = 一张 128×128 的对齐脸贴回去。目标脸只有 67–76px 时，缩回去就只剩大轮廓，细节全丢，
  **看起来像「另一个人」**。反证：把宝玉定妆照换到可卿定妆照上（正面、脸 293px）→ cos 0.853、肉眼就是宝玉。
  ⇒ 因此本模块会报「脸太小」告警（notes 会出现在资产卡上）：脸宽 < 120px 就不该指望像定妆照，
     要改景别（主脸占帧高 ≥20%）或提高关键帧分辨率。

依赖（VPS 上已就位）：
  · onnxruntime（CPU）：VPS py3.6 → onnxruntime==1.10.0（清华镜像装）
  · cv2（自带 FaceDetectorYN）
  · 模型：$WEAVEORA_FACESWAP_MODELS（默认 /opt/weaveora/faceswap_models）
      yunet.onnx（检脸）、w600k_r50.onnx（ArcFace 身份向量）、inswapper_128.onnx（换脸）、
      emap_512_512_f32.bin（inswapper 的 emap，导出成裸 float32，免装 onnx 包）
  ⚠️ 不依赖 insightface 包（py3.6 装不上）：检测用 YuNet、对齐自实现 umeyama、
     换脸逻辑逐行照搬 insightface model_zoo/inswapper.py。
"""
import os
import time

import cv2
import numpy as np
import onnxruntime

MODELS = os.environ.get("WEAVEORA_FACESWAP_MODELS") or "/opt/weaveora/faceswap_models"
# 开关：默认开（关键帧出图后自动换脸）；WEAVEORA_FACESWAP=0 关闭
ENABLED = (os.environ.get("WEAVEORA_FACESWAP", "1") or "1").strip() != "0"
# 换脸前是否把整幅放大 N 倍（1=不放大）；脸上的真实像素越多，128 对齐裁切越有信息
UP = float(os.environ.get("WEAVEORA_FACESWAP_UP", "1") or 1.0)
# 表情保真：none / restore（眉间+嘴的频带用原图高频）/ replace
EXPR = (os.environ.get("WEAVEORA_FACESWAP_EXPR", "none") or "none").strip()
EXPR_FREQ = float(os.environ.get("WEAVEORA_FACESWAP_EXPR_FREQ", "3") or 3.0)
# 换脸强度（<1 = 与原脸混合，保表情但掉身份）
BLEND = float(os.environ.get("WEAVEORA_FACESWAP_BLEND", "1") or 1.0)
# 亮度/色相对齐（cxy-2 指的「假脸感」根因之一）
BRIGHTNESS = (os.environ.get("WEAVEORA_FACESWAP_BRIGHTNESS", "1") or "1").strip() != "0"
# 脸宽低于此值就在资产卡上告警（像素级物理限制，见文件头）
MIN_FACE_PX = int(os.environ.get("WEAVEORA_FACESWAP_MIN_FACE_PX", "120") or 120)

ARC_DST = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                    [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)

_YUNET = {"o": None}
_ARC = {"o": None}
_SWAP = {"o": None}


def _log(msg):
    print("[faceswap] " + msg, flush=True)


# ---------------------------------------------------------------------------
# 对齐（insightface face_align.estimate_norm 的等价实现）
# ---------------------------------------------------------------------------
def _umeyama(src, dst, estimate_scale=True):
    num, dim = src.shape
    src_mean, dst_mean = src.mean(axis=0), dst.mean(axis=0)
    src_demean = src - src_mean
    dst_demean = dst - dst_mean
    A = dst_demean.T.dot(src_demean) / num
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
            U = U.dot(T)
        else:
            S = S * d
    else:
        U = U.dot(T)
        S = S * d
    scale = (1.0 / src_demean.var(axis=0).sum() * (S.dot(d))) if estimate_scale else 1.0
    T = np.eye(dim + 1)
    T[:dim, :dim] = U.dot(Vt)
    T[:dim, dim] = dst_mean - scale * U.dot(Vt).dot(src_mean)
    T[:dim, :dim] *= scale
    return T


def _estimate_norm(lmk, image_size=112):
    ratio = float(image_size) / 112.0
    t = _umeyama(lmk.astype(np.float64), (ARC_DST * ratio).astype(np.float64))
    return t[0:2, :]


def _norm_crop(img, kps, image_size=112):
    M = _estimate_norm(kps, image_size)
    return cv2.warpAffine(img, M, (image_size, image_size), borderValue=0.0), M


# ---------------------------------------------------------------------------
# 模型
# ---------------------------------------------------------------------------
class YuNet(object):
    """cv2.FaceDetectorYN（landmark 顺序需从「右左」重排成 ArcFace 的「左右」）。"""

    def detect(self, img, score=0.5):
        if _YUNET["o"] is None:
            _YUNET["o"] = cv2.FaceDetectorYN.create(
                os.path.join(MODELS, "yunet.onnx"), "", (320, 320), score, 0.3, 5000)
        det = _YUNET["o"]
        h, w = img.shape[:2]
        det.setInputSize((w, h))
        _, faces = det.detect(img)
        out = []
        for f in (faces if faces is not None else []):
            kps = np.array(f[4:14], dtype=np.float32).reshape(5, 2)[[1, 0, 2, 4, 3]]
            out.append({"bbox": [float(v) for v in f[:4]], "kps": kps, "score": float(f[14])})
        out.sort(key=lambda d: d["bbox"][0])          # 左 → 右
        return out


class ArcFace(object):
    def _sess(self):
        if _ARC["o"] is None:
            _ARC["o"] = onnxruntime.InferenceSession(
                os.path.join(MODELS, "w600k_r50.onnx"), providers=["CPUExecutionProvider"])
        return _ARC["o"]

    def embed(self, img, kps):
        crop, _ = _norm_crop(img, kps, 112)
        blob = cv2.dnn.blobFromImage(crop, 1.0 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True)
        s = self._sess()
        v = s.run(None, {s.get_inputs()[0].name: blob})[0].reshape(-1).astype(np.float32)
        return v / (np.linalg.norm(v) + 1e-9)


class Swapper(object):
    """insightface INSwapper 的等价实现（emap 从裸 bin 读，不用 onnx 包）。"""

    def __init__(self):
        self.emap = np.fromfile(os.path.join(MODELS, "emap_512_512_f32.bin"),
                                dtype=np.float32).reshape(512, 512)
        self.sess = onnxruntime.InferenceSession(
            os.path.join(MODELS, "inswapper_128.onnx"), providers=["CPUExecutionProvider"])
        ins = self.sess.get_inputs()
        self.inames = [i.name for i in ins]
        self.onames = [o.name for o in self.sess.get_outputs()]
        self.size = int(ins[0].shape[2])

    def swap(self, img, kps, emb, brightness=True, alpha=1.0):
        aimg, M = _norm_crop(img, kps, self.size)
        blob = cv2.dnn.blobFromImage(aimg, 1.0 / 255.0, (self.size, self.size), (0.0, 0.0, 0.0), swapRB=True)
        latent = emb.reshape((1, -1)).dot(self.emap)
        latent /= (np.linalg.norm(latent) + 1e-9)
        pred = self.sess.run(self.onames, {self.inames[0]: blob, self.inames[1]: latent.astype(np.float32)})[0]
        bgr_fake = np.clip(255 * pred.transpose((0, 2, 3, 1))[0], 0, 255).astype(np.uint8)[:, :, ::-1]
        stat = {}
        if brightness:
            ht = cv2.cvtColor(aimg, cv2.COLOR_BGR2HSV).astype(np.float32)
            hf = cv2.cvtColor(bgr_fake, cv2.COLOR_BGR2HSV).astype(np.float32)
            vt = float(ht[:, :, 2][ht[:, :, 2] > 8].mean() or 1.0)
            vf = float(hf[:, :, 2].mean() or 1.0)
            stat["v_ratio"] = vt / max(vf, 1.0)
            hf[:, :, 2] = np.clip(hf[:, :, 2] * stat["v_ratio"], 0, 255)
            dh = ((float(ht[:, :, 0].mean()) - float(hf[:, :, 0].mean())) + 90) % 180 - 90
            stat["h_shift"] = dh
            hf[:, :, 0] = np.mod(hf[:, :, 0] + dh * 0.5, 180)
            bgr_fake = cv2.cvtColor(hf.astype(np.uint8), cv2.COLOR_HSV2BGR)
        # ---- 贴回（insightface 原逻辑）----
        fd = np.abs(bgr_fake.astype(np.float32) - aimg.astype(np.float32)).mean(axis=2)
        fd[:2, :] = 0
        fd[-2:, :] = 0
        fd[:, :2] = 0
        fd[:, -2:] = 0
        IM = cv2.invertAffineTransform(M)
        white = np.full((aimg.shape[0], aimg.shape[1]), 255, dtype=np.float32)
        fake_w = cv2.warpAffine(bgr_fake, IM, (img.shape[1], img.shape[0]), borderValue=0.0)
        white_w = cv2.warpAffine(white, IM, (img.shape[1], img.shape[0]), borderValue=0.0)
        white_w[white_w > 20] = 255
        hs, ws = np.where(white_w == 255)
        if len(hs) == 0:
            return img, stat
        mask_size = int(np.sqrt((np.max(hs) - np.min(hs)) * (np.max(ws) - np.min(ws))))
        m = cv2.erode(white_w, np.ones((max(mask_size // 10, 10),) * 2, np.uint8), iterations=1)
        k2 = max(mask_size // 20, 5)
        m = cv2.GaussianBlur(m, (2 * k2 + 1, 2 * k2 + 1), 0)
        m = (m / 255).reshape(m.shape[0], m.shape[1], 1)
        if alpha < 1.0:
            fake_w = alpha * fake_w + (1.0 - alpha) * img.astype(np.float32)
        return (m * fake_w + (1 - m) * img.astype(np.float32)).astype(np.uint8), stat


# ---------------------------------------------------------------------------
# 表情保真（可选）：眉间 + 嘴的软遮罩内，用原图高频替换换脸高频
# ---------------------------------------------------------------------------
def _expr_mask(shape, kps):
    le, re = np.asarray(kps[0], np.float32), np.asarray(kps[1], np.float32)
    lm, rm = np.asarray(kps[3], np.float32), np.asarray(kps[4], np.float32)
    d = float(np.linalg.norm(le - re)) or 40.0
    mid = (le + re) / 2.0
    h, w = shape[:2]
    m = np.zeros((h, w), np.float32)
    for c, ax in [(mid + np.array([0, -0.30 * d]), (0.30 * d, 0.24 * d)),
                  ((lm + rm) / 2.0 + np.array([0, 0.06 * d]), (0.88 * d, 0.48 * d))]:
        cv2.ellipse(m, (int(round(c[0])), int(round(c[1]))),
                    (max(1, int(round(ax[0]))), max(1, int(round(ax[1])))), 0, 0, 360, 1.0, -1)
    k = max(3, int(round(d * 0.10)))
    if k % 2 == 0:
        k += 1
    return cv2.GaussianBlur(m, (k, k), 0)


def _expr_apply(orig, swapped, kps, freq=3.0):
    m = _expr_mask(orig.shape, kps)
    if m.max() <= 0:
        return swapped
    sg = float(freq)
    hp_o = orig.astype(np.float32) - cv2.GaussianBlur(orig, (0, 0), sg).astype(np.float32)
    low_s = cv2.GaussianBlur(swapped, (0, 0), sg).astype(np.float32)
    prev = low_s + hp_o
    m3 = m[:, :, None]
    return np.clip(m3 * prev + (1 - m3) * swapped.astype(np.float32), 0, 255).astype(np.uint8)


def _expr_replace(orig, swapped, kps):
    m = _expr_mask(orig.shape, kps)[:, :, None]
    return np.clip(m * orig.astype(np.float32) + (1 - m) * swapped.astype(np.float32), 0, 255).astype(np.uint8)


# ---------------------------------------------------------------------------
# 对外 API
# ---------------------------------------------------------------------------
def available():
    if not os.path.isdir(MODELS):
        return False
    for f in ("yunet.onnx", "w600k_r50.onnx", "inswapper_128.onnx", "emap_512_512_f32.bin"):
        if not os.path.exists(os.path.join(MODELS, f)):
            return False
    return True


def swap_bgr(img, sources, order=None, up=None, expr=None, expr_freq=None, blend=None, brightness=None):
    """img: BGR ndarray；sources: [(名字, BGR ndarray)]；order: 左→右要换的名字列表（缺省=按 cos 最近）。

    返回 (新图, report)：report = {"mapping": [...], "faces": [...], "warn": "..."}
    """
    up = UP if up is None else up
    expr = EXPR if expr is None else expr
    expr_freq = EXPR_FREQ if expr_freq is None else expr_freq
    blend = BLEND if blend is None else blend
    brightness = BRIGHTNESS if brightness is None else brightness

    det, arc = YuNet(), ArcFace()
    H0, W0 = img.shape[:2]
    faces = det.detect(img)
    if not faces:
        return img, {"mapping": [], "faces": [], "warn": "未检出人脸"}
    refs = []
    for name, im in sources:
        fs = det.detect(im)
        if not fs:
            _log("参考图 %s 未检出人脸 → 跳过" % name)
            continue
        f = max(fs, key=lambda d: d["bbox"][2] * d["bbox"][3])
        refs.append((name, arc.embed(im, f["kps"])))
    if not refs:
        return img, {"mapping": [], "faces": [], "warn": "参考图里都没有可用人脸"}
    us = float(up or 1.0)
    work = (cv2.resize(img, (int(round(W0 * us)), int(round(H0 * us))), interpolation=cv2.INTER_LANCZOS4)
            if us > 1.0 else img)
    cur = work.copy()
    sw = None
    report = {"mapping": [], "faces": [], "warn": ""}
    for i, f in enumerate(faces):
        k = f["kps"] * us
        emb_by = dict(refs)
        if order and i < len(order) and order[i] in emb_by:
            name = order[i]
        else:
            name = max(refs, key=lambda r: float(np.dot(arc.embed(cur, k), r[1])))[0]
        e = arc.embed(cur, k)
        before = {n: round(float(np.dot(e, v)), 3) for n, v in refs}
        if sw is None:
            sw = Swapper()
        t0 = time.time()
        cur, st = sw.swap(cur, k, emb_by[name], brightness=brightness, alpha=blend)
        if expr == "restore":
            cur = _expr_apply(work, cur, k, freq=expr_freq)
        elif expr == "replace":
            cur = _expr_replace(work, cur, k)
        e2 = arc.embed(cur, k)
        after = {n: round(float(np.dot(e2, v)), 3) for n, v in refs}
        wpx = int(f["bbox"][2])
        report["mapping"].append(name)
        report["faces"].append({"idx": i + 1, "x": round(f["bbox"][0] / float(W0), 3), "width_px": wpx,
                                "assigned": name, "before": before, "after": after,
                                "argmax_after": max(after, key=after.get),
                                "sec": round(time.time() - t0, 2)})
    if us > 1.0:
        cur = cv2.resize(cur, (W0, H0), interpolation=cv2.INTER_AREA)
    small = [f["width_px"] for f in report["faces"]]
    if small and min(small) < MIN_FACE_PX:
        report["warn"] = ("脸太小：最小脸宽 %dpx（< %dpx）→ 换脸只能改大轮廓，看不出是定妆照那个人。"
                          "建议把景别改成过肩/中近景（主脸占帧高 ≥20%%）或提高关键帧分辨率。"
                          % (min(small), MIN_FACE_PX))
    return cur, report


def post_process_png(png_bytes, sources_png, order=None, **kw):
    """worker 用：png 进 → png 出。sources_png = [(名字, png bytes)]。"""
    img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return png_bytes, {"warn": "解码失败", "faces": [], "mapping": []}
    src = []
    for name, b in sources_png:
        im = cv2.imdecode(np.frombuffer(b, np.uint8), cv2.IMREAD_COLOR)
        if im is not None:
            src.append((name, im))
    out, rep = swap_bgr(img, src, order=order, **kw)
    ok, buf = cv2.imencode(".png", out)
    if not ok:
        return png_bytes, rep
    return buf.tobytes(), rep
