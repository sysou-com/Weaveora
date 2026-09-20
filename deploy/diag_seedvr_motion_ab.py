#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定案实验：motion 出片的「糊」到底能不能靠 SeedVR2 补回来？

对照（同一帧、同一目标尺寸 = 成片画布 1280×720）：
  B 基线 = 832×464 → bicubic 1280×720      （= 现在 ConcatService `scale=1280:720` 的真实行为）
  A 实验 = 832×464 → SeedVR2 3B → 1280×720 （单帧，steps=1）
  C 参照 = 同镜关键帧 1664×928 → LANCZOS 1280×720（"关键帧级别的清晰度"上限参照）

判据：
  · 清晰度 hf = mean|I − gaussian_blur(1.5)|（同尺寸直接可比；本仓库既有口径）
  · 局部 hf（脸区域 100% 裁切）——整帧 hf 会被大面积平坦背景稀释
  · 保真度：A 与 B 的平均像素差（SeedVR2 若大幅改画面 → 差值会明显变大）
产物：
  · wvab2_A_seedvr_720p.png / B_bicubic_720p.png / C_keyframe_720p.png
  · wvab2_compare.png（整帧三连） / wvab2_crop_compare.png（同坐标 100% 裁切三连）

安全：开跑前查 ComfyUI /queue 与显存；非空或 <8GiB 空闲就等（只在队列空时才提交，不抢生产任务；不主动 /free）。
不写业务库、不改引擎配置。
"""
import json
import os
import sys
import time
import urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"
MODEL = "seedvr2_3b_fp8_e4m3fn.safetensors"
VAE = "ema_vae_fp16.safetensors"

SRC_MOTION = os.path.join(OUT, "weaveora_mot_dec2a5_00041_.png")   # 最近一次成功 motion（第1镜）中间帧
SRC_KEYFRAME = os.path.join(OUT, "weaveora_wf_shot1_00011_.png")   # 同镜关键帧（今天 23:04）
TARGET = (1280, 720)                                                # = ConcatService.canvasFor("16:9")


def api(path, body=None, timeout=120):
    req = urllib.request.Request(
        COMFY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def wait_idle(max_wait_s=1800, min_free_gib=8.0, poll=20):
    # 阈值 8 GiB 的依据：SeedVR2 3B fp8 ≈3.4 GB + ema_vae 0.5 GB + 单帧 1280×720 激活，
    # 远小于 8 GiB；故意**不调 /free**，避免把用户缓存的 Qwen-Image 踢出显存（下次出图变慢）。
    """等 ComfyUI 队列清空 + 显存充足（不抢正在跑的生产任务）。"""
    t0 = time.time()
    while time.time() - t0 < max_wait_s:
        try:
            q = json.loads(urllib.request.urlopen(COMFY + "/queue", timeout=15).read())
            running = len(q.get("queue_running") or [])
            pending = len(q.get("queue_pending") or [])
            st = json.loads(urllib.request.urlopen(COMFY + "/system_stats", timeout=15).read())
            dev = (st.get("devices") or [{}])[0]
            free = float(dev.get("vram_free") or 0) / 1073741824.0
            if running == 0 and pending == 0 and free >= min_free_gib:
                print("[guard] 队列空、显存 %.1f GiB ≥ %.0f → 开始" % (free, min_free_gib), flush=True)
                return True
            print("[guard] 忙：running=%d pending=%d free=%.1f GiB（等待）" % (running, pending, free), flush=True)
        except Exception as e:
            print("[guard] 探测失败（继续等）: %s" % e, flush=True)
        time.sleep(poll)
    print("[guard] 等待超时 → 放弃本次（不影响生产）", flush=True)
    return False


def seedvr_graph(src_name, w, h, steps=1, prefix="wvab2_A_seedvr"):
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
        "11": {"class_type": "SaveImage", "inputs": {"images": ["17", 0], "filename_prefix": prefix}},
    }


def hf(im):
    g = np.asarray(im.convert("L"), dtype=np.float32)
    b = np.asarray(Image.fromarray(g.astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.5)), dtype=np.float32)
    return float(np.abs(g - b).mean())


def stack(images, labels, path):
    w = max(i.width for i in images)
    gap = 10
    total_h = sum(i.height for i in images) + gap * (len(images) - 1) + 26 * len(images)
    canvas = Image.new("RGB", (w, total_h), (255, 255, 255))
    d = ImageDraw.Draw(canvas)
    y = 0
    for im, lab in zip(images, labels):
        d.text((6, y + 6), lab, fill=(0, 0, 0))
        y += 26
        canvas.paste(im, (0, y))
        y += im.height + gap
    canvas.save(path)
    return path


def main():
    for p in (SRC_MOTION, SRC_KEYFRAME):
        if not os.path.exists(p):
            print("MISSING 输入: %s" % p, flush=True)
            return 2

    mot = Image.open(SRC_MOTION).convert("RGB")
    kf = Image.open(SRC_KEYFRAME).convert("RGB")
    print("输入：motion 帧 %s %s ｜ 关键帧 %s %s ｜ 目标 %s"
          % (os.path.basename(SRC_MOTION), mot.size, os.path.basename(SRC_KEYFRAME), kf.size, TARGET), flush=True)

    # ---- B 基线（无 GPU）：bicubic 放大（= 成片 ffmpeg scale 的默认插值）----
    B = mot.resize(TARGET, Image.BICUBIC)
    B.save(os.path.join(OUT, "wvab2_B_bicubic_720p.png"))
    # ---- C 参照：关键帧降到成片画布 ----
    C = kf.resize(TARGET, Image.LANCZOS)
    C.save(os.path.join(OUT, "wvab2_C_keyframe_720p.png"))

    # ---- A 实验：SeedVR2 ----
    if not wait_idle():
        return 3
    src_name = os.path.basename(SRC_MOTION)
    if not os.path.exists(os.path.join(INP, src_name)):
        mot.save(os.path.join(INP, src_name))
    g = seedvr_graph(src_name, TARGET[0], TARGET[1], steps=1)
    t0 = time.time()
    pid = api("/prompt", {"prompt": g, "client_id": "wvab2"})["prompt_id"]
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
    print("SeedVR2 耗时 %.0fs status=%s files=%s" % (el, st.get("status_str"), files), flush=True)
    if not files:
        print("STATUS:", json.dumps(st, ensure_ascii=False)[:800], flush=True)
        return 4
    A = Image.open(files[0]).convert("RGB")
    if A.size != TARGET:
        A = A.resize(TARGET, Image.LANCZOS)
    A.save(os.path.join(OUT, "wvab2_A_seedvr_720p.png"))

    # ---- 指标 ----
    # 脸区域（第1镜是「两人在纱帐内」中近景；取中上部 100% 裁切做局部锐度）
    box = (int(TARGET[0] * 0.30), int(TARGET[1] * 0.12), int(TARGET[0] * 0.70), int(TARGET[1] * 0.52))
    rows = [("B bicubic  (现状)", B), ("A SeedVR2  (实验)", A), ("C 关键帧    (参照上限)", C)]
    print("\n=== 指标（成片画布 %dx%d）===" % TARGET, flush=True)
    print("%-24s %8s %10s" % ("arm", "hf(整帧)", "hf(脸区)"), flush=True)
    for name, im in rows:
        print("%-24s %8.3f %10.3f" % (name, hf(im), hf(im.crop(box))), flush=True)
    print("原生 motion 帧 832×464 的 hf = %.3f（仅供参考，尺寸不同不可直接比）" % hf(mot), flush=True)
    diff = float(np.abs(np.asarray(A, dtype=np.float32) - np.asarray(B, dtype=np.float32)).mean())
    print("保真度：|A−B| 平均像素差 = %.2f /255（越小越说明 SeedVR2 没乱改画面）" % diff, flush=True)

    # ---- 对比图 ----
    p1 = stack([B, A, C], ["B  bicubic 832x464 -> 1280x720 (current pipeline)",
                           "A  SeedVR2 3B 832x464 -> 1280x720 (this test)",
                           "C  keyframe 1664x928 -> 1280x720 (reference ceiling)"],
               os.path.join(OUT, "wvab2_compare.png"))
    crops = [im.crop(box) for _, im in rows]
    p2 = stack(crops, ["B bicubic (100%% crop)", "A SeedVR2 (100%% crop)", "C keyframe (100%% crop)"],
               os.path.join(OUT, "wvab2_crop_compare.png"))
    print("\n产物：\n  %s\n  %s\n  %s\n  %s\n  %s"
          % (os.path.join(OUT, "wvab2_A_seedvr_720p.png"), os.path.join(OUT, "wvab2_B_bicubic_720p.png"),
             os.path.join(OUT, "wvab2_C_keyframe_720p.png"), p1, p2), flush=True)
    print("DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
