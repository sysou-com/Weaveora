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


def _poll_history(client_id, prompt_id, poll=2.0, timeout=600):
    deadline = time.time() + timeout
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
    w = (payload.get("params") or {}).get("width", 768)
    h = (payload.get("params") or {}).get("height", 768)
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": mp4, "mime": "video/mp4", "width": int(w), "height": int(h)}]


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
