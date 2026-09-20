#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_v28local.py —— 本地 Qwen-Image-Edit 跑 V28 那套「简洁提示词」，再 SeedVR2 放大
用法：/opt/weaveora/ComfyUI/venv/bin/python wv_v28local.py [--steps 40] [--cfg 4.0] [--size 1664x928] [--upscale]
"""
import argparse, json, os, struct, sys, time, urllib.request

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"

POS = ("Cinematic film still, dramatic cinematic lighting, shallow depth of field, 35mm film, "
       "two young figures in Qing-dynasty embroidered silk robes reclining close inside a gauzy canopied bed chamber, "
       "whispers, soft breath, drifting translucent bed curtains, warm amber candlelight flickering, medium close-up, "
       "slow dolly in, shallow depth of field, classic Chinese dreamlike atmosphere, cinematic, film grain, high detail\n"
       "Reference images in order: 1) 宝玉(定妆图); 2) 可卿(定妆图). Each subject MUST strictly match its own reference "
       "image (face / hair / costume / shape); keep subjects distinct and never blend or swap their identities.")
NEG = ("text, watermark, logo, subtitle, modern clothing, modern room, electric lamp, phone, deformed hands, "
       "extra limbs, gore, identifiable real person likeness, caption, signature, blurry, lowres, deformed, "
       "badly drawn, jpeg artifacts, ugly, nsfw, identical faces, face swap, same person repeated, "
       "mixed identities, cloned face")


def api(path, body=None, timeout=120):
    req = urllib.request.Request(COMFY + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def wait(pid, limit=1800):
    t0 = time.time()
    while time.time() - t0 < limit:
        time.sleep(4)
        try:
            h = api("/history/" + pid, timeout=30)
        except Exception:
            continue
        if pid in h:
            return h[pid], time.time() - t0
    return None, time.time() - t0


def outs_of(res):
    fs = []
    for _n, o in ((res or {}).get("outputs") or {}).items():
        for im in o.get("images", []):
            p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
            if os.path.exists(p):
                fs.append(p)
    return fs


def gen_graph(w, h, steps, cfg, seed):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_edit_2511_fp8mixed.safetensors",
                                                     "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl_7b_fp8_scaled.safetensors",
                                                     "type": "qwen_image", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
        "5": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3.1}},
        "12": {"class_type": "LoadImage", "inputs": {"image": "v28ref1.png"}},
        "14": {"class_type": "LoadImage", "inputs": {"image": "v28ref2.png"}},
        "13": {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos",
                                                     "width": w, "height": h, "crop": "disabled"}},
        "15": {"class_type": "VAEEncode", "inputs": {"pixels": ["13", 0], "vae": ["3", 0]}},
        "6": {"class_type": "TextEncodeQwenImageEditPlus", "inputs": {"clip": ["2", 0], "vae": ["3", 0],
                                                                     "prompt": POS, "image1": ["12", 0],
                                                                     "image2": ["14", 0], "image3": None}},
        "7": {"class_type": "TextEncodeQwenImageEditPlus", "inputs": {"clip": ["2", 0], "vae": ["3", 0],
                                                                     "prompt": NEG, "image1": ["12", 0],
                                                                     "image2": ["14", 0], "image3": None}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["5", 0], "positive": ["6", 0], "negative": ["7", 0],
                                                   "latent_image": ["15", 0], "seed": seed, "steps": steps,
                                                   "cfg": cfg, "sampler_name": "euler", "scheduler": "simple",
                                                   "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "wv6_v28local"}},
    }


def up_graph(src_name, tw, th):
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "seedvr2_3b_fp8_e4m3fn.safetensors",
                                                     "weight_dtype": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "ema_vae_fp16.safetensors"}},
        "12": {"class_type": "LoadImage", "inputs": {"image": src_name}},
        "13": {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos",
                                                     "width": tw, "height": th, "crop": "disabled"}},
        "14": {"class_type": "SeedVR2Preprocess", "inputs": {"resized_images": ["13", 0]}},
        "15": {"class_type": "VAEEncode", "inputs": {"pixels": ["14", 0], "vae": ["3", 0]}},
        "16": {"class_type": "SeedVR2Conditioning", "inputs": {"model": ["1", 0], "vae_conditioning": ["15", 0]}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["16", 0], "negative": ["16", 1],
                                                   "latent_image": ["15", 0], "seed": 1234, "steps": 1, "cfg": 1.0,
                                                   "sampler_name": "euler", "scheduler": "simple", "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "17": {"class_type": "SeedVR2PostProcessing", "inputs": {"images": ["10", 0],
                                                                "original_resized_images": ["14", 0],
                                                                "color_correction_method": "lab"}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["17", 0], "filename_prefix": "wv6_v28up"}},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=40)
    ap.add_argument("--cfg", type=float, default=4.0)
    ap.add_argument("--size", default="1664x928")
    ap.add_argument("--seed", type=int, default=5513002932591935757)   # V28 那一镜的 seed
    ap.add_argument("--upscale", action="store_true")
    a = ap.parse_args()
    w, h = [int(x) for x in a.size.lower().split("x")]
    t0 = time.time()
    pid = api("/prompt", {"prompt": gen_graph(w, h, a.steps, a.cfg, a.seed), "client_id": "wv6"})["prompt_id"]
    res, el = wait(pid)
    fs = outs_of(res)
    print("[本地 Qwen-Edit] %dx%d %d步 cfg%.1f  seed=%d  → %.0fs  %s" % (w, h, a.steps, a.cfg, a.seed, el, fs), flush=True)
    if not fs:
        print("STATUS:", json.dumps((res or {}).get("status") or {}, ensure_ascii=False)[:400], flush=True)
        return
    if a.upscale:
        src = os.path.basename(fs[0])
        tw, th = w * 2, h * 2
        t1 = time.time()
        pid2 = api("/prompt", {"prompt": up_graph(src, tw, th), "client_id": "wv6u"})["prompt_id"]
        res2, el2 = wait(pid2)
        fs2 = outs_of(res2)
        print("[SeedVR2 放大] → %dx%d  %.0fs  %s" % (tw, th, el2, fs2), flush=True)
    print("[总耗时] %.0fs" % (time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
