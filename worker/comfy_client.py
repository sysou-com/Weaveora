#!/usr/bin/env python3
"""Weaveora comfy worker 引擎（W4：真 ComfyUI txt2img + IP-Adapter 一致性锚定）。

用法见 stub_worker.py：WEAVEORA_WORKER_MODE=comfy 时走本模块；
WEAVEORA_COMFY_URL 指向 ComfyUI（默认 http://127.0.0.1:8188）。
本模块只依赖标准库（urllib），不依赖 comfyui 客户端库。
"""
import base64
import io
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
TOKEN = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")
COMFY = os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8188").rstrip("/")
# IP-Adapter 工作流节点缺失或失败时是否降级 txt2img（默认降级，保证能出图）
FALLBACK = os.environ.get("WEAVEORA_COMFY_FALLBACK_TXT2IMG", "1") == "1"


class ComfyError(Exception):
    pass


def _api(path, payload=None, timeout=300):
    headers = {"X-Worker-Token": TOKEN}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        raise ComfyError("weaveora api %s -> %s %s" % (path, e.code, e.read()[:300]))


def _comfy(method, path, payload=None, files=None, timeout=120):
    headers = {}
    data = None
    if files:
        boundary = "----wf" + str(int(time.time() * 1e6))
        parts = []
        for field, (fname, content, ctype) in files.items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                          "Content-Type: %s\r\n\r\n" % (boundary, field, fname, ctype)).encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode())
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(COMFY + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, (r.read() or b"")
    except urllib.error.HTTPError as e:
        raise ComfyError("comfy %s %s -> %s %s" % (method, path, e.code, e.read()[:300]))


def _free_comfy_models():
    """让 ComfyUI 卸掉自己缓存的模型（SDXL / Wan 等占着 6+ GB），把显存让给 LatentSync。

    8 GiB 卡上很关键：否则 LatentSync 会在 ComfyUI 残留模型之上 OOM。失败不致命。
    """
    try:
        _comfy("POST", "/free", payload={"unload_models": True, "free_memory": True}, timeout=180)
        print("[comfy] 已请求卸载缓存模型（为 lipsync 腾显存）", flush=True)
    except Exception as e:
        print("[comfy] /free 失败（忽略）: %s" % e, flush=True)


def fetch_reference_bytes(storage_key):
    """经 weaveora 内部通道取参考图/参考音原始字节（token 鉴权）。

    注意：storage_key 必须是**存储 key**（形如 ws/project/ref/uuid.png），
    不是资产 UUID —— 传 UUID 服务端会 404（readAssetByKey 按 key 查）。
    """
    q = urllib.parse.quote(base64.urlsafe_b64encode(storage_key.encode()).decode(), safe="")
    req = urllib.request.Request("%s/internal/assets?key=%s" % (API, q),
                                 headers={"X-Worker-Token": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read(), (r.headers.get("Content-Type") or "image/png")
    except urllib.error.HTTPError as e:
        raise ComfyError("fetch ref asset %s -> %s（key 须为存储 key，不能传资产 UUID）"
                         % (storage_key, e.code))



_KS_INFO = None
_NODE_INFO = None


def _node_info(class_type):
    """缓存式节点探测：返回 object_info 字典或 None（节点不存在）。"""
    global _NODE_INFO
    if _NODE_INFO is None:
        _NODE_INFO = {}
    if class_type in _NODE_INFO:
        return _NODE_INFO[class_type]
    try:
        import urllib.request as _ur
        with _ur.urlopen(COMFY + "/object_info/" + urllib.parse.quote(class_type), timeout=15) as r:
            _NODE_INFO[class_type] = json.loads(r.read())
    except Exception:
        _NODE_INFO[class_type] = None
    return _NODE_INFO[class_type]


def _has_input(info, name):
    """节点是否有某输入（required/optional 都算）。"""
    if not info:
        return False
    node = next(iter(info.values())) if isinstance(info, dict) else None
    if not node:
        return False
    ipt = node.get("input") or {}
    for sec in ("required", "optional"):
        if name in (ipt.get(sec) or {}):
            return True
    return False


def _node_input_options(class_type, name):
    """从 object_info 取某输入的允许值列表（无则返回 []）。"""
    info = _node_info(class_type)
    if not info:
        return []
    node = next(iter(info.values())) if isinstance(info, dict) else None
    if not node:
        return []
    ipt = node.get("input") or {}
    for sec in ("required", "optional"):
        v = (ipt.get(sec) or {}).get(name)
        if isinstance(v, list) and v and isinstance(v[0], list):
            return v[0]
    return []


def _pick_option(class_type, name, requested, fallback):
    """在允许值里选：requested → fallback → 第一个；无约束则用 requested。"""
    opts = _node_input_options(class_type, name)
    if not opts:
        return requested if requested else fallback
    if requested in opts:
        return requested
    if fallback in opts:
        return fallback
    return opts[0]


def _rect_mask_png(width, height, region):
    """按归一化区域 {x,y,w,h} 生成灰度 PNG 遮罩（白=区域，黑=其余），纯标准库。"""
    w, h = int(width), int(height)
    x = int(round(float(region.get("x", 0)) * w))
    y = int(round(float(region.get("y", 0)) * h))
    rw = max(1, int(round(float(region.get("w", 0)) * w)))
    rh = max(1, int(round(float(region.get("h", 0)) * h)))
    rows = []
    white = b"\xff" * rw
    black_l = b"\x00" * max(0, x)
    black_r = b"\x00" * max(0, w - x - rw)
    empty_row = b"\x00" + b"\x00" * w
    for j in range(h):
        if y <= j < y + rh:
            rows.append(b"\x00" + black_l + white + black_r)
        else:
            rows.append(empty_row)
    raw = b"".join(rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def _upload_image(data, filename, ctype="image/png"):
    _, body = _comfy("POST", "/upload/image", files={"image": (filename, data, ctype)})
    return json.loads(body.decode()).get("name")


def _sampler_options(kind, fallback):
    """从 Comfy object_info 读 KSampler 允许列表并缓存；返回 (list, chosen)。"""
    global _KS_INFO
    try:
        if _KS_INFO is None:
            import urllib.request as _ur
            _KS_INFO = json.loads(_ur.urlopen(COMFY + "/object_info/KSampler", timeout=15).read())
        opts = _KS_INFO["KSampler"]["input"]["required"][kind][0]
        if isinstance(opts, list) and opts:
            return opts, (fallback if fallback in opts else opts[0])
    except Exception:
        pass
    return [fallback], fallback


def _prompt(client_id, positive, negative, params, seed, width=None, height=None,
            reference_image_name=None, references=None, prefix="weaveora"):
    """构造 ComfyUI prompt（txt2img；带参考图则加 IP-Adapter 分支）。

    P5：多参考图 + 归一化区域遮罩（分区 IP-Adapter，按角色解耦）：
      references = [{"name": ..., "subject": "唐僧", "region": {x,y,w,h}|None}, ...]
    策略（节点探测）：IPAdapterAdvanced(attn_mask) > IPAdapterMS(mask) > 单图回退（只用主主体）。
    """
    cfg = float(params.get("cfg", 5.5))
    steps = int(params.get("steps", 30))
    requested = params.get("sampler", "dpmpp_2m")
    _, sampler = _sampler_options("sampler_name", requested)
    _, scheduler = _sampler_options("scheduler", params.get("scheduler", "normal"))
    width = int(width or params.get("width") or 1024)
    height = int(height or params.get("height") or 1024)

    nodes = {
        "ckpt": {"class_type": "CheckpointLoaderSimple",
                 "inputs": {"ckpt_name": params.get("model", "sd_xl_base_1.0.safetensors")}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"text": positive, "clip": ["ckpt", 1]}},
        "neg": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["ckpt", 1]}},
        "empty": {"class_type": "EmptyLatentImage",
                  "inputs": {"width": width, "height": height, "batch_size": 1}},
        "ksampler": {"class_type": "KSampler",
                     "inputs": {"model": ["ckpt", 0], "positive": ["pos", 0], "negative": ["neg", 0],
                                "latent_image": ["empty", 0],
                                "seed": int(seed or 1), "steps": steps, "cfg": cfg,
                                "sampler_name": sampler, "scheduler": scheduler,
                                "denoise": 1.0}},
        "vae": {"class_type": "VAEDecode", "inputs": {"samples": ["ksampler", 0], "vae": ["ckpt", 2]}},
        "save": {"class_type": "SaveImage",
                 "inputs": {"images": ["vae", 0], "filename_prefix": prefix}},
    }

    refs = list(references or [])
    if not refs and reference_image_name:
        refs = [{"name": reference_image_name, "subject": "", "region": None}]
    if refs:
        # IP-Adapter（参考图 → 主体一致性；P5 多主体按区域遮罩解耦）
        nodes["ip_unified"] = {"class_type": "IPAdapterUnifiedLoader",
                               "inputs": {"preset": params.get("ipadapter_preset",
                                                                   "STANDARD (medium strength)"),
                                          "model": ["ckpt", 0]}}
        weight = float(params.get("ipadapter_weight", 0.85))
        req_wtype = params.get("ipadapter_weight_type")
        adv = _node_info("IPAdapterAdvanced")
        adv_masked = _has_input(adv, "attn_mask")
        ms = _node_info("IPAdapterMS")
        ms_masked = (not adv_masked) and _has_input(ms, "mask")
        regional_ok = bool(params.get("ipadapter_regional", True)) and (adv_masked or ms_masked)
        has_region = any((r.get("region") or {}) for r in refs)
        if len(refs) > 1 and not (regional_ok and has_region):
            # 无遮罩能力/未标注区域：多图不能全挂（会串脸）→ 只用首张（generate 已把主主体排首位）
            print("[comfy] 多参考图但无分区能力/未标注区域，仅用首张（%s）"
                  % (refs[0].get("subject") or "-"), flush=True)
            refs = [refs[0]]
        prev_model = ["ip_unified", 0]
        for i, r in enumerate(refs):
            nodes["load_ref_%d" % i] = {"class_type": "LoadImage", "inputs": {"image": r["name"]}}
            inp = {"model": prev_model, "ipadapter": ["ip_unified", 1], "image": ["load_ref_%d" % i, 0],
                   "weight": weight, "start_at": 0.0, "end_at": 1.0,
                   "weight_type": _pick_option("IPAdapter", "weight_type", req_wtype, "standard")}
            region = r.get("region") or None
            cls = "IPAdapter"
            if regional_ok and region:
                try:
                    mask_name = _upload_image(
                        _rect_mask_png(width, height, region),
                        "wvmask_%d_%d.png" % (int(seed or 0), i))
                    nodes["mask_%d" % i] = {"class_type": "LoadImage", "inputs": {"image": mask_name}}
                    mask_out = ["mask_%d" % i, 1]  # LoadImage 的 MASK 输出
                    blur = _node_info("MaskBlur")
                    if blur is not None:
                        nodes["mask_blur_%d" % i] = {
                            "class_type": "MaskBlur",
                            "inputs": {"mask": mask_out,
                                       "blur_radius": int(params.get("ipadapter_mask_blur", 12)),
                                       "sigma": float(params.get("ipadapter_mask_sigma", 8.0))}}
                        mask_out = ["mask_blur_%d" % i, 0]
                    if adv_masked:
                        cls = "IPAdapterAdvanced"
                        inp["weight_type"] = _pick_option("IPAdapterAdvanced", "weight_type", req_wtype, "linear")
                        inp["embeds_scaling"] = _pick_option("IPAdapterAdvanced", "embeds_scaling",
                                                             params.get("ipadapter_embeds_scaling"), "V only")
                        inp["attn_mask"] = mask_out
                    else:
                        cls = "IPAdapterMS"
                        inp["weight_type"] = _pick_option("IPAdapterMS", "weight_type", req_wtype, "linear")
                        inp["embeds_scaling"] = _pick_option("IPAdapterMS", "embeds_scaling",
                                                             params.get("ipadapter_embeds_scaling"), "V only")
                        inp["mask"] = mask_out
                except Exception as e:
                    print("[comfy] 区域遮罩构建失败，退化为全局 IP-Adapter: %s" % e, flush=True)
                    cls = "IPAdapter"
            nodes["ip_%d" % i] = {"class_type": cls, "inputs": inp}
            prev_model = ["ip_%d" % i, 0]
        nodes["ksampler"]["inputs"]["model"] = prev_model

    # ComfyUI 需要节点 id 为字符串键 + client_id
    return {"prompt": nodes, "client_id": client_id}


def _poll_history(client_id, prompt_id, poll=2.0, timeout=600, on_tick=None):
    """轮询 /history 直到成功/失败/超时。

    on_tick(elapsed_sec) 每轮回调一次（用于上报“已运行 N 分钟”，
    否则对口型 25–30 分钟期间 UI 会一直停在 40%，看着像卡死）。
    """
    deadline = time.time() + timeout
    t0 = time.time()
    while time.time() < deadline:
        st, body = _comfy("GET", "/history/" + prompt_id)
        if st == 200:
            data = json.loads(body or b"{}")
            rec = data.get(prompt_id)
            if rec:
                status = (rec.get("status") or {})
                if status.get("status_str") == "success":
                    return rec
                if status.get("status_str") == "error":
                    msgs = status.get("messages", [])
                    raise ComfyError("comfy error: " + str(msgs[-1] if msgs else status)[:500])
        if on_tick:
            try:
                on_tick(time.time() - t0)
            except Exception:
                pass
        time.sleep(poll)
    raise ComfyError("comfy prompt %s timeout" % prompt_id)


def _download_outputs(rec, prefix="weaveora"):
    outs = []
    outputs = rec.get("outputs") or {}
    for node in outputs.values():
        for img in (node.get("images") or []):
            fname = img.get("filename", "")
            if not fname.startswith(prefix):
                continue
            sub = img.get("subfolder") or ""
            typ = img.get("type") or "output"
            q = urllib.parse.urlencode({"filename": fname, "subfolder": sub, "type": typ})
            _, body = _comfy("GET", "/view?" + q)
            outs.append({"filename": fname, "bytes": body, "subfolder": sub})
    return outs


def generate(client_id, payload, progress_fn=None):
    """执行一个 job payload；返回输出图片字节列表（png）。"""
    if progress_fn:
        progress_fn(30, "sampling")

    positive = payload.get("positive_prompt", "")
    negative = payload.get("negative_prompt", "")
    params = payload.get("params") or {}
    seed = payload.get("seed")
    width = (params.get("width") if isinstance(params.get("width"), int) else None)
    height = (params.get("height") if isinstance(params.get("height"), int) else None)
    prefix = "weaveora" + (("_" + str(payload.get("shot_no") or "")) if payload.get("kind") == "video" else "")

    refs = []
    ref_keys = payload.get("referenceKeys") or []
    ref_subjects = payload.get("referenceSubjects") or []
    ref_regions = payload.get("referenceRegions") or []
    primary = payload.get("primarySubject") or ""
    for i, key in enumerate(ref_keys[:4]):
        try:
            data, ctype = fetch_reference_bytes(key)
            name = _upload_image(data, key.split("/")[-1] or ("ref_%d.png" % i), ctype or "image/png")
            region = ref_regions[i] if i < len(ref_regions) else None
            if not isinstance(region, dict):
                region = None
            subject = ref_subjects[i] if i < len(ref_subjects) else ""
            if name:
                refs.append({"name": name, "subject": subject, "region": region})
        except Exception as e:
            print("[comfy] ref#%d 上传失败，跳过: %s" % (i, e), flush=True)
    # 主主体排首位（单图回退与权重聚焦都按首位）
    if primary:
        refs.sort(key=lambda r: 0 if r.get("subject") == primary else 1)
    n_regions = sum(1 for r in refs if r.get("region"))
    if refs:
        print("[comfy] refs=%d regions=%d primary=%s" % (len(refs), n_regions, primary or "-"), flush=True)

    last_err = None
    try:
        prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                         references=refs, prefix=prefix)
        pid = _post_prompt(prompt, client_id)
        rec = _poll_history(client_id, pid)
        return _download_outputs(rec, prefix)
    except ComfyError as e:
        last_err = e
        # IP-Adapter 图未用上或节点缺失 → 降级纯 txt2img
        if refs and FALLBACK:
            prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                             references=None, prefix=prefix)
            pid = _post_prompt(prompt, client_id)
            rec = _poll_history(client_id, pid)
            return _download_outputs(rec, prefix)
        raise


