#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""整段验证：把第1镜那条 5s/80帧 motion 出片**整段**过 SeedVR2 → 1280×720（成片画布）

对照（同一批原始帧，逐帧一一对应）：
  B 基线 = 832×464 → bicubic 1280×720   （= 现在 ConcatService `scale=1280:720` 的行为）
  A 实验 = 832×464 → SeedVR2 3B → 1280×720（**逐帧独立**跑；本轮不接 SeedVR2TemporalChunk，
           所以下面专门量"帧间抖动"，用数据判断是否需要上 temporal 分块）

判据：
  · hf 整帧 / 脸区（同尺寸直接可比）
  · **帧间抖动** flicker = mean|frame_t − frame_{t−1}|(1280×720)：A 与 B 比，看 SeedVR2 是否放大抖动
  · 产两段 mp4（A/B 各一段，16fps，crf18）供肉眼判闪烁
  · 峰值显存 + 每帧耗时

安全：
  · 每帧提交前查 /queue；一旦有**别人的**pending（生产任务）→ 立刻停手让路（绝不抢卡）
  · 不写业务库、不改引擎配置、不 /free
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np
from PIL import Image, ImageFilter

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"
WORK = "/opt/weaveora/_xfer/wvab3"
MODEL = "seedvr2_3b_fp8_e4m3fn.safetensors"
VAE = "ema_vae_fp16.safetensors"
TARGET = (1280, 720)
FPS = 16.0
PREFIX = "weaveora_mot_dec2a5_"          # 最近一次成功 motion 的 81 帧
MAX_FRAMES = int(os.environ.get("WVAB3_MAX_FRAMES", "81"))


