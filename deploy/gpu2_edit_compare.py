# -*- coding: utf-8 -*-
"""关键帧结构对照（后台跑，结果写 /tmp/editcmp.log）：
   A txt2img（只用提示词）           B img2img 0.5（定妆照当底片）
   C Edit-官方结构（参考图→缩放→VAEEncode→当采样起点）   D Edit-旧结构（空 latent 起点，即"鬼图"那一版）
   每个变体：出图 + 与「宝玉定妆照」的人脸相似度（取不到人脸说明是鬼图/糊图）。
"""
import base64, json, math, os, time, urllib.request

COMFY = "http://127.0.0.1:8001"
FACE = "http://127.0.0.1:8093"
OUT = "/opt/weaveora/verify_style"
REF1 = "f091356a-a853-40ac-8b1d-64f2f01ff653.png"    # 宝玉（方案绑定）
REF2 = "775813a9-e43c-4208-8f62-cbff05f230d7.png"    # 可卿（方案绑定）
POS = ("Cinematic film still, dramatic cinematic lighting, 35mm film, view from behind two young figures in "
       "Qing-dynasty silk robes walking cautiously through a thorn-choked wasteland at night, mist ahead")
NEG = "text, watermark, blurry, low quality, distorted, white background, plain backdrop, character sheet"
W, H, STEPS = 640, 352, 16


def log(m):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), m)
    print(line, flush=True)
    open("/tmp/editcmp.log", "a", encoding="utf-8").write(line + "\n")


def post(path, body, timeout=1800):
    req = urllib.request.Request(COMFY + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())


def get(path, timeout=120):
    return json.loads(urllib.request.urlopen(COMFY + path, timeout=timeout).read().decode())


def embed(b):
    req = urllib.request.Request(FACE + "/face/embed",
                                 data=json.dumps({"image_b64": base64.b64encode(b).decode()}).encode(),
                                 headers={"Content-Type": "application/json"})
    d = json.loads(urllib.request.urlopen(req, timeout=300).read().decode())
    return d.get("embedding"), d.get("face_px")


def cos(a, b):
    if not a or not b or len(a) != len(b):
        return None
    s = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)); nb = math.sqrt(sum(y * y for y in b))
    return s / (na * nb) if na and nb else None


def base_nodes(edit, ref_latent):
    g = {
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl_7b_fp8_scaled.safetensors", "type": "qwen_image", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
        "12": {"class_type": "LoadImage", "inputs": {"image": REF1}},
        "14": {"class_type": "LoadImage", "inputs": {"image": REF2}},
        "10": {"class_type": "VAEDecode", "inputs": {"samples": ["9", 0], "vae": ["3", 0]}},
        "11": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "cmp_" + ("edit" if edit else "plain")}},
    }
    if edit:
        g["1"] = {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_edit_fp8_e4m3fn.safetensors", "weight_dtype": "default"}}
        g["5"] = {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3.0}}
        g["6"] = {"class_type": "TextEncodeQwenImageEditPlus", "inputs": {"clip": ["2", 0], "prompt": POS, "vae": ["3", 0], "image1": ["12", 0], "image2": ["14", 0]}}
        g["7"] = {"class_type": "TextEncodeQwenImageEditPlus", "inputs": {"clip": ["2", 0], "prompt": NEG, "vae": ["3", 0], "image1": ["12", 0], "image2": ["14", 0]}}
        if ref_latent:
            g["13"] = {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos", "width": W, "height": H, "crop": "disabled"}}
            g["15"] = {"class_type": "VAEEncode", "inputs": {"pixels": ["13", 0], "vae": ["3", 0]}}
            lat = ["15", 0]
        else:
            g["8"] = {"class_type": "EmptySD3LatentImage", "inputs": {"width": W, "height": H, "batch_size": 1}}
            lat = ["8", 0]
        g["9"] = {"class_type": "KSampler", "inputs": {"model": ["5", 0], "positive": ["6", 0], "negative": ["7", 0],
                   "latent_image": lat, "seed": 4321, "steps": STEPS, "cfg": 3.0, "sampler_name": "euler",
                   "scheduler": "simple", "denoise": 1.0}}
    else:
        g["1"] = {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_fp8_e4m3fn.safetensors", "weight_dtype": "default"}}
        g["5"] = {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["1", 0], "shift": 3.0}}
        g["6"] = {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": POS}}
        g["7"] = {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": NEG}}
        g["8"] = {"class_type": "EmptySD3LatentImage", "inputs": {"width": W, "height": H, "batch_size": 1}}
        g["9"] = {"class_type": "KSampler", "inputs": {"model": ["5", 0], "positive": ["6", 0], "negative": ["7", 0],
                   "latent_image": ["8", 0], "seed": 4321, "steps": STEPS, "cfg": 3.5, "sampler_name": "euler",
                   "scheduler": "simple", "denoise": 1.0}}
    return g


def img2img():
    g = base_nodes(edit=False, ref_latent=False)
    g["13"] = {"class_type": "ImageScale", "inputs": {"image": ["12", 0], "upscale_method": "lanczos", "width": W, "height": H, "crop": "disabled"}}
    g["14"] = {"class_type": "VAEEncode", "inputs": {"pixels": ["13", 0], "vae": ["3", 0]}}
    g["9"]["inputs"]["latent_image"] = ["14", 0]
    g["9"]["inputs"]["denoise"] = 0.5
    g["11"]["inputs"]["filename_prefix"] = "cmp_i2i"
    return g


os.makedirs(OUT, exist_ok=True)
e_ref, px_ref = embed(open("/opt/weaveora/ComfyUI/input/" + REF1, "rb").read())
log("定妆照(宝玉) 人脸 px=%s 特征维度=%s" % (px_ref, len(e_ref) if e_ref else None))

variants = [("A_txt2img", base_nodes(False, False)), ("B_img2img05", img2img()),
            ("C_edit_refLatent", base_nodes(True, True)), ("D_edit_emptyLatent", base_nodes(True, False))]
for tag, g in variants:
    t0 = time.time()
    try:
        r = post("/prompt", {"prompt": g, "client_id": "cmp"})
        pid = r.get("prompt_id")
    except Exception as e:
        log("%-20s 提交失败 %s" % (tag, str(e)[:150])); continue
    blob = None
    while time.time() - t0 < 1200:
        time.sleep(8)
        h = get("/history/" + pid)
        if h and pid in h:
            for node in (h[pid].get("outputs") or {}).values():
                for im in (node.get("images") or []):
                    q = "filename=%s&subfolder=%s&type=%s" % (im["filename"], im.get("subfolder", ""), im.get("type", "output"))
                    blob = urllib.request.urlopen(COMFY + "/view?" + q, timeout=300).read()
            break
    if not blob:
        log("%-20s 无输出/超时" % tag); continue
    p = os.path.join(OUT, "%s.png" % tag)
    open(p, "wb").write(blob)
    e, px = embed(blob)
    sim = ("%.3f" % cos(e_ref, e)) if (e and e_ref) else "取不到人脸"
    log("%-20s 用时%4.0fs  人脸px=%-6s 与定妆照相似度=%s  → %s" % (tag, time.time() - t0, px, sim, p))
log("=== 对照结束（产图在 %s，可复制到 _share 查看）===" % OUT)