def _post_prompt(prompt, client_id):
    """POST /prompt；对偶发的 prompt 校验失败重试 1 次（同图重投，服务端状态问题）。"""
    for attempt in (1, 2):
        try:
            st, body = _comfy("POST", "/prompt", payload=prompt)
            pid = json.loads(body.decode()).get("prompt_id")
            if not pid:
                raise ComfyError("comfy /prompt 无 prompt_id")
            return pid
        except ComfyError as e:
            if attempt == 1 and "failed_validation" in str(e):
                sys.stderr.write("[comfy] prompt validation 偶发失败，重试: %s\n" % str(e)[:600])
                time.sleep(1.5)
                continue
            raise



def _motion_graph(client_id, payload, positive, negative, first_frame_name, prefix):
    """Wan2.2 ti2v 5B i2v（新 wrapper 节点集）：Sampler → Decode → SaveImage 帧。
    输出帧由 generate_motion 用 ffmpeg 合成 mp4（不依赖 VHS）。"""
    fps = int(payload.get("fps") or 16)
    steps = int((payload.get("params") or {}).get("steps", 20))
    cfg = float((payload.get("params") or {}).get("cfg", 5.0))
    duration = float(payload.get("duration_sec") or 2.0)
    seed = int(payload.get("seed") or 1)
    fuser = payload.get("frames")
    if isinstance(fuser, int) and 32 <= fuser <= 128:
        frames = int((fuser + 3) / 4) * 4  # 用户显式指定（后端已校验 32–96）
    else:
        raw = max(32, min(64, int(round(duration * fps))))  # 缺省：>=32，封顶64
        frames = int((raw + 3) / 4) * 4
    width = int((payload.get("params") or {}).get("width", 768))
    height = int((payload.get("params") or {}).get("height", 768))
    length = frames + 1  # Comfy 原生 Wan latent 帧数 = 4n+1（32 采样帧 → 33）
    nodes = {
        "unet": {"class_type": "UNETLoader",
                 "inputs": {"unet_name": (payload.get("params") or {}).get(
                     "model", "wan2.2_ti2v_5B_fp16.safetensors"),
                     "weight_dtype": (payload.get("params") or {}).get(
                         "quantization", "fp8_e4m3fn")}},
        "clip": {"class_type": "CLIPLoader",
                 "inputs": {"clip_name": (payload.get("params") or {}).get(
                     "text_encoder", "umt5_xxl_fp8_e4m3fn_scaled.safetensors"),
                     "type": "wan"}},
        "vae": {"class_type": "VAELoader",
                "inputs": {"vae_name": (payload.get("params") or {}).get(
                    "vae", "wan2.2_vae.safetensors")}},
        "shift": {"class_type": "ModelSamplingSD3",
                  "inputs": {"model": ["unet", 0], "shift": 8.0}},
        "pos": {"class_type": "CLIPTextEncode",
                "inputs": {"text": positive, "clip": ["clip", 0]}},
        "neg": {"class_type": "CLIPTextEncode",
                "inputs": {"text": negative, "clip": ["clip", 0]}},
        "img": {"class_type": "LoadImage", "inputs": {"image": first_frame_name}},
        "latent": {"class_type": "Wan22ImageToVideoLatent",
                   "inputs": {"vae": ["vae", 0], "start_image": ["img", 0],
                              "width": width, "height": height, "length": length,
                              "batch_size": 1}},
        "sampler": {"class_type": "KSampler",
                    "inputs": {"model": ["shift", 0], "positive": ["pos", 0],
                               "negative": ["neg", 0], "latent_image": ["latent", 0],
                               "seed": seed, "steps": steps, "cfg": cfg,
                               "sampler_name": "uni_pc", "scheduler": "simple",
                               "denoise": 1.0}},
        "dec": {"class_type": "VAEDecode",
                "inputs": {"samples": ["sampler", 0], "vae": ["vae", 0]}},
        "save": {"class_type": "SaveImage",
                 "inputs": {"images": ["dec", 0], "filename_prefix": prefix}},
    }
    return {"prompt": nodes, "client_id": client_id}


