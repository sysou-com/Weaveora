#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""seedvr_test.py —— 用刚下好的 SeedVR2 把关键帧放大到 2×，量耗时与锐度增益
用法：/opt/weaveora/ComfyUI/venv/bin/python seedvr_test.py <输入图> [长边目标=3328] [steps=1]
"""
import json, os, sys, time, urllib.request
from PIL import Image, ImageFilter
import numpy as np

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"
MODEL = "seedvr2_3b_fp8_e4m3fn.safetensors"
VAE = "ema_vae_fp16.safetensors"


def api(path, body=None, timeout=120):
    req = urllib.request.Request(COMFY + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def graph(src_name, w, h, steps):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": MODEL, "weight_dtype": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "12": {"class_type": "LoadImage", "inputs": {"image": src_name}},
        "13": {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos",
                                                     "width": w, "height": h, "crop": "disabled"}},
        "14": {"class_type": "SeedVR2Preprocess", "inputs": {"resized_images": ["13", 0]}},
        "15": {"class_type": "VAEEncode", "inputs": {"pixels": ["14", 0], "vae": ["3", 0]}},
        "16": {"class_type": "SeedVR2Conditioning", "inputs": {"model": ["1", 0], "vae_conditioning": ["15", 0]}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["16", 0], "negative": ["16", 1],
                                                   "latent_image": ["15", 0], "seed": 1234, "steps": steps,
                                                   "cfg": 1.0, "sampler_name": "euler", "scheduler": "simple",
                                                   "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "17": {"class_type": "SeedVR2PostProcessing", "inputs": {"images": ["10", 0],
                                                                "original_resized_images": ["14", 0],
                                                                "color_correction_method": "lab"}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["17", 0], "filename_prefix": "wv5_seedvr"}},
    }


def hf(im, long_side=832):
    """下采样到 long_side 后的高频能量（|图-高斯模糊|），用于比较"喂给 I2V 的清晰度"。"""
    w = long_side
    h = int(round(long_side * im.height / im.width))
    g = im.convert("L").resize((w, h))
    a = np.asarray(g, dtype=np.float32)
    b = np.asarray(g.filter(ImageFilter.GaussianBlur(1.5)), dtype=np.float32)
    return float(np.abs(a - b).mean())


def main():
    src = sys.argv[1]
    long_side = int(sys.argv[2]) if len(sys.argv) > 2 else 3328
    steps = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    src_name = os.path.basename(src)
    if not os.path.exists(os.path.join(INP, src_name)):
        Image.open(src).save(os.path.join(INP, src_name))
    im0 = Image.open(src)
    w = long_side
    h = int(round(long_side * im0.height / im0.width))
    print("源 %s %s → 目标 %dx%d  steps=%d" % (src_name, im0.size, w, h, steps), flush=True)
    g = graph(src_name, w, h, steps)
    t0 = time.time()
    pid = api("/prompt", {"prompt": g, "client_id": "wv5"})["prompt_id"]
    res = None
    while time.time() - t0 < 1800:
        time.sleep(4)
        try:
            h2 = api("/history/" + pid, timeout=30)
        except Exception:
            continue
        if pid in h2:
            res = h2[pid]
            break
    el = time.time() - t0
    st = (res or {}).get("status") or {}
    files = []
    for _n, o in ((res or {}).get("outputs") or {}).items():
        for im in o.get("images", []):
            p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
            if os.path.exists(p):
                files.append(p)
    print("耗时 %.0fs  status=%s  files=%s" % (el, st.get("status_str"), files), flush=True)
    if files:
        out = Image.open(files[0])
        print("输出尺寸 %s" % (out.size,), flush=True)
        print("锐度对比（先各自下采样到 832 长边，再算高频能量）：", flush=True)
        print("   原生 1664×928          → %.2f" % hf(im0), flush=True)
        print("   SeedVR2 放大后再降采样 → %.2f" % hf(out), flush=True)
        print("   增益 %.0f%%" % ((hf(out) / hf(im0) - 1) * 100), flush=True)
    else:
        print("STATUS:", json.dumps(st, ensure_ascii=False)[:600], flush=True)


if __name__ == "__main__":
    main()
