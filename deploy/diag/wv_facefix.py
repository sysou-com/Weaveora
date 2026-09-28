#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_facefix.py —— 方案 2 第一版：**逐脸「单参考局部重绘 + 羽化贴回」**（在 GPU 盒上跑）

为什么要这么干（2026-09-25 用户裁定「试方案 2」）：
  第 4 镜实测：输出里一张脸只有 60–108px 宽（占整图 0.6% 面积），而定妆照里是 326px（7.7%）。
  18 次出图统计：**最大脸宽 vs 最好身份 cos，Pearson r = 0.722**；100–119px 档平均 0.494，
  60–79px 档只有 0.321。⇒ 身份丢的不是「提示词没写」，是**脸上没像素**。
  FLUX.2 的官方 multi-reference 用法是「少参考的属性组合」，不是「N 个身份槽」。

所以本脚本把活儿拆开：
  · 构图 / 站位 / 服装 / 场景 —— 保持**原关键帧不动**（只换脸上那一小块像素）
  · 身份 —— 每个角色**单独一张参考图**（单参考 = 模型最擅长的档位），且把脸裁出来放大到
    ~1024px 再重绘（重绘时脸上的像素 ≈ 定妆照自己的量级）

做法（每个检出人脸一支）：
  1) insightface 检脸 + 与各定妆照算 cos，最近的认领（打印出来，认领错了看得见）
  2) 以脸为中心裁正方形（边长 = expand × 脸框长边，贴边则平移）→ 放大到 px×px
  3) 送 ComfyUI：**底图 = 这个裁切**（SplitSigmasDenoise，denoise 默认 0.55）
                 + **ReferenceLatent = 该角色定妆照**（工作流本脚本内联拼，见 build_graph）
  4) 出图缩回裁切尺寸，用羽化椭圆贴回原图（只碰脸那一块，边框不动）
  5) 出图后再跑一次 faceid，打印 before/after

⚠️ 本脚本**自带极简 HTTP 客户端**（upload / prompt / history / view 四步）：
   `comfy_client` 是 **VPS 上**的模块，盒上没这个文件 —— 在盒上跑不能 import 它。

用法：
  /opt/weaveora/ComfyUI/venv/bin/python wv_facefix.py \
      --img keyframe.png --out keyframe_fixed.png \
      --ref 宝玉=baoyu.png --ref 可卿=keqing.png --ref 警幻=jinghuan.png \
      [--denoise 0.55] [--expand 2.0] [--px 1024] [--seed N] [--only 可卿] [--dump /tmp/wvf/fix]
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

import cv2
import numpy as np
from insightface.app import FaceAnalysis

AUX = "/opt/weaveora/ComfyUI/custom_nodes/ComfyUI-LatentSyncWrapper/checkpoints/auxiliary"
COMFY = "http://127.0.0.1:8001"
CRLF = chr(13) + chr(10)