def _encode_frames_mp4(frames_bytes, fps, out_dir):
    import subprocess as _sp
    import tempfile
    try:
        import imageio_ffmpeg as _iif
        ff = _iif.get_ffmpeg_exe()
    except Exception as e:
        raise ComfyError("imageio_ffmpeg 不可用: %s" % e)
    if not frames_bytes:
        raise ComfyError("motion 无输出帧")
    for i, b in enumerate(frames_bytes):
        with open(os.path.join(out_dir, "f_%04d.png" % i), "wb") as fh:
            fh.write(b)
    out = os.path.join(out_dir, "motion.mp4")
    r = _sp.run([ff, "-y", "-framerate", str(fps), "-i",
                 os.path.join(out_dir, "f_%04d.png"),
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", out],
                capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(out):
        raise ComfyError("ffmpeg 合成失败: %s" % (r.stderr or "")[-300:])
    with open(out, "rb") as fh:
        mp4 = fh.read()
    return mp4


def generate_motion(client_id, payload, progress_fn=None):
    """Wan2.2 i2v motion（关键帧→短视频 mp4）。返回 [{bytes,mime,width,height}]。"""
    import tempfile, uuid as _uuid
    if progress_fn:
        progress_fn(25, "loading_model")
    key = payload.get("keyframeKey")
    if not key:
        raise ComfyError("clip 任务缺少 keyframeKey（先出关键帧）")
    data, ctype = fetch_reference_bytes(key)
    positive = payload.get("positive_prompt", "")
    negative = payload.get("negative_prompt", "")
    fps = int(payload.get("fps") or 16)
    # motion 固定 768×768（Comfy 原生 Wan2.2 方形档位；8GB fp8），关键帧缩放后上传保证一致
    mw = int((payload.get("params") or {}).get("width", 768))
    mh = int((payload.get("params") or {}).get("height", 768))
    try:
        from PIL import Image as _PIL
        _im = _PIL.open(io.BytesIO(data)).convert("RGB").resize((mw, mh), _PIL.LANCZOS)
        _buf = io.BytesIO()
        _im.save(_buf, format="PNG")
        data = _buf.getvalue()
    except Exception:
        pass
    params2 = dict(payload.get("params") or {})
    params2["width"] = mw
    params2["height"] = mh
    payload = dict(payload)
    payload["params"] = params2
    prefix = "weaveora_mot_" + _uuid.uuid4().hex[:6]
    if progress_fn:
        progress_fn(35, "sampling")
    _, body = _comfy("POST", "/upload/image",
                     files={"image": (key.split("/")[-1], data, ctype)})
    name = json.loads(body.decode()).get("name")
    prompt = _motion_graph(client_id, payload, positive, negative, name, prefix)
    st, resp = _comfy("POST", "/prompt", payload=prompt)
    pid = json.loads(resp.decode()).get("prompt_id")
    if not pid:
        raise ComfyError("comfy /prompt 无 prompt_id")
    if progress_fn:
        progress_fn(50, "sampling")
    rec = _poll_history(client_id, pid)
    # 收集输出帧
    frames = []
    outputs = rec.get("outputs") or {}
    for node in outputs.values():
        if not isinstance(node, dict):
            continue
        for it in node.get("images") or []:
            fname = it.get("filename", "")
            if not fname.startswith(prefix):
                continue
            q = urllib.parse.urlencode({"filename": fname,
                                        "subfolder": it.get("subfolder", ""),
                                        "type": it.get("type", "output")})
            _, fb = _comfy("GET", "/view?" + q)
            frames.append(fb)
    if not frames:
        raise ComfyError("motion 无输出帧（prefix=%s）" % prefix)
    out_dir = tempfile.mkdtemp(prefix="wv_mot_")
    try:
        mp4 = _encode_frames_mp4(frames, fps, out_dir)
    finally:
        import shutil as _sh
        _sh.rmtree(out_dir, ignore_errors=True)
    # 上报**真实**产出规格：原先直接把 payload 里「请求的」width/height 当结果上报，
    # 于是资产库里记的是 1280×704，而实际文件是 Wan 真正出图桶（本例 832×464）——
    # 2026-09-13 排查时让人误以为「对口型把分辨率改小了」。
    pw, ph, pdur = _probe_video_meta(mp4)
    rw = (payload.get("params") or {}).get("width", 768)
    rh = (payload.get("params") or {}).get("height", 768)
    w, h = pw or rw, ph or rh
    if (pw and int(pw) != int(rw)) or (ph and int(ph) != int(rh)):
        print("[comfy] motion 实际尺寸 %sx%s 与请求 %sx%s 不一致（已按实际上报）"
              % (w, h, rw, rh), flush=True)
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": mp4, "mime": "video/mp4", "width": int(w), "height": int(h),
             "duration_ms": pdur}]