def api(path, body=None, timeout=180):
    req = urllib.request.Request(
        COMFY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def queue_state():
    q = json.loads(urllib.request.urlopen(COMFY + "/queue", timeout=15).read())
    return len(q.get("queue_running") or []), len(q.get("queue_pending") or [])


def vram_used_gib():
    st = json.loads(urllib.request.urlopen(COMFY + "/system_stats", timeout=15).read())
    dev = (st.get("devices") or [{}])[0]
    total = float(dev.get("vram_total") or 0) / 1073741824.0
    free = float(dev.get("vram_free") or 0) / 1073741824.0
    return total - free, free


def upload(path, name):
    with open(path, "rb") as fh:
        data = fh.read()
    boundary = "----wvab3"
    body = (b"--" + boundary.encode() + b"\r\n"
            b'Content-Disposition: form-data; name="image"; filename="' + name.encode() + b'"\r\n'
            b"Content-Type: image/png\r\n\r\n" + data + b"\r\n--" + boundary.encode() + b"--\r\n")
    req = urllib.request.Request(COMFY + "/upload/image", data=body,
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    return json.loads(urllib.request.urlopen(req, timeout=120).read()).get("name")


def graph(src_name, prefix, steps=1):
    w, h = TARGET
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


def ffmpeg_frames_to_mp4(frame_paths, out, fps=FPS, crf=18):
    if not frame_paths:
        return False
    lst = out + ".txt"
    with open(lst, "w") as fh:
        for p in frame_paths:
            fh.write("file '%s'\n" % p)
    cmd = ["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-r", str(fps), "-i", lst,
           "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", str(crf), out]
    try:
        subprocess.run(cmd, check=True, timeout=600)
        return True
    except Exception as e:
        print("[ffmpeg] 合成失败 %s: %s" % (out, e), flush=True)
        return False


def main():
    os.makedirs(WORK, exist_ok=True)
    srcs = sorted(p for p in os.listdir(OUT) if p.startswith(PREFIX) and p.endswith(".png"))
    srcs = srcs[:MAX_FRAMES]
    if not srcs:
        print("找不到输入帧（前缀 %s）" % PREFIX, flush=True)
        return 2
    print("整段验证：%d 帧，目标 %s，逐帧独立跑 SeedVR2" % (len(srcs), TARGET), flush=True)

    box = (int(TARGET[0] * 0.30), int(TARGET[1] * 0.12), int(TARGET[0] * 0.70), int(TARGET[1] * 0.52))
    A_paths, B_paths = [], []
    hfA, hfB, hfcA, hfcB = [], [], [], []
    peak_used = 0.0
    t_start = time.time()
    per_frame = []

    for i, name in enumerate(srcs):
        running, pending = queue_state()
        if running > 0 or pending > 0:
            print("[让路] 第 %d 帧前发现有生产任务在排队（running=%d pending=%d）→ 停手"
                  % (i, running, pending), flush=True)
            break
        src = os.path.join(OUT, name)
        base = Image.open(src).convert("RGB")
        if base.size != TARGET:
            base = base.resize(TARGET, Image.BICUBIC)
        bpath = os.path.join(WORK, "B_%03d.png" % i)
        base.save(bpath)
        B_paths.append(bpath)

        sname = upload(os.path.join(OUT, name), name)
        t0 = time.time()
        pid = api("/prompt", {"prompt": graph(sname, "wvab3_A_%03d" % i), "client_id": "wvab3"})["prompt_id"]
        res = None
        while time.time() - t0 < 600:
            time.sleep(2)
            try:
                h2 = api("/history/" + pid, timeout=30)
            except Exception:
                continue
            if pid in h2:
                res = h2[pid]
                break
        el = time.time() - t0
        per_frame.append(el)
        files = []
        for _n, o in ((res or {}).get("outputs") or {}).items():
            for im in o.get("images", []):
                p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
                if os.path.exists(p):
                    files.append(p)
        if not files:
            print("[warn] 第 %d 帧无输出：%s" % (i, json.dumps((res or {}).get("status") or {}, ensure_ascii=False)[:300]),
                  flush=True)
            continue
        a = Image.open(files[0]).convert("RGB")
        if a.size != TARGET:
            a = a.resize(TARGET, Image.LANCZOS)
        apath = os.path.join(WORK, "A_%03d.png" % i)
        a.save(apath)
        A_paths.append(apath)
        hfA.append(hf(a)); hfB.append(hf(base))
        hfcA.append(hf(a.crop(box))); hfcB.append(hf(base.crop(box)))
        try:
            used, free = vram_used_gib()
            peak_used = max(peak_used, used)
        except Exception:
            pass
        if i % 10 == 0 or i == len(srcs) - 1:
            print("  [%d/%d] %.1fs/帧  峰值占用 %.1f GiB" % (i + 1, len(srcs), el, peak_used), flush=True)

    total = time.time() - t_start
    print("\n=== 整段结果 ===", flush=True)
    print("帧数 %d/%d ｜ 总耗时 %.0fs ｜ 平均 %.1fs/帧 ｜ 峰值显存占用 %.1f GiB"
          % (len(A_paths), len(srcs), total, (sum(per_frame) / max(1, len(per_frame))), peak_used), flush=True)
    if not A_paths:
        return 3
    hfA, hfB = np.array(hfA), np.array(hfB)
    hfcA, hfcB = np.array(hfcA), np.array(hfcB)
    print("hf 整帧：B %.3f±%.3f → A %.3f±%.3f（%+.0f%%）"
          % (hfB.mean(), hfB.std(), hfA.mean(), hfA.std(), 100 * (hfA.mean() / hfB.mean() - 1)), flush=True)
    print("hf 脸区：B %.3f±%.3f → A %.3f±%.3f（%+.0f%%）"
          % (hfcB.mean(), hfcB.std(), hfcA.mean(), hfcA.std(), 100 * (hfcA.mean() / hfcB.mean() - 1)), flush=True)

    # 帧间抖动：同尺寸下相邻帧差分均值（越大越抖/越闪）
    def flicker(paths):
        prev, d = None, []
        for p in paths:
            arr = np.asarray(Image.open(p).convert("L").resize((640, 360)), dtype=np.float32)
            if prev is not None:
                d.append(float(np.abs(arr - prev).mean()))
            prev = arr
        return np.array(d)

    fA, fB = flicker(A_paths), flicker(B_paths[:len(A_paths)])
    print("帧间抖动（相邻帧差分）：B %.3f±%.3f → A %.3f±%.3f（%+.0f%%）"
          % (fB.mean(), fB.std(), fA.mean(), fA.std(), 100 * (fA.mean() / max(1e-6, fB.mean()) - 1)), flush=True)
    print("  ↑ 这条最关键：若 A 明显大于 B，说明逐帧独立跑在抖动，需要上 SeedVR2TemporalChunk", flush=True)

    mA = os.path.join(WORK, "wvab3_A_seedvr_1280x720.mp4")
    mB = os.path.join(WORK, "wvab3_B_bicubic_1280x720.mp4")
    okA = ffmpeg_frames_to_mp4(A_paths, mA)
    okB = ffmpeg_frames_to_mp4(B_paths[:len(A_paths)], mB)
    print("产物：\n  %s %s\n  %s %s\n  帧目录 A=%s B=%s"
          % (mA, "(ok)" if okA else "(失败)", mB, "(ok)" if okB else "(失败)", WORK, WORK), flush=True)
    print("DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
