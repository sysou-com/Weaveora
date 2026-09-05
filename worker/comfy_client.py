#!/usr/bin/env python3
"""Weaveora comfy worker 引擎（W4：真 ComfyUI txt2img + IP-Adapter 一致性锚定）。

用法见 stub_worker.py：WEAVEORA_WORKER_MODE=comfy 时走本模块；
WEAVEORA_COMFY_URL 指向 ComfyUI（默认 http://127.0.0.1:8188）。
本模块只依赖标准库（urllib），不依赖 comfyui 客户端库。
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

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
    """经 weaveora 内部通道取参考图原始字节（token 鉴权）。"""
    q = urllib.parse.quote(base64.urlsafe_b64encode(storage_key.encode()).decode(), safe="")
    req = urllib.request.Request("%s/internal/assets?key=%s" % (API, q),
                                 headers={"X-Worker-Token": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read(), (r.headers.get("Content-Type") or "image/png")
    except urllib.error.HTTPError as e:
        raise ComfyError("fetch ref asset %s -> %s" % (storage_key, e.code))



_KS_INFO = None


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
            reference_image_name=None, prefix="weaveora"):
    """构造 ComfyUI prompt（txt2img；带参考图则加 IP-Adapter 分支）。"""
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

    if reference_image_name:
        # IP-Adapter（参考图 → 主体一致性，产品/物体/虚构人物；§30 #23）
        # ComfyUI_IPAdapter_plus 真实拓扑：UnifiedLoader 预载 clip_vision+adapter，
        # apply 节点(IPAdapter=IPAdapterSimple) 仅改 model（单 MODEL 输出），提示词仍走原 pos/neg。
        nodes["load_ref"] = {"class_type": "LoadImage",
                             "inputs": {"image": reference_image_name}}
        nodes["ip_unified"] = {"class_type": "IPAdapterUnifiedLoader",
                               "inputs": {"preset": params.get("ipadapter_preset",
                                                                   "STANDARD (medium strength)"),
                                           "model": ["ckpt", 0]}}
        nodes["ip_apply"] = {"class_type": "IPAdapter",
                             "inputs": {"model": ["ip_unified", 0],
                                        "ipadapter": ["ip_unified", 1],
                                        "image": ["load_ref", 0],
                                        "weight": float(params.get("ipadapter_weight", 0.85)),
                                        "start_at": 0.0, "end_at": 1.0,
                                        "weight_type": params.get("ipadapter_weight_type", "standard")}}
        nodes["ksampler"]["inputs"]["model"] = ["ip_apply", 0]

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

    ref_name = None
    ref_keys = payload.get("referenceKeys") or []
    if ref_keys:
        try:
            data, ctype = fetch_reference_bytes(ref_keys[0])
            _, body = _comfy("POST", "/upload/image",
                             files={"image": (ref_keys[0].split("/")[-1], data, ctype)})
            ref_name = json.loads(body.decode()).get("name")
        except Exception:
            ref_name = None

    last_err = None
    try:
        prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                         reference_image_name=ref_name, prefix=prefix)
        pid = _post_prompt(prompt, client_id)
        rec = _poll_history(client_id, pid)
        return _download_outputs(rec, prefix)
    except ComfyError as e:
        last_err = e
        # IP-Adapter 图未用上或节点缺失 → 降级纯 txt2img
        if ref_name is not None and FALLBACK:
            prompt = _prompt(client_id, positive, negative, params, seed, width, height,
                             reference_image_name=None, prefix=prefix)
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



def _wan_graph(client_id, payload, positive, negative, first_frame_name, prefix="weaveora"):
    """Wan i2v（关键帧→运动）图（§11.2 wan_i2v 档位）。

    注意：真实节点名/加载器随安装的 Wan 节点包而异（ComfyUI-WanVideoWrapper 等），
    生产接入 GPU 机时按实际安装微调（可整体用 WEAVEORA_COMFY_WORKFLOW_WAN 覆盖为 JSON 文件）。
    """
    fps = int(payload.get("fps") or 24)
    steps = int((payload.get("params") or {}).get("steps", 20))
    cfg = float((payload.get("params") or {}).get("cfg", 5.0))
    duration = float(payload.get("duration_sec") or 3.0)
    seed = int(payload.get("seed") or 1)
    # 帧数 = duration*fps（clamp 8..81）
    frames = max(8, min(81, int(duration * fps)))
    nodes = {
        "load_model": {"class_type": "WanVideoModelLoader",
                       "inputs": {"ckpt_name": (payload.get("params") or {}).get("model", "wan2.6_14B_fp8.safetensors")}},
        "load_clip": {"class_type": "WanVideoTextEncoder",
                      "inputs": {"clip": ["load_model", 1], "text": positive, "start_time": 0, "end_time": duration}},
        "load_img": {"class_type": "LoadImage", "inputs": {"image": first_frame_name}},
        "img2vid": {"class_type": "WanImageToVideo",
                    "inputs": {"model": ["load_model", 0], "positive": ["load_clip", 0],
                               "negative": ["load_clip", 1], "image": ["load_img", 0],
                               "seed": seed, "steps": steps, "cfg": cfg,
                               "width": 1024, "height": 1024, "frames": frames,
                               "start_frames": 1, "end_frames": 1, "filename_prefix": prefix}},
        "save": {"class_type": "VHS_VideoCombine",
                 "inputs": {"images": ["img2vid", 0], "fps": fps,
                            "filename_prefix": prefix, "format": "mp4"}},
    }
    return {"prompt": nodes, "client_id": client_id}


def _download_generic(rec, prefix="weaveora"):
    outs = []
    outputs = rec.get("outputs") or {}
    for node in outputs.values():
        for key, fmt in (("gifs", "image/webp"), ("video", "video/mp4"), ("images", "image/png")):
            for item in node.get(key) or []:
                fname = item.get("filename", "")
                if not fname.startswith(prefix):
                    continue
                sub = item.get("subfolder") or ""
                typ = item.get("type") or "output"
                q = urllib.parse.urlencode({"filename": fname, "subfolder": sub, "type": typ})
                _, body = _comfy("GET", "/view?" + q)
                if key == "video" and fname.endswith((".mp4", ".webm")):
                    mime = "video/mp4" if fname.endswith(".mp4") else "video/webm"
                elif key == "gifs":
                    mime = "image/webp"
                else:
                    mime = fmt
                outs.append({"bytes": body, "mime": mime, "width": item.get("width"), "height": item.get("height")})
                break
    return outs


def generate_motion(client_id, payload, progress_fn=None):
    """Wan i2v motion；返回 [{bytes,mime,width,height}]。真节点缺失抛 ComfyError。"""
    if progress_fn:
        progress_fn(25, "loading_model")
    key = payload.get("keyframeKey")
    if not key:
        raise ComfyError("clip 任务缺少 keyframeKey（先出关键帧）")
    data, ctype = fetch_reference_bytes(key)
    positive = payload.get("positive_prompt", "")
    negative = payload.get("negative_prompt", "")
    prefix = "weaveora_" + str(payload.get("shot_no") or "motion")
    if progress_fn:
        progress_fn(35, "sampling")
    _, body = _comfy("POST", "/upload/image",
                     files={"image": (key.split("/")[-1], data, ctype)})
    name = json.loads(body.decode()).get("name")
    prompt = _wan_graph(client_id, payload, positive, negative, name, prefix)
    st, resp = _comfy("POST", "/prompt", payload=prompt)
    pid = json.loads(resp.decode()).get("prompt_id")
    if not pid:
        raise ComfyError("comfy /prompt 无 prompt_id")
    rec = _poll_history(client_id, pid)
    return _download_generic(rec, prefix)

if __name__ == "__main__":
    import sys
    print("comfy engine url=%s api=%s" % (COMFY, API))