# ---- P7 配乐：ACE-Step 1.5（ComfyUI 原生节点，非 wrapper） ----
# 模型：all-in-one checkpoint（含 unet + qwen3 文本编码器 + vae），CheckpointLoaderSimple 直接加载。
# 生成配方对齐 ComfyUI 官方蓝图 blueprints/Text to Audio (ACE-Step 1.5).json：
#   ModelSamplingAuraFlow(shift=3) + KSampler(euler/simple, steps=8, cfg=1, denoise=1)
MUSIC_CKPT = os.environ.get("WEAVEORA_MUSIC_CKPT_NAME", "ace_step_1.5_turbo_aio.safetensors")
MUSIC_SAVE_NODE = os.environ.get("WEAVEORA_MUSIC_SAVE_NODE", "SaveAudioMP3")
MUSIC_QUALITY = os.environ.get("WEAVEORA_MUSIC_QUALITY", "320k")
MUSIC_STEPS = int(os.environ.get("WEAVEORA_MUSIC_STEPS", "8"))
MUSIC_CFG = float(os.environ.get("WEAVEORA_MUSIC_CFG", "1.0"))
MUSIC_SHIFT = float(os.environ.get("WEAVEORA_MUSIC_SHIFT", "3.0"))
MUSIC_SCHEDULER = os.environ.get("WEAVEORA_MUSIC_SCHEDULER", "simple")
MUSIC_SAMPLER = os.environ.get("WEAVEORA_MUSIC_SAMPLER", "euler")
MUSIC_TAGS_CFG = float(os.environ.get("WEAVEORA_MUSIC_TAGS_CFG", "2.0"))
MUSIC_TEMPERATURE = float(os.environ.get("WEAVEORA_MUSIC_TEMPERATURE", "0.85"))
MUSIC_BPM = int(os.environ.get("WEAVEORA_MUSIC_BPM", "120"))
MUSIC_KEYSCALE = os.environ.get("WEAVEORA_MUSIC_KEYSCALE", "E minor")
MUSIC_LANGUAGE = os.environ.get("WEAVEORA_MUSIC_LANGUAGE", "en")
MUSIC_TIMESIG = os.environ.get("WEAVEORA_MUSIC_TIMESIG", "4")
MUSIC_AUDIO_CODES = os.environ.get("WEAVEORA_MUSIC_AUDIO_CODES", "1") == "1"
# 配乐单次生成上限（秒）：8GB 卡上 120s 潜在序列很长，必要时可下调
MUSIC_MAX_SEC = float(os.environ.get("WEAVEORA_MUSIC_MAX_SEC", "180"))
MUSIC_TIMEOUT = int(os.environ.get("WEAVEORA_MUSIC_TIMEOUT", "2400"))