# ---------------------------------------------------------------------------
# 自带极简 ComfyUI HTTP 客户端
# ---------------------------------------------------------------------------
def _http(method, path, body=None, headers=None, timeout=300):
    req = urllib.request.Request(COMFY + path, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def upload_image(data, filename):
    """multipart/form-data 上传（字段名 image）。返回盒上的文件名。"""
    b = "----wvfacefix%d" % int(time.time() * 1000)
    parts = []
    parts.append(("--" + b + CRLF).encode())
    parts.append(("Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"" + CRLF).encode())
    parts.append(("Content-Type: image/png" + CRLF + CRLF).encode())
    parts.append(data)
    parts.append((CRLF + "--" + b + "--" + CRLF).encode())
    out = json.loads(_http("POST", "/upload/image", b"".join(parts),
                           {"Content-Type": "multipart/form-data; boundary=" + b}).decode())
    return out.get("name")


def post_prompt(graph, cid):
    body = json.dumps({"prompt": graph, "client_id": cid}).encode()
    return json.loads(_http("POST", "/prompt", body,
                            {"Content-Type": "application/json"}).decode())["prompt_id"]


def poll_history(pid, poll=3.0, timeout=1800):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            rec = json.loads(_http("GET", "/history/" + pid, timeout=30).decode()).get(pid)
        except Exception:
            rec = None
        if rec and ((rec.get("outputs") or {}) or (rec.get("status") or {}).get("completed") is True):
            return rec
        time.sleep(poll)
    return None


def fetch_first_image(rec):
    for _nid, out in (rec.get("outputs") or {}).items():
        for im in (out.get("images") or []):
            q = urllib.parse.urlencode({"filename": im.get("filename", ""),
                                        "subfolder": im.get("subfolder", "") or "",
                                        "type": im.get("type", "output")})
            return im.get("filename"), _http("GET", "/view?" + q, timeout=120)
    return None, None


# ---------------------------------------------------------------------------
# 工作流：生产 img2img 图 + 一条 ReferenceLatent 支链（= 底图 + 单参考）
#   · 尺寸：ImageScaleToTotalPixels(area, 1.0MP) —— 方形裁切 ⇒ 1024×1024，与 Flux2Scheduler 同步
#   · 采样：20 步 / guidance 4 / euler（与生产同一口径）
#   · denoise：SplitSigmasDenoise（<1 才保留原裁切的构图/表情/光线）
# ---------------------------------------------------------------------------
def build_graph(px, steps, guidance, denoise, seed):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux2_dev_fp8mixed.safetensors",
                                                     "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "mistral_3_small_flux2_fp8.safetensors",
                                                     "type": "flux2", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "full_encoder_small_decoder.safetensors"}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": ""}},
        "7": {"class_type": "FluxGuidance", "inputs": {"conditioning": ["6", 0], "guidance": guidance}},
        "10": {"class_type": "LoadImage", "inputs": {"image": ""}},
        "11": {"class_type": "ImageScaleToTotalPixels",
               "inputs": {"image": ["10", 0], "upscale_method": "area", "megapixels": 1.0, "resolution_steps": 1}},
        "12": {"class_type": "VAEEncode", "inputs": {"pixels": ["11", 0], "vae": ["3", 0]}},
        "20": {"class_type": "LoadImage", "inputs": {"image": ""}},
        "21": {"class_type": "ImageScaleToTotalPixels",
               "inputs": {"image": ["20", 0], "upscale_method": "area", "megapixels": 1.0, "resolution_steps": 1}},
        "22": {"class_type": "VAEEncode", "inputs": {"pixels": ["21", 0], "vae": ["3", 0]}},
        "30": {"class_type": "ReferenceLatent", "inputs": {"conditioning": ["7", 0], "latent": ["22", 0]}},
        "40": {"class_type": "BasicGuider", "inputs": {"model": ["1", 0], "conditioning": ["30", 0]}},
        "41": {"class_type": "Flux2Scheduler", "inputs": {"steps": steps, "width": px, "height": px}},
        "42": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "43": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "44": {"class_type": "SplitSigmasDenoise", "inputs": {"sigmas": ["41", 0], "denoise": denoise}},
        "45": {"class_type": "SamplerCustomAdvanced",
               "inputs": {"noise": ["43", 0], "guider": ["40", 0], "sampler": ["42", 0],
                          "sigmas": ["44", 1], "latent_image": ["12", 0]}},
        "46": {"class_type": "VAEDecode", "inputs": {"samples": ["45", 0], "vae": ["3", 0]}},
        "47": {"class_type": "SaveImage", "inputs": {"images": ["46", 0], "filename_prefix": "weaveora_facefix"}},
    }


FACE_PROMPT = (
    "The person in this image must be exactly the same person as in Reference Image 1: identical facial "
    "features, face shape, jawline, eyes, nose, mouth, hairstyle, age and costume. Keep this image's pose, "
    "head angle, gaze direction, expression, framing and lighting unchanged. Photorealistic close-up "
    "portrait, real skin texture and pores, natural asymmetric details, sharp eyes, cinematic film look."
)


def run_face(crop_bgr, ref_path, px, denoise, seed, steps=20, guidance=4.0, timeout=1800):
    graph = build_graph(px, steps, guidance, denoise, seed)
    _ok, buf = cv2.imencode(".png", crop_bgr)
    ts = int(time.time() * 1000)
    base_name = upload_image(buf.tobytes(), "facefix_base_%d.png" % ts)
    with open(ref_path, "rb") as fh:
        ref_name = upload_image(fh.read(), "facefix_ref_%d_%s" % (ts, os.path.basename(ref_path)))
    if not base_name or not ref_name:
        raise SystemExit("!! 底图/参考图上传失败")
    graph["10"]["inputs"]["image"] = base_name
    graph["20"]["inputs"]["image"] = ref_name
    graph["6"]["inputs"]["text"] = FACE_PROMPT
    cid = "facefix-%d-%d" % (ts, int(np.random.randint(1, 10 ** 6)))
    pid = post_prompt(graph, cid)
    rec = poll_history(pid, poll=3.0, timeout=timeout)
    if not rec:
        raise SystemExit("!! 超时未出结果（%s）" % pid)
    st = ((rec.get("status") or {}).get("status_str")) or "?"
    if st != "success":
        raise SystemExit("!! 采样未成功：status=%s %s" % (st, json.dumps(rec.get("status"))[:400]))
    fn, data = fetch_first_image(rec)
    if not data:
        raise SystemExit("!! 出图节点无输出")
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR), fn


