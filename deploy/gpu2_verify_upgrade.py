# -*- coding: utf-8 -*-
"""2511/2512 升级后的三步核实（后台跑，结果写 /tmp/verify_upgrade.log）：
   ① 字节 + .done + safetensors 头 + sha256（入 manifest）
   ② 最小加载测试：用 2511 fp8mixed 跑 256×256 / 4 步（只验证能加载+能建图，不求质量）
   ③ 顺带验证 2512 基座同样可加载
"""
import hashlib
import json
import os
import struct
import time
import urllib.request

COMFY = "http://127.0.0.1:8001"
STORE = "/addDisk/weaveora/models/diffusion_models"
FILES = [
    ("qwen_image_edit_2511_fp8mixed.safetensors", 20533762817),
    ("qwen_image_2512_fp8_e4m3fn.safetensors", 20430679144),
]


def log(m):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), m)
    print(line, flush=True)
    open("/tmp/verify_upgrade.log", "a", encoding="utf-8").write(line + "\n")


def post(path, body, timeout=1800):
    req = urllib.request.Request(COMFY + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())


def get(path, timeout=120):
    return json.loads(urllib.request.urlopen(COMFY + path, timeout=timeout).read().decode())


log("=== ① 文件校验 ===")
for name, want in FILES:
    p = os.path.join(STORE, name)
    if not os.path.exists(p):
        log("FAIL %s 不存在" % name); continue
    got = os.path.getsize(p)
    done = os.path.exists(p + ".done")
    try:
        fh = open(p, "rb"); n = struct.unpack("<Q", fh.read(8))[0]
        assert 0 < n < 200_000_000; hdr = json.loads(fh.read(n)); tensors = len(hdr)
    except Exception as e:
        log("FAIL %s 头异常 %s" % (name, e)); continue
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 24), b""):
            h.update(chunk)
    log("%s  bytes=%s(期望 %s) .done=%s 张量=%d sha256=%s"
        % ("OK  " if got == want else "FAIL", got, want, done, tensors, h.hexdigest()))
    if got == want:
        with open("/opt/weaveora/logs/sha256_manifest.txt", "a", encoding="utf-8") as mf:
            mf.write("%s  %s  %s\n" % (h.hexdigest(), got, name))


def tiny(unet, tag):
    g = {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": unet, "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl_7b_fp8_scaled.safetensors", "type": "qwen_image", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
        "5": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3.0}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": "test"}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": ""}},
        "8": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 256, "height": 256, "batch_size": 1}},
        "9": {"class_type": "KSampler", "inputs": {"model": ["5", 0], "positive": ["6", 0], "negative": ["7", 0],
              "latent_image": ["8", 0], "seed": 1, "steps": 4, "cfg": 2.5, "sampler_name": "euler",
              "scheduler": "simple", "denoise": 1.0}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "verify_" + tag}},
    }
    t0 = time.time()
    try:
        pid = post("/prompt", {"prompt": g, "client_id": "verify"})["prompt_id"]
    except Exception as e:
        log("%s 提交失败：%s" % (tag, str(e)[:200])); return
    while time.time() - t0 < 900:
        time.sleep(6)
        h = get("/history/" + pid)
        if h and pid in h:
            st = (h[pid].get("status") or {}).get("status_str")
            if st == "success":
                log("%s ✅ 加载+出图正常（%.0fs）" % (tag, time.time() - t0))
            else:
                msgs = (h[pid].get("status") or {}).get("messages") or []
                log("%s ❌ %s" % (tag, str(msgs[-1])[:300]))
            return
    log("%s 超时" % tag)


log("=== ② 最小加载测试（256×256 / 4 步，只验能否加载+建图）===")
tiny("qwen_image_edit_2511_fp8mixed.safetensors", "edit2511")
tiny("qwen_image_2512_fp8_e4m3fn.safetensors", "base2512")
log("=== VERIFY_DONE ===")