_AUDIO_MIME = {"mp3": "audio/mpeg", "flac": "audio/flac", "opus": "audio/ogg",
               "wav": "audio/wav", "m4a": "audio/mp4", "ogg": "audio/ogg"}


def _download_audio(rec, prefix):
    """收集音频产物（SaveAudio* 落到 outputs[node].audio）。"""
    outs = []
    for node in (rec.get("outputs") or {}).values():
        if not isinstance(node, dict):
            continue
        for it in list(node.get("audio") or []) + list(node.get("images") or []):
            fname = it.get("filename", "")
            if not fname.startswith(prefix):
                continue
            q = urllib.parse.urlencode({"filename": fname,
                                        "subfolder": it.get("subfolder", ""),
                                        "type": it.get("type", "output")})
            _, body = _comfy("GET", "/view?" + q)
            outs.append({"filename": fname, "bytes": body})
    return outs


def _music_graph(client_id, payload, prefix):
    """ACE-Step 1.5 文生配乐图（ComfyUI 原生节点 + all-in-one checkpoint）。"""
    tags = (payload.get("prompt") or "").strip() or \
        "cinematic instrumental score, emotional, no vocals"
    lyrics = (payload.get("lyrics") or "").strip()
    try:
        duration = float(payload.get("duration_sec") or 30)
    except (TypeError, ValueError):
        duration = 30.0
    duration = max(5.0, min(MUSIC_MAX_SEC, duration))
    seed = int(payload.get("seed") or 0)
    save = {"audio": ["7", 0], "filename_prefix": prefix}
    if MUSIC_SAVE_NODE == "SaveAudioMP3":
        save["quality"] = MUSIC_QUALITY
    elif MUSIC_SAVE_NODE == "SaveAudioAdvanced":
        save["format"] = {"format": "mp3", "quality": MUSIC_QUALITY}
    nodes = {
        "1": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": MUSIC_CKPT}},
        "2": {"class_type": "ModelSamplingAuraFlow",
              "inputs": {"model": ["1", 0], "shift": MUSIC_SHIFT}},
        "3": {"class_type": "TextEncodeAceStepAudio1.5", "inputs": {
            "clip": ["1", 1], "tags": tags, "lyrics": lyrics, "seed": seed,
            "bpm": MUSIC_BPM, "duration": duration, "timesignature": MUSIC_TIMESIG,
            "language": MUSIC_LANGUAGE, "keyscale": MUSIC_KEYSCALE,
            "generate_audio_codes": MUSIC_AUDIO_CODES,
            "cfg_scale": MUSIC_TAGS_CFG, "temperature": MUSIC_TEMPERATURE,
            "top_p": 0.9, "top_k": 0, "min_p": 0.0}},
        "4": {"class_type": "ConditioningZeroOut",
              "inputs": {"conditioning": ["3", 0]}},
        "5": {"class_type": "EmptyAceStep1.5LatentAudio",
              "inputs": {"seconds": duration, "batch_size": 1}},
        "6": {"class_type": "KSampler", "inputs": {
            "model": ["2", 0], "positive": ["3", 0], "negative": ["4", 0],
            "latent_image": ["5", 0], "seed": seed, "steps": MUSIC_STEPS,
            "cfg": MUSIC_CFG, "sampler_name": MUSIC_SAMPLER,
            "scheduler": MUSIC_SCHEDULER, "denoise": 1.0}},
        "7": {"class_type": "VAEDecodeAudio",
              "inputs": {"samples": ["6", 0], "vae": ["1", 2]}},
        "8": {"class_type": MUSIC_SAVE_NODE, "inputs": save},
    }
    # ComfyUI 需要节点 id 为字符串键 + client_id
    return {"prompt": nodes, "client_id": client_id}


def generate_music(client_id, payload, progress_fn=None):
    """ACE-Step 1.5 配乐（bgm 任务）。返回 [{bytes, mime, duration_ms}]。"""
    import uuid as _uuid
    if progress_fn:
        progress_fn(20, "loading_model")
    duration = float(payload.get("duration_sec") or 30)
    duration = max(5.0, min(MUSIC_MAX_SEC, duration))
    prefix = "weaveora_bgm_" + _uuid.uuid4().hex[:6]
    prompt = _music_graph(client_id, payload, prefix)
    pid = _post_prompt(prompt, client_id)
    if progress_fn:
        progress_fn(35, "sampling")
    rec = _poll_history(client_id, pid, timeout=MUSIC_TIMEOUT)
    outs = _download_audio(rec, prefix)
    if not outs:
        raise ComfyError("bgm 无输出音频（prefix=%s）" % prefix)
    if progress_fn:
        progress_fn(100, "done")
    out = []
    for o in outs:
        ext = o["filename"].rsplit(".", 1)[-1].lower() if "." in o["filename"] else ""
        out.append({"bytes": o["bytes"], "mime": _AUDIO_MIME.get(ext, "audio/mpeg"),
                    "duration_ms": int(duration * 1000)})
    return out


if __name__ == "__main__":
    import sys
    print("comfy engine url=%s api=%s" % (COMFY, API))
    print("music ckpt=%s node=%s steps=%d cfg=%s shift=%s"
          % (MUSIC_CKPT, MUSIC_SAVE_NODE, MUSIC_STEPS, MUSIC_CFG, MUSIC_SHIFT))