def _queue_snapshot():
    """返回 (有哪些非 facefix 任务在跑/排队, 明细)。用来给生产任务让路。"""
    try:
        d = json.loads(_http("GET", "/queue", timeout=15).decode())
    except Exception:
        return False, "队列读不到"
    prod = []
    for it in list(d.get("queue_running") or []) + list(d.get("queue_pending") or []):
        q = it[2]
        save = [v["inputs"].get("filename_prefix") for v in q.values() if v["class_type"] == "SaveImage"]
        if not any(s == "weaveora_facefix" for s in save):
            prod.append(save)
    return bool(prod), str(prod)


def yield_to_prod(wait_min, idx):
    """有生产任务（非 facefix）就先让路，最多等 wait_min 分钟。返回是否可以开工。"""
    if wait_min <= 0:
        return True
    t0 = time.time()
    while time.time() - t0 < wait_min * 60:
        busy, detail = _queue_snapshot()
        if not busy:
            return True
        print("   [让路] 盒上有生产任务在跑/排队 %s → 等 20s（第 %d 张脸，已等 %.0fs）"
              % (detail, idx, time.time() - t0), flush=True)
        time.sleep(20)
    return False


def feather_ellipse(side, rx=0.46, ry=0.50, sigma=0.06):
    m = np.zeros((side, side), np.uint8)
    cv2.ellipse(m, (side // 2, side // 2), (max(1, int(side * rx)), max(1, int(side * ry))),
                0, 0, 360, 255, -1)
    m = cv2.GaussianBlur(m, (0, 0), max(1.0, side * sigma))
    return (m.astype(np.float32) / 255.0)[:, :, None]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--img", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ref", action="append", default=[])
    ap.add_argument("--denoise", type=float, default=0.55)
    ap.add_argument("--expand", type=float, default=2.0, help="裁切边长 = expand × 脸框长边")
    ap.add_argument("--px", type=int, default=1024, help="重绘分辨率（方形）")
    ap.add_argument("--seed", type=int, default=20260925)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--guidance", type=float, default=4.0)
    ap.add_argument("--only", default="", help="只修某些角色（名字，逗号分隔）")
    ap.add_argument("--assign", default="cos", choices=["cos", "pos"],
                    help="哪张脸用谁的参考图：cos=按人脸相似度认领（默认）；pos=按左→右硬指定（用 --map）")
    ap.add_argument("--map", default="", help="pos 模式下的左→右角色顺序，如 宝玉,可卿,警幻（默认取 --ref 给的顺序）")
    ap.add_argument("--dump", default="/tmp/wvf/fix")
    ap.add_argument("--url", default="http://127.0.0.1:8001")
    ap.add_argument("--yield-check", type=float, default=2.0,
                    help="每张脸开跑前先看队列：有非 facefix 任务（生产任务）就先等，最多等这么多分钟；0=不让路")
    a = ap.parse_args()
    global COMFY
    COMFY = a.url.rstrip("/")
    os.makedirs(a.dump, exist_ok=True)

    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=AUX,
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))

    def faces_of(path):
        img = cv2.imread(path)
        if img is None:
            raise SystemExit("读不到图：%s" % path)
        return img, app.get(img)

    refs, emb, ref_path = [], {}, {}
    for spec in a.ref:
        n, p = spec.split("=", 1)
        _i, fs = faces_of(p)
        if not fs:
            raise SystemExit("!! 参考图检不到脸：%s" % p)
        emb[n] = fs[0].normed_embedding
        refs.append(n)
        ref_path[n] = p
    if not refs:
        raise SystemExit("!! 至少一张 --ref 名字=路径")

    canvas, fs = faces_of(a.img)
    H, W = canvas.shape[:2]

    def report(tag, faces):
        print("== %s：脸 %d 张 ==" % (tag, len(faces)))
        for i, f in enumerate(faces, 1):
            x0, y0, x1, y1 = [float(v) for v in f.bbox]
            sims = {k: float(np.dot(f.normed_embedding, v)) for k, v in emb.items()}
            best = max(sims, key=sims.get)
            print("   #%d 宽=%3.0f x=%.2f y=%.2f → %-8s %s"
                  % (i, x1 - x0, (x0 + x1) / 2 / W, (y0 + y1) / 2 / H, best,
                     {k: round(v, 3) for k, v in sims.items()}))

    fs = sorted(fs, key=lambda f: float(f.bbox[0]))
    report("修复前 " + os.path.basename(a.img), fs)

    # 认领：每张脸用哪张定妆照
    #   cos = 按 ArcFace 相似度取最大（脸小的时候这个信号很弱 —— 第 4 镜实测三张脸全 <0.26，
    #         argmax 全指到同一个人；那时就该改用 pos）
    #   pos = 按左→右硬指定（镜次设计就是左→右 宝玉/可卿/警幻），_map_pos
    order = [x.strip() for x in (a.map or ",".join(refs)).split(",") if x.strip()]
    own = []
    for i, f in enumerate(fs):
        sims = {k: float(np.dot(f.normed_embedding, v)) for k, v in emb.items()}
        if a.assign == "pos":
            who = order[i % len(order)] if order else max(sims, key=sims.get)
        else:
            who = max(sims, key=sims.get)
        own.append(who)

    only = set(x.strip() for x in a.only.split(",") if x.strip())
    done = []
    for i, (f, who) in enumerate(zip(fs, own), 1):
        if not yield_to_prod(a.yield_check, i):
            print("   盒子一直被生产任务占着 → 先停在这里（已修 %d 张）" % len(done), flush=True)
            break
        x0, y0, x1, y1 = [float(v) for v in f.bbox]
        sims = {k: float(np.dot(f.normed_embedding, v)) for k, v in emb.items()}
        if only and who not in only:
            print("   #%d 认领=%s → 跳过（--only）" % (i, who))
            continue
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        side = int(round(max(x1 - x0, y1 - y0) * a.expand))
        side = max(64, min(side, W, H))
        x = max(0, min(int(round(cx - side / 2.0)), W - side))
        y = max(0, min(int(round(cy - side / 2.0)), H - side))
        crop = canvas[y:y + side, x:x + side].copy()
        big = cv2.resize(crop, (a.px, a.px), interpolation=cv2.INTER_LANCZOS4)
        cv2.imwrite(os.path.join(a.dump, "face%d_%s_before.png" % (i, who)), crop)
        cv2.imwrite(os.path.join(a.dump, "face%d_%s_input_%dpx.png" % (i, who, a.px)), big)
        t0 = time.time()
        print("   #%d 认领=%s（%s）cos=%.3f → 重绘 %dpx denoise=%.2f …"
              % (i, who, a.assign, sims.get(who, float("nan")), a.px, a.denoise), flush=True)
        new_big, fn = run_face(big, ref_path[who], a.px, a.denoise, a.seed + i, a.steps, a.guidance)
        new = cv2.resize(new_big, (side, side), interpolation=cv2.INTER_AREA)
        cv2.imwrite(os.path.join(a.dump, "face%d_%s_after.png" % (i, who)), new)
        alpha = feather_ellipse(side)
        canvas[y:y + side, x:x + side] = (canvas[y:y + side, x:x + side].astype(np.float32) * (1 - alpha)
                                          + new.astype(np.float32) * alpha).astype(np.uint8)
        print("      ✓ %s（%.0fs，%s）" % (who, time.time() - t0, fn), flush=True)
        done.append(who)

    cv2.imwrite(a.out, canvas)
    print("修复图已写：%s（改了 %d 张脸：%s）" % (a.out, len(done), ", ".join(done) or "无"))
    _c2, fs2 = faces_of(a.out)
    report("修复后 " + os.path.basename(a.out), sorted(fs2, key=lambda f: float(f.bbox[0])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
