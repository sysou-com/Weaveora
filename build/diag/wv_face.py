#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_face.py —— 脸部局部重绘（FaceDetailer 自搭版），只改脸、不动构图
用法：/root/venv/bin/python wv_face.py <底图> <定妆照1> <定妆照2> [--expand 2.5] [--canvas 512]
           [--denoise 0.6] [--steps 20] [--cfg 4.0] [--top 2] [--out 前缀]
"""
import argparse, json, os, re, shutil, struct, sys, time, urllib.request
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from insightface.app import FaceAnalysis

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"
SRC_GRAPH = OUT + "/weaveora_wf_shot6_00022_.png"   # 取结构骨架（UNETLoader/CLIP/VAE/shift）

app = FaceAnalysis(name="buffalo_l", root="/tmp/diag/fa", providers=["CPUExecutionProvider"])
app.prepare(ctx_id=-1, det_size=(640, 640))


def api(path, body=None, timeout=60):
    req = urllib.request.Request(COMFY + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def detect(path):
    import cv2
    img = cv2.imread(path)
    if img is None:
        img = np.array(Image.open(path).convert("RGB"))[:, :, ::-1]
    return app.get(img)


def png_prompt(path):
    raw = open(path, "rb").read()
    i, meta = 8, {}
    while i < len(raw):
        ln = struct.unpack(">I", raw[i:i + 4])[0]
        typ = raw[i + 4:i + 8]
        if typ == b"tEXt":
            k, _, v = raw[i + 8:i + 8 + ln].partition(b"\x00")
            meta[k.decode()] = v.decode("utf8", "ignore")
        if typ == b"IDAT":
            break
        i += 12 + ln
    return json.loads(meta["prompt"])


def base_graph():
    g = png_prompt(SRC_GRAPH)
    g.pop("_comment", None)
    # 去掉所有参考图/坐标相关：只保留 1/2/3/5/9/10/11 骨架
    for k in list(g.keys()):
        ct = g[k].get("class_type")
        if ct in ("LoadImage", "ImageScale", "VAEEncode", "TextEncodeQwenImageEditPlus", "SaveImage"):
            g.pop(k)
    g["wv_crop"] = {"class_type": "LoadImage", "inputs": {"image": ""}}
    g["wv_scale"] = {"class_type": "ImageScale",
                     "inputs": {"image": ["wv_crop", 0], "upscale_method": "lanczos",
                                "width": 512, "height": 512, "crop": "disabled"}}
    g["wv_latent"] = {"class_type": "VAEEncode", "inputs": {"pixels": ["wv_scale", 0], "vae": ["3", 0]}}
    g["wv_ref"] = {"class_type": "LoadImage", "inputs": {"image": ""}}
    g["6"] = {"class_type": "TextEncodeQwenImageEditPlus",
              "inputs": {"clip": ["2", 0], "vae": ["3", 0], "prompt": "",
                         "image1": ["wv_scale", 0], "image2": ["wv_ref", 0], "image3": None}}
    g["7"] = {"class_type": "TextEncodeQwenImageEditPlus",
              "inputs": {"clip": ["2", 0], "vae": ["3", 0], "prompt": "",
                         "image1": ["wv_scale", 0], "image2": ["wv_ref", 0], "image3": None}}
    g["9"]["inputs"].update({"positive": ["6", 0], "negative": ["7", 0], "latent_image": ["wv_latent", 0]})
    g["11"] = {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "wv4x"}}
    return g


POS = ("对着 image1 这块面部区域做“高清重绘”：人物身份、脸型、五官比例严格以 image2 为准，"
       "保持 image1 里原本的姿态、朝向、表情幅度、光照方向与肤色，只把五官细节画清楚"
       "（眼睛结构、眉毛、鼻梁、嘴唇边缘、皮肤质感、发丝）。不要改变构图、不要改变景别、"
       "不要添加人物或配饰、不要出现文字。")
POS_REF_FIRST = ("把 image2 里这块区域重新画成 image1 的那个人：脸型、五官比例、眉眼距离、鼻型、嘴型、"
                 "发际线一律严格照 image1 的人物身份；姿态、朝向、光照方向、肤色明暗沿用 image2。"
                 "只重画面部，不要改变构图与景别、不要添加人物或配饰、不要出现文字。")
NEG = ("模糊, 低质量, 变形, 扭曲, 多余五官, 多张脸, 双人, 文字, 水印, 塑料感, "
       "blurry, deformed, distorted, extra face, two people, watermark, text, plastic skin")


def wait(pid, limit=900):
    t0 = time.time()
    while time.time() - t0 < limit:
        time.sleep(3)
        try:
            h = api("/history/" + pid, timeout=30)
        except Exception:
            continue
        if pid in h:
            st = (h[pid].get("status") or {})
            return h[pid], time.time() - t0, st.get("status_str")
    return None, time.time() - t0, "timeout"


def collect(res):
    fs = []
    for _n, o in (res.get("outputs") or {}).items():
        for im in o.get("images", []):
            p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
            if os.path.exists(p):
                fs.append(p)
    return fs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("refs", nargs="+")
    ap.add_argument("--expand", type=float, default=2.5)
    ap.add_argument("--canvas", type=int, default=512)
    ap.add_argument("--denoise", type=float, default=0.6)
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--cfg", type=float, default=4.0)
    ap.add_argument("--top", type=int, default=2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--ref-first", action="store_true",
                    help="定妆照当 image1（身份主参考）、裁块当 image2")
    a = ap.parse_args()

    base_im = Image.open(a.base).convert("RGB")
    refs = {}
    for r in a.refs:
        p = r if os.path.isabs(r) else os.path.join(INP, r)
        fs = detect(p)
        if fs:
            refs[os.path.basename(p)] = fs[0].normed_embedding
    print("参考定妆照：", list(refs.keys()), flush=True)

    fs = detect(a.base)
    faces = sorted(fs, key=lambda f: -min(f.bbox[2] - f.bbox[0], f.bbox[3] - f.bbox[1]))[:a.top]
    print("检出 %d 张脸，处理前 %d 张" % (len(fs), len(faces)), flush=True)
    out_im = base_im.copy()
    stats = []
    for i, f in enumerate(faces):
        x1, y1, x2, y2 = [int(v) for v in f.bbox]
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        s = max(x2 - x1, y2 - y1) * a.expand
        bx1, by1 = int(max(0, cx - s / 2)), int(max(0, cy - s / 2))
        bx2, by2 = int(min(base_im.width, cx + s / 2)), int(min(base_im.height, cy + s / 2))
        crop = base_im.crop((bx1, by1, bx2, by2))
        cp = os.path.join(INP, "wv4x_face%d.png" % i)
        crop.resize((a.canvas, a.canvas), Image.LANCZOS).save(cp)
        best, bs = None, -2.0
        if f.normed_embedding is not None:
            for n, e in refs.items():
                c = float(np.dot(f.normed_embedding, e))
                if c > bs:
                    best, bs = n, c
        g = base_graph()
        g["wv_crop"]["inputs"]["image"] = "wv4x_face%d.png" % i
        g["wv_ref"]["inputs"]["image"] = best
        g["wv_scale"]["inputs"]["width"] = a.canvas
        g["wv_scale"]["inputs"]["height"] = a.canvas
        g["6"]["inputs"]["prompt"] = POS_REF_FIRST if a.ref_first else POS
        g["7"]["inputs"]["prompt"] = NEG
        if a.ref_first:
            g["6"]["inputs"]["image1"] = ["wv_ref", 0]
            g["6"]["inputs"]["image2"] = ["wv_scale", 0]
            g["7"]["inputs"]["image1"] = ["wv_ref", 0]
            g["7"]["inputs"]["image2"] = ["wv_scale", 0]
        g["9"]["inputs"].update({"steps": a.steps, "cfg": a.cfg, "denoise": a.denoise,
                                 "seed": (a.seed + i) if a.seed else 12345 + i})
        g["11"]["inputs"]["filename_prefix"] = "wv4x_f%d" % i
        for attempt in (1, 2):
            pid = api("/prompt", {"prompt": g, "client_id": "wv4x"})["prompt_id"]
            res, el, st = wait(pid)
            files = collect(res)
            if files:
                break
            print("   [脸%d] 第%d次 %s，重试" % (i, attempt, st), flush=True)
            time.sleep(2)
        if not files:
            print("   [脸%d] 两次都没出图，跳过" % i, flush=True)
            continue
        red = Image.open(files[0]).convert("RGB").resize((bx2 - bx1, by2 - by1), Image.LANCZOS)
        mask = Image.new("L", red.size, 0)
        ImageDraw.Draw(mask).ellipse([0, 0, red.size[0], red.size[1]], fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(max(4, red.size[0] // 14)))
        out_im.paste(red, (bx1, by1), mask)
        # 贴回后重新检测这块脸的相似度
        tmp = os.path.join(OUT, "tmp_face%d.png" % i)
        out_im.save(tmp)
        after = [x for x in detect(tmp) if abs((x.bbox[0] + x.bbox[2]) / 2 - cx) < s and abs((x.bbox[1] + x.bbox[3]) / 2 - cy) < s]
        cos_after = None
        if after and after[0].normed_embedding is not None and best:
            cos_after = float(np.dot(after[0].normed_embedding, refs[best]))
        os.remove(tmp)
        row = {"face": i, "ref": best, "cos_before": round(bs, 3), "cos_after": None if cos_after is None else round(cos_after, 3),
               "box": [bx1, by1, bx2, by2], "secs": round(el, 1)}
        stats.append(row)
        print("   [脸%d] %.0fs 裁块 %dx%d→%d² denoise=%.2f 参考=%s cos %.3f→%s" %
              (i, el, bx2 - bx1, by2 - by1, a.canvas, a.denoise, best, bs,
               "?" if cos_after is None else "%.3f" % cos_after), flush=True)
    fp = os.path.join(OUT, "wv4x_face_%d.png" % int(time.time()))
    out_im.save(fp)
    print("[DONE] %s" % fp, flush=True)
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