# --------------------------------------------------------------------------- #
# P13 对口型（lipsync）：音频驱动嘴型
#
# 图生视频模型（Wan i2v 等）**没有音频通道**，出来的画面不可能对口型；
# 口型必须靠「音频驱动」的后处理。这里走 **workflow 驱动**：
#   把 ComfyUI 里已装好的口型工作流（LatentSync / MuseTalk / Wav2Lip 任一）
#   导出成 API 格式 JSON，用环境变量 WEAVEORA_LIPSYNC_WORKFLOW 指过来，
#   本函数负责：上传 视频 + 音频 → 按**节点标题**注入输入 → 排队 → 取回 mp4。
#
# 安装步骤见 docs/lipsync-setup.md。未配置时给出明确报错（不静默出无声/无口型结果）。
# --------------------------------------------------------------------------- #
LIPSYNC_WORKFLOW = os.environ.get("WEAVEORA_LIPSYNC_WORKFLOW", "").strip()
LIPSYNC_TIMEOUT = float(os.environ.get("WEAVEORA_LIPSYNC_TIMEOUT", "1800"))
# 工作流里承载「视频/音频路径」的节点标题（用户按自己导出的工作流改这两个值即可）
LIPSYNC_VIDEO_TITLE = os.environ.get("WEAVEORA_LIPSYNC_VIDEO_TITLE", "video").strip()
LIPSYNC_AUDIO_TITLE = os.environ.get("WEAVEORA_LIPSYNC_AUDIO_TITLE", "audio").strip()
# 注入到该节点的哪个 **输入键**：不同加载节点键名不同（原生 LoadVideo = file，VHS_LoadVideo = video）。
# 不设则回退到旧行为（视频键 video / 音频键 audio）。
LIPSYNC_VIDEO_INPUT = os.environ.get("WEAVEORA_LIPSYNC_VIDEO_INPUT", "").strip()
LIPSYNC_AUDIO_INPUT = os.environ.get("WEAVEORA_LIPSYNC_AUDIO_INPUT", "").strip()
# LatentSync 的原生输出帧率（configs/unet/stage2_512.yaml: video_fps: 25）。
# 组装成片时必须用这个值，不能用源片 fps —— 否则时长会按 25/源fps 缩短。
LIPSYNC_FPS = int(os.environ.get("WEAVEORA_LIPSYNC_FPS", "0") or 0)
LIPSYNC_NODE_CLASS = os.environ.get("WEAVEORA_LIPSYNC_NODE_CLASS", "LatentSyncNode").strip()


# 文件名类输入的候选键（按优先级）：不同加载节点名字不一样
_VIDEO_FILE_KEYS = ("file", "video", "video_file", "path", "filename", "image", "url")
_AUDIO_FILE_KEYS = ("audio", "audio_file", "file", "path", "filename", "url")


def _derive_input_key(info, want_video):
    """从节点 schema（object_info）推出「文件名输入」的键名。

    原生 LoadVideo 用 `file`、VHS_LoadVideo 用 `video`、LoadAudio 用 `audio` ——
    不同节点不一致，写错键名会被 ComfyUI 忽略（并静默使用工作流里写死的旧文件名）。
    """
    for k in (_VIDEO_FILE_KEYS if want_video else _AUDIO_FILE_KEYS):
        if _has_input(info, k):
            return k
    return None


def _upload_any(data, filename, ctype, sub="input"):
    """上传任意文件到 ComfyUI 输入目录（视频/音频），返回服务器端文件名。"""
    _, body = _comfy("POST", "/upload/image", files={"image": (filename, data, ctype)},
                     timeout=600)
    try:
        return json.loads(body.decode()).get("name")
    except Exception:
        return None


def _set_node_input(graph, title, value, input_key=None, want_video=True):
    """按节点 title 注入输入；返回命中数。

    input_key 显式指定要写哪个输入键；不传则**从节点 schema 推**（原生 LoadVideo=file、
    VHS_LoadVideo=video、LoadAudio=audio），最后才回退到按 title 猜。

    为什么要 schema 推导 + 清掉非法键（2026-09-13 线上事故）：只靠 title 猜时，
    给 LoadVideo 写了它不认识的 `video` 键，而真正的 `file` 键保留了工作流 JSON 里
    写死的旧文件名 —— ComfyUI 直接加载了那个旧文件，于是拿我调试用的静帧片出了片，
    但外观上「任务成功、资产也有」，极难发现。
    """
    hit = 0
    explicit = (input_key or "").strip()
    candidates = _VIDEO_FILE_KEYS if want_video else _AUDIO_FILE_KEYS
    for _nid, node in (graph or {}).items():
        if not isinstance(node, dict):
            continue
        meta = node.get("_meta") or {}
        if (meta.get("title") or "").strip().lower() != (title or "").lower():
            continue
        info = _node_info(node.get("class_type")) if node.get("class_type") else None
        # 显式指定的键如果该节点并不声明（env 写错键名），**退回 schema 推导**，
        # 而不是写到节点不认识的键上（那正是把真文件丢掉、旧文件被静默使用的成因）
        key = explicit if (explicit and (not info or _has_input(info, explicit))) else None
        key = key or _derive_input_key(info, want_video) or explicit \
            or ("video" if want_video else "audio")
        inputs = node.setdefault("inputs", {})
        # 同一节点上其它「候选文件名键」：节点不认识的直接删掉，认识的清空
        # （宁可让 ComfyUI 报「文件名为空」，也不要静默加载旧文件）
        for k in candidates:
            if k == key or k not in inputs or not isinstance(inputs[k], str):
                continue
            if _has_input(info, k):
                inputs[k] = ""
            else:
                del inputs[k]
        inputs[key] = value
        print("[comfy] 注入 %s -> %s.%s = %s" % (title, node.get("class_type"), key, value),
              flush=True)
        hit += 1
    return hit


def _ensure_output_fps(graph, fps):
    """把「图片序列→视频」那一步的 fps 钉成 LatentSync 的原生帧率（默认 25）。

    为什么要钉（2026-09-13 线上事故）：LatentSync 按 config 的 `video_fps: 25` 生成帧，
    帧数 ≈ 配音时长 × 25；若组装时用**源片 fps**（本项目 motion 是 30），播放会快 25/30，
    成片时长缩短 17% → **末尾对白被截掉**（实例：4.92s 配音 → 4.17s 成片）。
    注意这些帧是「定数」的，改播放速度不能补回内容，只能改回 25。
    """
    fixed = []
    for node in (graph or {}).values():
        if not isinstance(node, dict):
            continue
        inputs = node.get("inputs") or {}
        if "fps" not in inputs:
            continue
        # 无论原值是指向源片的链接还是字面量，一律钉成固定值
        inputs["fps"] = int(fps)
        fixed.append(node.get("class_type"))
    if fixed:
        print("[comfy] 对口型输出 fps 已钉为 %d（节点：%s）" % (int(fps), ",".join(str(x) for x in fixed)),
              flush=True)
    return fixed


def _apply_fps_policy(graph):
    """帧率策略：默认「**生成帧率 = 播放帧率 = 源片帧率**」。

    为什么（2026-09-13 事故）：LatentSync 的 `video_fps` 决定「按配音时长生成多少帧」
    （frames ≈ 配音秒数 × fps）并同步截取音频长度；播放时**必须**用同一个 fps，
    否则时长会按比例变化（曾把组装端接到源片 30fps 而生成端停在默认 25
    → 成片短 17%、末尾对白被截；反过来也会把影片拉长）。

    两种模式：
      · `WEAVEORA_LIPSYNC_FPS` > 0：全部 fps 输入钉成该值（质量兜底开关：
        模型原生训练帧率是 25，若某台机器上非 25 的效果不满意，设 25 回退）；
      · 未设/0（默认，auto）：把除生成节点外所有 fps 输入**对齐到生成节点的 fps**
        （同一个链接/字面量）——工作流把两者都接到源片 fps，就得到源片帧率的成片。
    生成节点自己缺 fps 输入时（旧工作流）回退到 25（= 节点内部默认值）。
    返回 (mode, [被改的节点类名]) 供日志展示。
    """
    nodes = [n for n in (graph or {}).values() if isinstance(n, dict)]
    gen = next((n for n in nodes if n.get("class_type") == LIPSYNC_NODE_CLASS), None)
    gen_fps = ((gen or {}).get("inputs") or {}).get("fps")
    forced = int(LIPSYNC_FPS) if int(LIPSYNC_FPS or 0) > 0 else None
    if forced is None and gen_fps is None:
        forced = 25   # 旧工作流没有 fps 输入 → 跟节点内部默认保持一致
    touched = []
    for n in nodes:
        inputs = n.get("inputs") or {}
        if "fps" not in inputs:
            continue
        if forced is not None:
            inputs["fps"] = forced
        elif n.get("class_type") == LIPSYNC_NODE_CLASS:
            continue                      # 生成节点保留工作流里的来源（源片 fps）
        else:
            inputs["fps"] = gen_fps       # 对齐生成端（同一链接 → 运行时同值）
        touched.append(n.get("class_type"))
    return ("forced=%d" % forced) if forced is not None else "auto=source-fps", touched


def _probe_video_meta(mp4_bytes):
    """用 ffmpeg -i 读 mp4 的宽高与时长（返回 (w, h, duration_ms)）。

    为什么要做：LatentSync 节点只返回帧，不返回元数据；不回填的话资产库里这条
    对口型产物没有时长/分辨率（卡片显示不完整、导出时也可能被当成未知时长）。
    """
    import re
    import subprocess
    import tempfile
    if not mp4_bytes:
        return None, None, None
    p = os.path.join(tempfile.mkdtemp(prefix="weaveora_probe_"), "o.mp4")
    with open(p, "wb") as fh:
        fh.write(mp4_bytes)
    try:
        r = subprocess.run([_ffmpeg_exe(), "-i", p], capture_output=True, text=True, timeout=180)
        err = r.stderr or ""
    except Exception:
        return None, None, None
    w = h = dur_ms = None
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", err)
    if m:
        dur_ms = int((int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))) * 1000)
    m2 = re.search(r"Video:.*?,\s*(\d{2,5})x(\d{2,5})", err)
    if m2:
        w, h = int(m2.group(1)), int(m2.group(2))
    return w, h, dur_ms


def generate_lipsync(client_id, payload, progress_fn=None):
    """对口型（lipsync 任务）：画面 + 配音 → 嘴型对齐的视频。返回 [{bytes, mime}]。"""
    import uuid as _uuid
    if not LIPSYNC_WORKFLOW:
        raise ComfyError(
            "未配置对口型工作流：请按 docs/lipsync-setup.md 安装（ComfyUI-LatentSyncWrapper 等）"
            "并把导出的 API 格式工作流路径填到 WEAVEORA_LIPSYNC_WORKFLOW")
    if not os.path.exists(LIPSYNC_WORKFLOW):
        raise ComfyError("对口型工作流文件不存在: %s（检查 WEAVEORA_LIPSYNC_WORKFLOW）" % LIPSYNC_WORKFLOW)
    vkey = (payload.get("videoKey") or "").strip()
    vkeys = [k for k in (payload.get("voiceKeys") or []) if k]
    if not vkey or not vkeys:
        raise ComfyError("对口型缺少输入：videoKey/voiceKeys")

    if progress_fn:
        progress_fn(15, "upload")
    # fetch_reference_bytes 返回 (bytes, content_type) —— 别把 tuple 直接塞给上传（
    # 之前就是 b"".join(parts) 报 "expected a bytes-like object, tuple found"）。
    vdata, vctype = fetch_reference_bytes(vkey)
    # 多段配音：先合成为一个音频（本机 ffmpeg），一次对口型
    adata = _concat_voice(vkeys)
    # 没有 motion 的镜，后端会把关键帧静帧（png）当画面传过来；口型工作流吃的是视频
    # （LoadVideo → GetVideoComponents），所以先把它变成与配音等长的 mp4。
    still_mode = (bool(payload.get("videoIsStill")) or bool(payload.get("isStill"))
                  or str(vctype or "").startswith("image"))
    if still_mode:
        vdata = _still_to_video(vdata, adata, payload.get("duration_sec"))
        print("[comfy] lipsync 画面为静帧 → 已用 ffmpeg 转成与配音等长的 mp4", flush=True)
    # 文件名带上本次任务的唯一后缀：一是避免 ComfyUI 重名自动改名（会变成 xxx (1).mp4，
    # 日志里对不上人），二是从根上堆不了「同名旧文件被静默复用」。
    _tok = _uuid.uuid4().hex[:8]
    vname = _upload_any(vdata, "weaveora_lipsync_%s_in.mp4" % _tok, "video/mp4")
    if not vname:
        raise ComfyError("上传画面失败")
    aname = _upload_any(adata, "weaveora_lipsync_%s_voice.wav" % _tok, "audio/wav")
    if not aname:
        raise ComfyError("上传配音失败")

    with open(LIPSYNC_WORKFLOW, "r", encoding="utf-8") as fh:
        graph = json.load(fh)
    vh = _set_node_input(graph, LIPSYNC_VIDEO_TITLE, vname, LIPSYNC_VIDEO_INPUT)
    ah = _set_node_input(graph, LIPSYNC_AUDIO_TITLE, aname, LIPSYNC_AUDIO_INPUT, want_video=False)
    if not vh or not ah:
        raise ComfyError(
            "工作流里没找到标题为「%s」/「%s」的节点：请在 ComfyUI 里把承载视频/音频的节点标题改成这两个值"
            "（或用 WEAVEORA_LIPSYNC_VIDEO_TITLE / _AUDIO_TITLE 指定）"
            % (LIPSYNC_VIDEO_TITLE, LIPSYNC_AUDIO_TITLE))

    # 自检：确认注入后的图上确实指向本次上传的文件（防止再出现「写错键→静默用旧文件」）
    dirty = []
    for node in graph.values():
        if not isinstance(node, dict):
            continue
        for k, v in (node.get("inputs") or {}).items():
            if isinstance(v, str) and v and v.endswith((".mp4", ".wav", ".png", ".jpg")) \
                    and v not in (vname, aname):
                dirty.append("%s.%s=%s" % (node.get("class_type"), k, v))
    if dirty:
        raise ComfyError("对口型工作流里还有指向其它文件的输入（拒绝跑，避免拿错素材）：%s" % ", ".join(dirty))

    prefix = "weaveora_lipsync_" + _uuid.uuid4().hex[:6]
    for node in graph.values():
        if isinstance(node, dict) and "filename_prefix" in (node.get("inputs") or {}):
            node["inputs"]["filename_prefix"] = prefix
    # 帧率：生成帧率必须 = 播放帧率（默认都跟源片 fps 走）
    _mode, _nodes = _apply_fps_policy(graph)
    print("[comfy] 对口型 fps 策略：%s（节点：%s）" % (_mode, ",".join(str(x) for x in _nodes)),
          flush=True)
    _free_comfy_models()
    # 注意：导出的「API 格式」工作流是**裸节点图**（{"1":{...}}），而 /prompt 要的是
    # {"prompt": 图, "client_id": ...} —— 直接投裸图会被 ComfyUI 拒为 no_prompt。
    pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
    if progress_fn:
        progress_fn(40, "lipsync")
    # 对口型单镜实测 25–30 分钟；期间每过一分钟报一次“已运行 N 分钟”，
    # 既让 UI 有动静，也方便判“还在跑”还是“卡住了”。
    _last_min = [-1]

    def _tick(elapsed):
        m = int(elapsed // 60)
        if m == _last_min[0]:
            return
        _last_min[0] = m
        if progress_fn and m > 0:
            progress_fn(40, "lipsync 已运行 %d 分钟" % m)

    try:
        rec = _poll_history(client_id, pid, timeout=LIPSYNC_TIMEOUT, on_tick=_tick)
    except ComfyError as e:
        # 把还在 ComfyUI 队列里的任务清掉，避免它继续空占显存
        try:
            _comfy("POST", "/interrupt", payload={}, timeout=30)
        except Exception:
            pass
        raise ComfyError("对口型推理失败：%s" % e)
    outs = _download_outputs(rec, prefix)
    if not outs:
        # 有些口型工作流走 SaveVideo/自定义节点，兜底再抓一次不限风格
        outs = _download_outputs(rec, "weaveora")
    if not outs:
        raise ComfyError("对口型无输出视频（prefix=%s）" % prefix)
    if progress_fn:
        progress_fn(100, "done")
    result = []
    for o in outs:
        w, h, dur = _probe_video_meta(o["bytes"])
        result.append({"bytes": o["bytes"], "mime": "video/mp4", "width": w,
                       "height": h, "duration_ms": dur})
    return result


def _concat_voice(voice_keys):
    """把多段配音按顺序拼成一个 wav（用本机 ffmpeg；失败时退回第一段）。"""
    import subprocess, tempfile
    blobs = []
    for k in voice_keys:
        try:
            # fetch_reference_bytes 返回 (bytes, ctype)
            b, _ct = fetch_reference_bytes(k)
            blobs.append(b)
        except Exception:
            continue
    if not blobs:
        raise ComfyError("配音素材读取失败")
    if len(blobs) == 1:
        return blobs[0]
    tmp = tempfile.mkdtemp(prefix="weaveora_voice_")
    paths = []
    for i, b in enumerate(blobs):
        fp = os.path.join(tmp, "v%d.bin" % i)
        with open(fp, "wb") as fh:
            fh.write(b)
        paths.append(fp)
    out = os.path.join(tmp, "out.wav")
    cmd = [_ffmpeg_exe(), "-y"]
    for fp in paths:
        cmd += ["-i", fp]
    cmd += ["-filter_complex", "".join("[%d:a]" % i for i in range(len(paths)))
            + "concat=n=%d:v=0:a=1[out]" % len(paths), "-map", "[out]", "-ar", "16000", out]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
        with open(out, "rb") as fh:
            return fh.read()
    except Exception:
        return blobs[0]


def _ffmpeg_exe():
    """本机 ffmpeg 路径（优先 imageio_ffmpeg 自带的，其次 PATH 里的 ffmpeg）。"""
    try:
        import imageio_ffmpeg as _iif
        return _iif.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def _wav_seconds(path):
    try:
        import wave
        with wave.open(path, "rb") as w:
            return w.getnframes() / float(w.getframerate() or 1)
    except Exception:
        return None


def _still_to_video(img_bytes, audio_bytes, duration_sec=None, size=512, fps=25):
    """关键帧静帧 + 配音 → mp4（口型工作流需要视频轨）。时长以实际配音为准。

    为什么必须做：LatentSync 工作流是 LoadVideo → GetVideoComponents，
    直接把 png 当 mp4 传进去 LoadVideo 会解码失败。
    """
    import subprocess, tempfile
    if not img_bytes:
        raise ComfyError("静帧画面为空，无法生成对口型输入视频")
    d = tempfile.mkdtemp(prefix="weaveora_still_")
    ip = os.path.join(d, "in.png")
    ap = os.path.join(d, "a.wav")
    op = os.path.join(d, "out.mp4")
    with open(ip, "wb") as fh:
        fh.write(img_bytes)
    with open(ap, "wb") as fh:
        fh.write(audio_bytes or b"")
    dur = _wav_seconds(ap) or float(duration_sec or 0) or 3.0
    r = subprocess.run(
        [_ffmpeg_exe(), "-y", "-loglevel", "error", "-loop", "1", "-i", ip, "-t", "%.3f" % dur,
         "-r", str(fps), "-vf",
         "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2"
         % (size, size, size, size),
         "-pix_fmt", "yuv420p", "-c:v", "libx264", op],
        capture_output=True, text=True, timeout=300)
    if r.returncode != 0 or not os.path.exists(op):
        raise ComfyError("静帧转视频失败: %s" % (r.stderr or "")[-300:])
    with open(op, "rb") as fh:
        return fh.read()
