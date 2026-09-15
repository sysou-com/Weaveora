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


# ── 节点补丁版本/能力校验（2026-09-14）────────────────────────────────────
# 为什么：worker 跑在 API 服务器、LatentSync 节点跑在 GPU 服务器，节点侧只能手工同步。
# 曾实测：GPU 机上是旧副本（旧 inference.py 不认「内联 JSON 规格」）→ 退回「取最大脸」→
# 两段台词都驱动同一张脸、画面被毁，而 worker 这边完全看不出异常，只表现为「效果不对」。
# 所以这里先问节点要版本，缺能力就**直接失败**并给出修复指引，不再静默降级。
REQUIRED_NODE_FEATURES = ("point_lock", "inline_spec", "track_lock", "quality_gate", "paste_mask", "fps_pin")
NODE_PATCH_HOWTO = (
    "修复：在 GPU 服务器上执行\n"
    "  curl -fsSL https://sysou.com/weaveora-node/latentsync-node-patch.tar.gz -o /tmp/p.tar.gz"
    " && tar xzf /tmp/p.tar.gz -C /tmp && bash /tmp/latentsync-node/apply.sh\n"
    "然后**重启 ComfyUI**，再用 bash /tmp/latentsync-node/verify.sh 自检。"
    "（应急跳过：worker 环境变量 WEAVEORA_SKIP_NODE_CHECK=1）"
)
_NODE_VERSION_CACHE = {"at": 0.0, "base": None, "payload": None}


def _node_version(base, timeout=15, ttl=300):
    """取节点补丁版本（带缓存，避免每个任务都问一次）。拿不到返回 None。"""
    import time as _t
    now = _t.time()
    c = _NODE_VERSION_CACHE
    if c["payload"] is not None and c["base"] == base and (now - float(c["at"])) < ttl:
        return c["payload"]
    try:
        with urllib.request.urlopen(base + "/weaveora/version", timeout=timeout) as r:
            payload = json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        payload = None
    c.update({"at": now, "base": base, "payload": payload})
    return payload


def _require_node_features():
    """跑对口型前强校验 GPU 机上节点补丁的版本与能力。"""
    if os.environ.get("WEAVEORA_SKIP_NODE_CHECK", "") == "1":
        print("[comfy] 已跳过节点版本校验（WEAVEORA_SKIP_NODE_CHECK=1）", flush=True)
        return
    info = _node_version(COMFY)
    if info is None:
        raise ComfyError(
            "拿不到 GPU 服务器上 LatentSync 节点的版本接口（%s/weaveora/version）。\n"
            "这说明那台机器的节点还是**旧副本**（没打 Weaveora 补丁）—— 旧副本不认「点选人脸/内联规格」，"
            "会退回「取最大脸」，导致嘴型贴到别人脸上。\n%s" % (COMFY, NODE_PATCH_HOWTO))
    feats = info.get("features") or []
    missing = [f for f in REQUIRED_NODE_FEATURES if f not in feats]
    if missing:
        raise ComfyError(
            "GPU 服务器上 LatentSync 节点补丁**版本过旧**（版本 %s），缺少能力：%s。\n%s"
            % (info.get("version") or "未知", "、".join(missing), NODE_PATCH_HOWTO))
    print("[comfy] 节点补丁版本 %s，能力齐全 ✅" % info.get("version"), flush=True)


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



# ---------------------------------------------------------------------------
# 图生视频（motion）模型档案：Wan2.2 I2V-A14B **双专家 MoE**（ComfyUI 原生节点）
#
#   为什么不是单专家 5B、也不是「4 步蒸馏一路到底」：
#     Wan2.2 A14B 的**高噪声专家**负责整体布局与**大幅运动**，低噪声专家负责细节收尾。
#     把 4-step 蒸馏 LoRA 同样压在高噪声专家上 → 运动幅度被压扁（现象：慢动作 / 动态丢失）。
#     ⇒ 修法：高噪声专家**少蒸馏或完全不蒸馏**（强度 0 = 该专家不加 LoRA），低噪声专家可足量蒸馏。
#     实测口径（GPU#2 48G / 2026-09-14）：给 fp8 高噪声专家挂 LoRA，ComfyUI 要额外
#     dequantize 一份权重（13.3GiB fp8 → ~28GiB fp16）→ **48G 卡也 OOM**；且动态会被压扁。
#     ⇒ 默认 high=0.0（只给低噪声专家蒸馏）、low=1.0（换 LoRA 家族时强度约定会变，见下）。
#
#   档位（payload["params"]["preset"]；每一项都能用显式键覆盖）：
#     draft    4 步  switch 2   最快，动态最弱（只看构图）
#     balanced 6 步  switch 3   默认生产档
#     motion   8 步  switch 4   动态优先
#     hero     6 步  switch 3   抬高 cfg_high=3.5（关键镜；高噪声 LoRA 在所有档位都是 0）
#     full    24 步  switch 12  完全不蒸馏（画质上限，配合 sageattention）
#   切分口径：高/低噪声按总步数折半（4→2、6→3、8→4），与官方/社区一致。
#
#   显式覆盖键：steps / switch_step / cfg_high / cfg_low / cfg / lora_high / lora_low /
#               lora_high_name / lora_low_name / shift / sampler_name / scheduler /
#               model_high / model_low / model（单专家旧路径）/ vae / text_encoder / weight_dtype
#   环境默认档：WEAVEORA_MOTION_PRESET（缺省 balanced）
#   注：旧 engine settings 里的 params.steps / params.cfg 仍会被采纳（不静默替换用户设置），
#       但「蒸馏 LoRA + steps>12」是异常组合，会打 WARN。
# ---------------------------------------------------------------------------
MOTION_MODEL_HIGH = "wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors"
MOTION_MODEL_LOW = "wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors"
MOTION_LORA_HIGH = "Wan_2_2_I2V_A14B_HIGH_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
MOTION_LORA_LOW = "Wan_2_2_I2V_A14B_LOW_lightx2v_4step_lora_260412_rank_64_fp16.safetensors"
MOTION_VAE = "wan_2.1_vae.safetensors"          # I2V-A14B 用 2.1 VAE；wan2.2_vae 是 TI2V-5B 的
MOTION_TEXT_ENCODER = "umt5_xxl_fp8_e4m3fn_scaled.safetensors"

MOTION_PRESETS = {
    # ★ 2026-09-14 实测修正（GPU#2 48G）：**高噪声专家一律不蒸馏（lora_high=0）**。
    #   原因：给 fp8 的高噪声专家挂 LoRA 时，ComfyUI 需额外 dequantize 一份权重
    #   （13.3 GiB fp8 → 28 GiB fp16）× 两专家 → 在 **48GB 卡上也会 OOM**（实测 draft/balanced
    #   报 "Allocation on device ... out of memory"）；而 lora_high=0 的 hero 档实测通过：
    #   832×480 / 33 帧 / 6 步，**显存峰值 41.5 GiB、54 秒**出片。
    #   这正合本方案的初衷：高噪声专家负责大幅运动，越蒸馏动态越扁 —— 所以只给**低噪声**专家
    #   足量蒸馏，高噪声靠步数/cfg 调。需要旧行为时显式传 params.lora_high。
    "draft":    {"steps": 4,  "switch": 2,  "cfg_high": 1.0, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "balanced": {"steps": 6,  "switch": 3,  "cfg_high": 1.0, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "motion":   {"steps": 8,  "switch": 4,  "cfg_high": 1.5, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "hero":     {"steps": 6,  "switch": 3,  "cfg_high": 3.5, "cfg_low": 1.0, "lora_high": 0.0, "lora_low": 1.0, "shift": 5.0},
    "full":     {"steps": 24, "switch": 12, "cfg_high": 3.5, "cfg_low": 3.5, "lora_high": 0.0, "lora_low": 0.0, "shift": 5.0},
}
# 给高噪声专家挂 LoRA 的显存风险阈值：超过它就在日志里点名提醒（48G 实测 0.6 也 OOM）
MOTION_LORA_HIGH_WARN = 0.0
MOTION_PRESET_ENV = os.environ.get("WEAVEORA_MOTION_PRESET", "balanced").strip().lower()

# ★ 引擎配置下发的 motion 档位（随任务下发，见 api/…/EngineSettingsService.motionServices）。
#   为什么需要：clip 的 payload.params 只带 {width,height}，否则「UI 改档位没反应」。
#   生效优先级：payload.params（每镜显式） > 这里的下发值 > MOTION_PRESETS 档位默认。
MOTION_OVERRIDES = {}
MOTION_SERVICE_KEYS = ("preset", "steps", "switch", "switch_step", "cfg", "cfg_high", "cfg_low",
                       "lora_high", "lora_low", "lora_high_name", "lora_low_name", "shift",
                       "sampler_name", "scheduler", "model_high", "model_low", "mode", "dual",
                       "width", "height", "frames", "fps")

# 显存口径（A14B 双专家实测，GPU#2 48G，832×480）：
#   · 33 帧 → 峰值 41.8 GiB；121 帧 → 峰值 47.3 GiB（线性拟合）
#     ⇒ 峰值 ≈ BASE + PER_FRAME×帧数，BASE≈39.7 GiB、每帧≈0.0625 GiB（832×480）
#   · 同时实测：**64/84 帧反而 OOM、而 121 帧能过** ⇒ OOM 不是帧数导致的，
#     是**共存争显存**（CosyVoice 常驻 ~7GiB + ComfyUI 缓存双专家 26.6GiB）。
#     所以阈值用「运行时可用显存 vs 预估需求」而非常量帧上限；不够先让 TTS 让位，再不够就明确报错。
MOTION_VRAM_BASE_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_BASE_GB", "39.7") or 39.7)
MOTION_VRAM_PER_FRAME_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_PER_FRAME_GB", "0.0625") or 0.0625)
MOTION_VRAM_AREA_REF = 832.0 * 480.0   # 标定分辨率（换成其它分辨率按面积等比缩放）
# 卡片预留（GiB）：预估值与总显存留这么多余地；只用来挡「根本放不下」的组合。
# 注意：**不能拿 /system_stats 的 vram_free 当判据** —— ComfyUI 自己缓存的双专家（26.6GiB）
# 是可以回收的，实测就吃过这个坑：1280x704/48 帧被误拦（当时 free 只有 20GiB）。
MOTION_VRAM_SAFETY_GB = float(os.environ.get("WEAVEORA_MOTION_VRAM_SAFETY_GB", "0") or 0)

# 总显存低于这个值就别浪费 GPU 时间了（A14B 硬门槛：24G 卡无论如何跑不了）
MOTION_MIN_FREE_GB = float(os.environ.get("WEAVEORA_MOTION_MIN_FREE_GB", "30") or 30)
# motion 出片分辨率：A14B 的甜点是 **480p 级**（官方模板就是 640×640；实测 832×480 比 1280×704
# 快 5~10 倍，而且不会把 48G 卡跑到 47.4/47.4 GiB 反复换入换出 —— 实测 720p/48 帧要 >600s）。
# 默认把长边压到 ≤ MOTION_DEFAULT_LONG_SIDE；params.resolution=720p/原始 可放开。
MOTION_DEFAULT_LONG_SIDE = int(os.environ.get("WEAVEORA_MOTION_LONG_SIDE", "832") or 832)

# ★ Wan2.2 I2V-A14B 的**原生节奏 = 16fps**（ComfyUI 官方模板 CreateVideo fps=16）。
#   踩过的坑（2026-09-15 线上）：按项目 fps(30) 生成同样帧数 → 运动被 1.875× 加速
#   （用户反馈「像开了倍速」），而且时长变成 帧数/30（5s 的镜头只出 4.13s）。
#   正确做法：**按 16fps 决定帧数**（= 时长 × 16），先按 16fps 出片（速度/时长都对），
#   再插值到项目 fps（ffmpeg minterpolate，失败则退化为补帧）。
MOTION_NATIVE_FPS = float(os.environ.get("WEAVEORA_MOTION_NATIVE_FPS", "16") or 16)


def _motion_resolution(params, width, height):
    """motion 实际出片尺寸：按「分辨率上限」压到桶内（保持画幅，宽高 /16 对齐）。

    params.resolution / services.motion.resolution 语义 = **上限**（来自引擎配置里的
    「GPU 服务器最大支持分辨率」）：
      480p → 长边 ≤ 832 ｜ 720p → ≤ 1280 ｜ 1080p → ≤ 1920
      auto / full / original / source → 不限制
      空/未识别 → 用环境默认上限（WEAVEORA_MOTION_LONG_SIDE，缺省 832，即 480p 级）
    实测依据：48G 卡 1280×704/48 帧要 >600s（跑满显存反复换入换出），832×480 只要 27~50s；
    A14B 的甜点本来就是 480p 级（ComfyUI 官方模板 = 640×640）。
    """
    res = str((params or {}).get("resolution") or "").strip().lower()
    w, h = int(width), int(height)
    if res in ("full", "original", "source", "auto", "none"):
        return w, h
    caps = {"480p": 832, "480": 832, "720p": 1280, "720": 1280, "1080p": 1920, "1080": 1920}
    long_side = caps.get(res, MOTION_DEFAULT_LONG_SIDE)
    if long_side <= 0 or max(w, h) <= long_side:
        return w, h
    scale = float(long_side) / float(max(w, h))
    nw = max(16, int(round(w * scale / 16.0)) * 16)
    nh = max(16, int(round(h * scale / 16.0)) * 16)
    return nw, nh

MOTION_MIN_TOTAL_GB = float(os.environ.get("WEAVEORA_MOTION_MIN_TOTAL_GB", "44") or 44)
# 出片轮询上限（秒）。旧值 600s 是 TI2V-5B 时代的；A14B 双专家在 720p 下光采样就要 10~30 分钟
# （线上实测：1280x704/48 帧跑到 604s 被 timeout 打断）。默认 2700s（45 分钟），可用 env 调。
MOTION_TIMEOUT = float(os.environ.get("WEAVEORA_MOTION_TIMEOUT", "2700") or 2700)


def _motion_vram_need_gb(frames, width, height):
    """预估一次 A14B 双专家出片的峰值显存（GiB）。

    按实测标定（832×480：33 帧 41.8 / 121 帧 47.3）+ 分辨率面积等比；
    只用于**跑前体检与明确报错**，不追求精确：真正上限看运行时可用显存。
    """
    area = max(1.0, float(width) * float(height))
    scale = area / MOTION_VRAM_AREA_REF
    return MOTION_VRAM_BASE_GB + MOTION_VRAM_PER_FRAME_GB * float(frames) * scale


def _snake_key(k):
    """camelCase → snake_case（switchStep → switch_step）；已是 snake_case 的原样返回。"""
    out = []
    for ch in str(k or ""):
        if ch.isupper():
            out.append("_")
            out.append(ch.lower())
        else:
            out.append(ch)
    return "".join(out)


def _motion_params(payload):
    """本镜 motion 参数：引擎配置下发的档位 ← payload.params 覆盖（每镜显式值优先）。"""
    p = dict(MOTION_OVERRIDES)
    raw = (payload or {}).get("params")
    if isinstance(raw, dict):
        p.update(raw)
    return p


def _motion_plan(params):
    """preset 打底 + 显式键覆盖 → 采样计划（纯函数，便于扫描实验）。"""
    params = params or {}
    name = str(params.get("preset") or MOTION_PRESET_ENV or "balanced").strip().lower()
    if name not in MOTION_PRESETS:
        print("[comfy] 未知 motion preset=%s，回退 balanced" % name, flush=True)
        name = "balanced"
    plan = dict(MOTION_PRESETS[name])
    plan["preset"] = name

    plan["steps"] = max(1, int(params.get("steps") or plan["steps"]))
    sw = params.get("switch_step")
    if sw is None:
        sw = params.get("switch")
    if sw is not None:
        plan["switch"] = max(1, min(plan["steps"] - 1, int(sw))) if plan["steps"] > 1 else 1
    else:
        plan["switch"] = max(1, plan["steps"] // 2)   # 4→2 / 6→3 / 8→4 / 24→12

    plan["cfg_high"] = float(params.get("cfg_high", params.get("cfg", plan["cfg_high"])))
    plan["cfg_low"] = float(params.get("cfg_low", params.get("cfg", plan["cfg_low"])))
    plan["lora_high"] = float(params.get("lora_high", params.get("lora_strength_high", plan["lora_high"])))
    plan["lora_low"] = float(params.get("lora_low", params.get("lora_strength_low", plan["lora_low"])))
    plan["shift"] = float(params.get("shift", plan["shift"]))
    plan["sampler"] = str(params.get("sampler_name") or "euler")
    plan["scheduler"] = str(params.get("scheduler") or "simple")
    if plan["steps"] > 12 and (plan["lora_high"] or plan["lora_low"]):
        print("[comfy] WARN steps=%d 仍在用蒸馏 LoRA（>12 步时蒸馏意义不大；"
              "若要高步数请用 preset=full 并把 lora_* 归零）" % plan["steps"], flush=True)
    return plan


def _motion_assets(params, dual):
    """解析素材名并对 object_info 做存在性校验（错误信息直接点出缺哪个文件）。"""
    p = params or {}

    def _need(class_type, key, wanted, label):
        opts = _node_input_options(class_type, key)
        if opts and wanted not in opts:
            raise ComfyError("%s 不存在：%s（ComfyUI %s 可用：%s）"
                             % (label, wanted, class_type, "、".join(opts[:6]) or "无"))
        return wanted

    a = {"dual": bool(dual)}
    a["clip"] = _need("CLIPLoader", "clip_name", p.get("text_encoder") or MOTION_TEXT_ENCODER, "文本编码器")
    a["vae"] = _need("VAELoader", "vae_name", p.get("vae") or MOTION_VAE, "VAE")
    if dual:
        a["high"] = _need("UNETLoader", "unet_name", p.get("model_high") or MOTION_MODEL_HIGH, "高噪声专家")
        a["low"] = _need("UNETLoader", "unet_name", p.get("model_low") or MOTION_MODEL_LOW, "低噪声专家")
    else:
        a["single"] = _need("UNETLoader", "unet_name", p.get("model") or MOTION_MODEL_HIGH, "扩散模型")
    a["lora_high_name"] = p.get("lora_high_name") or MOTION_LORA_HIGH
    a["lora_low_name"] = p.get("lora_low_name") or MOTION_LORA_LOW
    # fp8_scaled 权重自带 scale：weight_dtype 必须 default（旧 settings 里的
    # quantization=fp8_e4m3fn 是给 fp16 的 5B 文件用的，套到 fp8_scaled 上会掉画质）
    wd = p.get("weight_dtype")
    if not wd:
        names = [a.get("high"), a.get("low"), a.get("single")]
        if any(n and n.endswith("_fp8_scaled.safetensors") for n in names):
            wd = "default"
            if p.get("quantization"):
                print("[comfy] 忽略 quantization=%s（fp8_scaled 权重用 default）" % p.get("quantization"), flush=True)
        else:
            wd = p.get("quantization") or "default"
    a["weight_dtype"] = wd
    return a


def _motion_base_nodes(positive, negative, first_frame_name, a, width, height, length):
    return {
        "clip": {"class_type": "CLIPLoader",
                 "inputs": {"clip_name": a["clip"], "type": "wan"}},
        "vae": {"class_type": "VAELoader",
                "inputs": {"vae_name": a["vae"]}},
        "pos": {"class_type": "CLIPTextEncode",
                "inputs": {"text": positive, "clip": ["clip", 0]}},
        "neg": {"class_type": "CLIPTextEncode",
                "inputs": {"text": negative, "clip": ["clip", 0]}},
        "img": {"class_type": "LoadImage", "inputs": {"image": first_frame_name}},
        # ★ 必须用**原生** WanImageToVideo（2026-09-14 修）：它按 Wan2.2 14B 的
        #   patch_size=2 口径造潜变量，并顺便把正/负条件一起吐出来（输出 0/1/2）。
        #   之前这里用的是 Wan22ImageToVideoLatent（旧的自定义路径，按 patch=1 造 60×104）
        #   → A14B 模型内部按 2×2 patchify 得 30×52，两者对不上，
        #   报 “The expanded size of the tensor (52) must match the existing size (104)”。
        #   对照：ComfyUI 自带官方模板 video_wan2_2_14B_i2v.json 用的就是 WanImageToVideo。
        "latent": {"class_type": "WanImageToVideo",
                   "inputs": {"vae": ["vae", 0], "start_image": ["img", 0],
                              "positive": ["pos", 0], "negative": ["neg", 0],
                              "width": width, "height": height, "length": length,
                              "batch_size": 1}},
    }


def _motion_expert(nodes, tag, unet_name, weight_dtype, lora_name, lora_strength, shift):
    """一条专家支路：UNETLoader →（可选 LoRA）→ ModelSamplingSD3；返回 model 来源。"""
    nodes["unet_" + tag] = {"class_type": "UNETLoader",
                            "inputs": {"unet_name": unet_name, "weight_dtype": weight_dtype}}
    src = ["unet_" + tag, 0]
    if lora_name and lora_strength:
        nodes["lora_" + tag] = {"class_type": "LoraLoaderModelOnly",
                               "inputs": {"model": src, "lora_name": lora_name,
                                          "strength_model": lora_strength}}
        src = ["lora_" + tag, 0]
    nodes["shift_" + tag] = {"class_type": "ModelSamplingSD3",
                             "inputs": {"model": src, "shift": shift}}
    return ["shift_" + tag, 0]


def _motion_graph_frames(payload, params):
    """帧数/分辨率口径：frames=4n、latent length=frames+1。

    ★ 帧数按 **原生 16fps × 镜头时长** 取（16fps 是 A14B 的原生节奏），
      payload.frames（UI 选的帧数）只当**上限**：min(时长×16, 上限)。
      为什么不再直接用 payload.frames（2026-09-15 线上事故）：
        UI 选 121 帧时按 30fps 出片 → 运动快 1.875×（像开了倍速），且 5s 镜头只剩 4.13s。
    """
    duration = float(payload.get("duration_sec") or 2.0)
    native = MOTION_NATIVE_FPS
    want = int(round(duration * native))              # 时长 × 原生 fps
    cap = payload.get("frames")
    if isinstance(cap, int) and 32 <= cap <= 128:
        want = min(want, int(cap))                    # UI 只当上限
    raw = max(32, min(121, want))                     # A14B 原生上限 121（官方模板）
    frames = int((raw + 3) / 4) * 4                   # 4n 对齐（latent = frames+1）
    width = int(params.get("width", 768))
    height = int(params.get("height", 768))
    return frames, width, height, frames + 1


def _motion_graph(client_id, payload, positive, negative, first_frame_name, prefix):
    """构造图生视频 prompt。

    双专家（默认，Wan2.2 I2V-A14B 高/低噪声）或单专家（params.model 显式给了单文件时走旧路径）。
    输出帧由 generate_motion 用 ffmpeg 合成 mp4（不依赖 VHS）。
    """
    params = _motion_params(payload)
    plan = _motion_plan(params)
    # P1：自托管路径**不能**因为 params.model 里有云模型名就退化成单专家
    #   （旧写法 `dual = not params.get("model")`：云模型名如 minimax/video-01 会让 worker
    #   拿它去 UNETLoader 找文件 → 报「扩散模型不存在」）。
    #   只认：显式 mode=single / dual=false / model 形如本地文件（*.safetensors）。
    _m = str(params.get("model") or "")
    _want_single = (str(params.get("mode") or "").strip().lower() == "single"
                    or params.get("dual") is False
                    or _m.endswith(".safetensors"))
    if _m and not _want_single:
        print("[comfy] 忽略 params.model=%r（云模型名不影响自托管双专家路由）" % _m, flush=True)
    if not _want_single:
        params.pop("model", None)
    dual = not _want_single
    a = _motion_assets(params, dual)
    seed = int(payload.get("seed") or 1)
    frames, width, height, length = _motion_graph_frames(payload, params)
    sampler = _pick_option("KSamplerAdvanced", "sampler_name", plan["sampler"], "euler")
    scheduler = _pick_option("KSamplerAdvanced", "scheduler", plan["scheduler"], "simple")

    nodes = _motion_base_nodes(positive, negative, first_frame_name, a, width, height, length)

    if dual:
        hi = _motion_expert(nodes, "hi", a["high"], a["weight_dtype"],
                            a["lora_high_name"], plan["lora_high"], plan["shift"])
        lo = _motion_expert(nodes, "lo", a["low"], a["weight_dtype"],
                            a["lora_low_name"], plan["lora_low"], plan["shift"])
        nodes["sampler_hi"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": hi, "add_noise": "enable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_high"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["latent", 2], "start_at_step": 0,
            "end_at_step": plan["switch"], "return_with_leftover_noise": "enable"}}
        nodes["sampler_lo"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": lo, "add_noise": "disable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_low"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["sampler_hi", 0], "start_at_step": plan["switch"],
            "end_at_step": 10000, "return_with_leftover_noise": "disable"}}
        tail = ["sampler_lo", 0]
        shape = "dual 高/低噪声"
    else:
        one = _motion_expert(nodes, "hi", a["single"], a["weight_dtype"],
                             a["lora_high_name"], plan["lora_high"], plan["shift"])
        nodes["sampler"] = {"class_type": "KSamplerAdvanced", "inputs": {
            "model": one, "add_noise": "enable", "noise_seed": seed,
            "steps": plan["steps"], "cfg": plan["cfg_high"],
            "sampler_name": sampler, "scheduler": scheduler,
            "positive": ["latent", 0], "negative": ["latent", 1],
            "latent_image": ["latent", 2], "start_at_step": 0,
            "end_at_step": 10000, "return_with_leftover_noise": "disable"}}
        tail = ["sampler", 0]
        shape = "single 单专家"

    nodes["dec"] = {"class_type": "VAEDecode",
                    "inputs": {"samples": tail, "vae": ["vae", 0]}}
    nodes["save"] = {"class_type": "SaveImage",
                     "inputs": {"images": ["dec", 0], "filename_prefix": prefix}}

    lora_txt = "hi=%s(%s) lo=%s(%s)" % (plan["lora_high"],
                                        "—" if not plan["lora_high"] else a["lora_high_name"][:28],
                                        plan["lora_low"],
                                        "—" if not plan["lora_low"] else a["lora_low_name"][:28])
    if float(plan.get("lora_high") or 0) > 0:
        print("[comfy] WARN lora_high=%.2f：给高噪声 fp8 专家挂 LoRA 需额外 dequantize 一份权重"
              "（13.3GiB fp8 → ~28GiB fp16），实测 48G 卡也会 OOM；动态也会被压扁。"
              "除非显式要求，建议保持 0（只给低噪声专家蒸馏）。" % float(plan["lora_high"]), flush=True)
    print("[comfy] motion %s preset=%s steps=%d(switch %d) cfg=%s/%s shift=%s "
          "%dx%d %d帧 lora %s sampler=%s/%s weight=%s"
          % (shape, plan["preset"], plan["steps"], plan["switch"], plan["cfg_high"],
             plan["cfg_low"], plan["shift"], width, height, frames, lora_txt,
             sampler, scheduler, a["weight_dtype"]), flush=True)
    return {"prompt": nodes, "client_id": client_id}


def vram_stats():
    """ComfyUI 视角的显存 (free_gb, total_gb)；取不到返回 (None, None)。

    给 stub_worker 做「出视频前让 TTS 让出显存」的判据用（见 worker/stub_worker.py）。
    """
    try:
        _, body = _comfy("GET", "/system_stats", timeout=20)
        dev = (json.loads(body.decode()).get("devices") or [{}])[0]
        return (float(dev.get("vram_free") or 0) / 1073741824.0,
                float(dev.get("vram_total") or 0) / 1073741824.0)
    except Exception:
        return (None, None)


def _vram_note(tag):
    """打一行 ComfyUI 视角的显存体检；余量 < MOTION_MIN_FREE_GB 时提示。

    A14B 双专家实测峰值 41.8~42.4 GiB（832×480/33 帧），所以阈值不是旧 24G 卡的 15GiB。
    """
    free, total = vram_stats()
    if free is None:
        print("[comfy] %s 取显存失败（忽略）" % tag, flush=True)
        return
    print("[comfy] %s 显存 free=%.1f/%.1f GiB" % (tag, free, total), flush=True)
    if free < MOTION_MIN_FREE_GB:
        print("[comfy] WARN 显存余量 %.1f GiB < %.1f GiB：14B 双专家会频繁换入换出，"
              "请确认 TTS 未常驻（WEAVEORA_TTS_PRELOAD=0）或已让出显存"
              % (free, MOTION_MIN_FREE_GB), flush=True)


def _retime_to_fps(mp4, src_fps, dst_fps, target_frames=None):
    """把 mp4 从 src_fps 重定时到 dst_fps（**保持时长与速度**）。

    为什么：A14B 按原生 16fps 生成（速度正确），而项目成片是 30fps。
    直接改容器 fps 会把时长缩短、速度变快；所以要**插值补帧**：
      · 优先 ffmpeg `minterpolate=fps=N:mi_mode=mci`（运动补偿插值，画面顺滑）
      · 失败/无此滤镜 → 退化为 `-r N`（重复帧，时长速度仍正确，只是略顿）

    target_frames：目标总帧数（= round(镜头时长 × dst_fps)）。给了就把它**做成帧精确**：
      短了用 tpad 克隆尾帧补齐、长了截断。
      为什么（2026-09-15 线上）：81 帧@16fps 插值到 30fps 得到 149 帧 = 4.97s，
      播放器按整秒显示就是「4 秒」——用户会以为时长又不对。
    src == dst（且无需补帧）时原样返回。
    """
    try:
        src_fps = float(src_fps or 0)
        dst_fps = float(dst_fps or 0)
    except (TypeError, ValueError):
        return mp4
    need_retime = dst_fps > 0 and abs(dst_fps - src_fps) >= 0.01
    try:
        tf = int(target_frames) if target_frames else 0
    except (TypeError, ValueError):
        tf = 0
    if not need_retime and tf <= 0:
        return mp4
    import subprocess as _sp
    import tempfile as _tf
    ff = _ffmpeg_exe()
    d = _tf.mkdtemp(prefix="wv_retime_")
    try:
        src = os.path.join(d, "in.mp4")
        dst = os.path.join(d, "out.mp4")
        with open(src, "wb") as fh:
            fh.write(mp4)
        base = "minterpolate=fps=%g:mi_mode=mci" % dst_fps if need_retime else "null"
        attempts = []
        if need_retime:
            attempts.append((base, "运动补偿插值"))
            attempts.append((None, "重复帧退化"))
        else:
            attempts.append((base, "仅补/截帧"))
        for vf, label in attempts:
            cmd = [ff, "-y", "-loglevel", "error", "-i", src]
            if vf == "null":
                pass
            elif vf:
                cmd += ["-vf", vf]
            else:
                cmd += ["-r", "%g" % dst_fps]
            if tf > 0:
                # 先克隆尾帧保证够长，再按目标帧数截断 → 帧精确
                if vf and vf != "null":
                    cmd += ["-vf", vf + ",tpad=stop_mode=clone:stop_duration=2"]
                else:
                    cmd += ["-vf", "tpad=stop_mode=clone:stop_duration=2"]
                cmd += ["-frames:v", str(tf)]
            cmd += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", dst]
            r = _sp.run(cmd, stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True)
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 0:
                with open(dst, "rb") as fh:
                    out = fh.read()
                nf = "?"
                try:
                    _w, _h, _du, _n = _probe_video_meta_ex(out)
                    nf = str(_n or "?")
                except Exception:
                    pass
                print("[comfy] 帧率补齐 %g → %gfps（%s）%s"
                      % (src_fps, dst_fps, label,
                         "，帧数 → %s（目标 %d）" % (nf, tf) if tf > 0 else ""), flush=True)
                return out
        print("[comfy] 帧率补齐失败（保持 %gfps 原样输出）" % src_fps, flush=True)
        return mp4
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _probe_video_meta_ex(mp4_bytes):
    """更详细的探测：返回 (w, h, duration_ms, nb_frames)。ffprobe 不可用时退到 ffmpeg -i 解析。"""
    import subprocess as _sp
    import tempfile as _tf
    import re as _re
    d = _tf.mkdtemp(prefix="wv_probe2_")
    try:
        p = os.path.join(d, "o.mp4")
        with open(p, "wb") as fh:
            fh.write(mp4_bytes)
        ff = _ffmpeg_exe()
        probe = os.path.join(os.path.dirname(ff), "ffprobe")
        if os.path.exists(probe):
            r = _sp.run([probe, "-v", "error", "-select_streams", "v:0",
                         "-show_entries", "stream=width,height,nb_frames",
                         "-show_entries", "format=duration", "-of", "default=nw=1", p],
                        stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True, timeout=120)
            out = r.stdout or ""
            w = _re.search(r"^width=(\d+)", out, _re.M)
            h = _re.search(r"^height=(\d+)", out, _re.M)
            n = _re.search(r"^nb_frames=(\d+)", out, _re.M)
            du = _re.search(r"^duration=([\d.]+)", out, _re.M)
            return (int(w.group(1)) if w else None, int(h.group(1)) if h else None,
                    int(round(float(du.group(1)) * 1000)) if du else None,
                    int(n.group(1)) if n else None)
        w, h, du = _probe_video_meta(mp4_bytes)
        return w, h, du, None
    except Exception:
        return None, None, None, None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _encode_frames_mp4(frames_bytes, fps, out_dir):
    import subprocess as _sp
    import tempfile
    # 统一走 _ffmpeg_exe()（支持 WEAVEORA_FFMPEG 覆盖 + PATH 回退）。
    # 旧写法直接 import imageio_ffmpeg 并用其自带二进制：老版本（4.2.2）不支持
    # `-fps_mode`（需 ffmpeg >= 4.3），会导致对口型拼接报 "Unrecognized option 'fps_mode'"。
    ff = _ffmpeg_exe()
    if not frames_bytes:
        raise ComfyError("motion 无输出帧")
    for i, b in enumerate(frames_bytes):
        with open(os.path.join(out_dir, "f_%04d.png" % i), "wb") as fh:
            fh.write(b)
    out = os.path.join(out_dir, "motion.mp4")
    r = _sp.run([ff, "-y", "-framerate", str(fps), "-i",
                 os.path.join(out_dir, "f_%04d.png"),
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", out],
                stdout=_sp.PIPE, stderr=_sp.PIPE, universal_newlines=True)  # py3.6 兼容（capture_output/text 需 3.7+）
    if r.returncode != 0 or not os.path.exists(out):
        raise ComfyError("ffmpeg 合成失败: %s" % (r.stderr or "")[-300:])
    with open(out, "rb") as fh:
        mp4 = fh.read()
    return mp4


def _motion_tick(progress_fn):
    """motion 采样期间的进度回调：每分钟报一次已耗时（720p 双专家可能跑很久）。

    为什么需要：旧的 `_poll_history` 默认 600s 超时且不报进度 —— 用户看到「50%」卡住十分钟
    然后 timeout，既不知道在跑、也不知道慢在哪。
    """
    state = {"last": -1}

    def _tick(elapsed):
        m = int(elapsed // 60)
        if m > 0 and m != state["last"]:
            state["last"] = m
            if progress_fn:
                progress_fn(60, "sampling 已 %d 分钟" % m)

    return _tick


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
    # ★ 帧率分两个：
    #   out_fps = 项目导出帧率（payload.fps，如 30）→ 出片后由 _retime_to_fps() 补帧到它
    #   fps     = 生成/编码帧率 = 模型原生节奏（A14B = 16fps）→ 保证**速度与时长正确**
    #   踩过的坑（2026-09-15）：以前直接用 payload.fps 生成+编码 → 运动快 1.875×（“像开了倍速”），
    #   且 5s 镜头只剩 4.13s。
    out_fps = int(payload.get("fps") or 16)
    fps = MOTION_NATIVE_FPS
    _mp = _motion_params(payload)
    # motion 固定 768×768（Comfy 原生 Wan2.2 方形档位；8GB fp8），关键帧缩放后上传保证一致
    mw = int(_mp.get("width", 768))
    mh = int(_mp.get("height", 768))
    # 分辨率口径：默认压到 480p 桶（A14B 甜点 + 48G 卡不换入换出），显式 720p 才放开
    _w0, _h0 = mw, mh
    mw, mh = _motion_resolution(_mp, mw, mh)
    if (mw, mh) != (_w0, _h0):
        print("[comfy] motion 分辨率 %dx%d → %dx%d（默认 480p 桶；要原分辨率请设 resolution=720p）"
              % (_w0, _h0, mw, mh), flush=True)
    # P2：A14B 双专家实测峰值 ~42 GiB → 总显存不够就**快速失败并点名换机**
    # （旧行为：硬跑 → OOM，报一堆看不懂的错；24G 卡无论如何跑不了 A14B）
    if str(_mp.get("mode") or "").strip().lower() != "single":
        _free, _total = vram_stats()
        _frames = _motion_graph_frames(payload, _mp)[0]
        # ★ 必须用**压后**尺寸（mw/mh）估算：线上踩过 —— 已把 1280×704 压成 832×464，
        #   体检却仍按 1280×704 算 → 误报「放不下」（124 帧报 57.2 GiB，实际只需 ~47 GiB）
        _need = _motion_vram_need_gb(_frames, mw, mh)
        if _total is not None and _total < MOTION_MIN_TOTAL_GB:
            raise ComfyError(
                "本机 ComfyUI 总显存 %.1f GiB < A14B 双专家所需 %.0f GiB（实测峰值 ~42–47 GiB）："
                "Wan2.2 I2V-A14B 在这台机器上跑不了。\n"
                "  修法一（推荐）：把 clip 任务路由到 48G 的 GPU#2（生成引擎配置 → GPU 服务器地址）\n"
                "  修法二：显式降级单专家（params.mode=single + params.model=本地 5B 权重名）"
                % (_total, MOTION_MIN_TOTAL_GB))
        if _free is not None and _free < _need:
            # 先请 ComfyUI 释放自己的缓存（双专家 26.6GiB 是可回收的），再重新量一次
            try:
                _free_comfy_models()
                _free2, _total2 = vram_stats()
                if _free2 is not None:
                    _free, _total = _free2, (_total2 or _total)
                print("[comfy] 已请 ComfyUI 释放缓存，可用显存 → %.1f GiB" % _free, flush=True)
            except Exception as _e:
                print("[comfy] 释放 ComfyUI 缓存失败（忽略）: %s" % _e, flush=True)
        # 判据：**只挡「根本放不下」的组合**（预估 > 总显存 - 预留）。
        # free < need 但仍能放下 → 给 WARN 继续跑，交给 ComfyUI 自己换入换出
        # （旧写法拿 free 硬拦，会把本来能跑的任务误杀 —— 实测 121 帧 47.3GiB 是能跑的）。
        if _total is not None and _need > (_total - MOTION_VRAM_SAFETY_GB):
            _area_scale = (float(mw) * float(mh)) / MOTION_VRAM_AREA_REF
            _max_frames = max(32, int((_total - MOTION_VRAM_SAFETY_GB - MOTION_VRAM_BASE_GB)
                                      / max(1e-6, MOTION_VRAM_PER_FRAME_GB * _area_scale)))
            _max_px = int(((_total - MOTION_VRAM_SAFETY_GB - MOTION_VRAM_BASE_GB) /
                           max(1e-6, MOTION_VRAM_PER_FRAME_GB * float(_frames))) * MOTION_VRAM_AREA_REF)
            raise ComfyError(
                "这次 motion 放不下：%dx%d / %d 帧 预估需 %.1f GiB，本机总显存 %.1f GiB。\n"
                "  ① 本分辨率下最多约 %d 帧；或总像素降到约 %d\n"
                "  ② 降分辨率（如 832x480）往往比降帧数划算\n"
                "  ③ 跨镜并发时确认没有其它任务同时占卡（A14B 会独占）"
                % (mw, mh, _frames, _need, _total, _max_frames, _max_px))
        if _free is not None and _free < _need:
            print("[comfy] WARN 当前可用 %.1f GiB < 预估 %.1f GiB：ComfyUI 会自行换入换出，"
                  "若 OOM 请降分辨率或帧数" % (_free, _need), flush=True)
        elif _free is not None:
            print("[comfy] motion 显存体检：预估需 %.1f GiB，可用 %.1f/%.1f GiB"
                  % (_need, _free, _total or 0), flush=True)
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
    _vram_note("motion 前")
    prompt = _motion_graph(client_id, payload, positive, negative, name, prefix)
    st, resp = _comfy("POST", "/prompt", payload=prompt)
    pid = json.loads(resp.decode()).get("prompt_id")
    if not pid:
        raise ComfyError("comfy /prompt 无 prompt_id")
    if progress_fn:
        progress_fn(50, "sampling")
    rec = _poll_history(client_id, pid, timeout=MOTION_TIMEOUT, on_tick=_motion_tick(progress_fn))
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
        mp4 = _encode_frames_mp4(frames, fps, out_dir)   # fps = 原生 16（速度/时长正确）
    finally:
        import shutil as _sh
        _sh.rmtree(out_dir, ignore_errors=True)
    # 再补齐到项目 fps（16 → 30）：保持时长与速度，只是补帧；并做成**帧精确**
    # （否则 81 帧@16 → 149 帧 = 4.97s，播放器按整秒显示成「4 秒」）
    _target = 0
    try:
        _target = int(round(float(payload.get("duration_sec") or 0) * out_fps))
    except (TypeError, ValueError):
        _target = 0
    mp4 = _retime_to_fps(mp4, fps, out_fps, target_frames=_target)
    print("[comfy] motion 出片：%d 帧 @%gfps ≈ %.2fs → 输出 %gfps（目标 %d 帧 = %.2fs）"
          % (len(frames), fps, len(frames) / max(1.0, fps), out_fps, _target,
             _target / max(1.0, float(out_fps))), flush=True)
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
    # P13：顺手做一次人脸检测并随资产上报 —— 对口型要求**每一帧**都能检出人脸，
    # 选镜弹窗靠这个把「无人脸 / 部分帧无人脸」的镜提前标出来（否则要等跑到一半才失败）。
    fr = _face_probe(mp4)
    face = None if fr is None else (fr[0] == fr[1] and fr[1] > 0)
    frames = None if fr is None else "%d/%d" % (fr[0], fr[1])
    if fr is not None:
        print("[comfy] motion 人脸检测：%s 帧可检出（%s）"
              % (frames, "全部帧有人脸" if face else ("无人脸" if fr[0] == 0 else "部分帧无人脸")),
              flush=True)
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": mp4, "mime": "video/mp4", "width": int(w), "height": int(h),
             "duration_ms": pdur, "face_detected": face, "face_frames": frames}]


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
# 人脸服务地址（生成引擎配置 → 服务地址 → 人脸）。空 = 用本机 insightface 子进程（原行为）。
# 填了就走远端 HTTP（见 deploy/face/face_server.py），便于把脸算力集中到新 GPU 机器。
FACE_URL = os.environ.get("WEAVEORA_FACE_URL", "").rstrip("/")

# --------------------------------------------------------------------------- #
# 对口型「底片体检」（B）：底片（静帧/片段）本身就不适合做口型时，**宁可拒绝也不出坏画面**。
#
# 背景（2026-09-14 《那宝玉恍恍惚惚》实测，用户反馈「配口型时画面被破坏」）：
#   第 5 镜 action「…抓住宝玉将他拖下溪去，**宝玉失声惊叫**」、正词里写着
#   `his mouth open in a **terrified scream**` —— 底片（motion 片段）里嘴本来就大张，
#   LatentSync 要先把嘴「合上」再按音频重开 → 嘴部掩码区大幅形变 = 画面被破坏。
#
# ★ 阈值是按「真实素材」量的，不是拍的（同项目实测 mouth_open）：
#     正常/平静脸：0.44~0.66（第1镜 0.53、第4镜 0.66、定妆照 0.44、新闭嘴近景 0.41）
#     略开（可接受）：0.59~0.78（第6镜：宝玉喊叫但镜头是「近景+被安抚」，实际没大张）
#     真·大张嘴：1.23（第5镜 静帧 1.232 / 片段 1.238 —— 就是「画面被破坏」那一镜）
#   所以 WARN=0.90 / MAX=1.05：平静脸绝不误伤，只拦真正张口喊叫的底片。
#   别再照「0.3/0.5」这类拍的阈值 —— 那会把所有正常脸全判成「嘴大张」（实测过）。
# 两道硬门禁 + 两道软提醒：
#   ① 嘴张太大（张开度 ≥ LIPSYNC_MOUTH_MAX）→ 拒绝，提示换「嘴部自然的静帧」当底片；
#   ② 脸极小（宽 < LIPSYNC_FACE_MIN_PX）→ 拒绝（跑了也没意义：脸都糊了）；
#   ③ 嘴偏大（≥ WARN）/ 脸偏小（< LIPSYNC_FACE_WARN_PX，但 ≥ MIN_PX）→ 只提醒，不拦；
# 指标来自人脸服务 / 本机 insightface（106 点）；**拿不到指标就跳过**（绝不误拦）。
# 逃生门：payload.lipsyncForce=true 或 env WEAVEORA_LIPSYNC_FORCE=1（用户明确要硬跑）。
# --------------------------------------------------------------------------- #
LIPSYNC_FACE_MIN_RATIO = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_MIN_RATIO", "0.015"))
# 人脸像素宽度（比「占画面占比」稳：同机位 2560x1440 静帧的占比比 1280x720 片段小 4 倍）。
# 注意两个阈值的分工：远小于 MIN_PX = 硬拒（脸都糊了）；MIN_PX~WARN_PX 之间 = 只提醒
# （实测第 5 镜 1280x704 宽景的脸宽 95.7px —— 这种合法宽景不应该被“脸小”一刀切拦掉）。
LIPSYNC_FACE_MIN_PX = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_MIN_PX", "64"))
LIPSYNC_FACE_WARN_PX = float(os.environ.get("WEAVEORA_LIPSYNC_FACE_WARN_PX", "96"))
# 嘴张开度软提醒阈值（实测平静脸 0.44~0.66，真·大张 1.23 —— 见上面的量化表）
LIPSYNC_MOUTH_WARN = float(os.environ.get("WEAVEORA_LIPSYNC_MOUTH_WARN", "0.90"))
LIPSYNC_MOUTH_MAX = float(os.environ.get("WEAVEORA_LIPSYNC_MOUTH_MAX", "1.05"))
LIPSYNC_FORCE = os.environ.get("WEAVEORA_LIPSYNC_FORCE", "").strip().lower() in ("1", "true", "yes", "on")
# 极端表情（惊恐/喊叫）镜头的嘴部驱动强度：工作流写死 1.5 → 高危镜降到**节点允许的下限 1.0**
# （LatentSyncNode 的 lips_expression = min 1.0 / max 3.0 / default 1.5；实测给它 0.8 会被
#   ComfyUI 直接判 prompt_outputs_failed_validation → 任务秒失败。所以这里默认 1.0，并且
#   注入前一律按节点 schema 夹一次区间，env 写错也不会再把任务打挂）
LIPSYNC_EXPRESSION_RISK = float(os.environ.get("WEAVEORA_LIPSYNC_EXPRESSION_RISK", "1.0"))


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


# 人脸预检（子进程，**CPU 检测器**）：抽 6 帧看有没有脸。
#
# 为什么必须用 CPU 版：这个检查要在 ComfyUI 刚跑完/还没跑的时候执行，而 ComfyUI 会把模型
# 缓存在显存里（实测残留 ~5GB/8GB）；再起一个 CUDA 上下文（torch + ORT）很容易把 8GiB 卡打爆。
# 6 帧检测在 CPU 上只多花几秒，但完全不吃显存。
_FACE_CHECK = r'''
import os, sys, json, cv2, numpy as np
# 注：这里的常量**不能**写裸名 LATENTSYNC_DIR —— 本脚本是交给 `python -c` 的子进程，
# 父进程的模块变量在子进程里并不存在（写裸名会 NameError → 子进程崩 → 父进程只看到
# “预检无结果，不拦” → 门禁静默失效）。所以只能读 env + 默认值。
node = (os.environ.get("WEAVEORA_LATENTSYNC_DIR") or r"D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper")
sys.path.insert(0, node)
AUX = os.path.join(node, "checkpoints", "auxiliary")
tgt_path = sys.argv[2] if len(sys.argv) > 2 else ""
try:
    from insightface.app import FaceAnalysis
    # landmark_2d_106：底片体检用（脸太小 / 嘴张太大）。没有这个模型时 insightface 会报错，
    # 外层 except 会打印 SKIP → 调用方跳过门禁（而不是误拦）。
    mods = ["detection", "landmark_2d_106"] + (["recognition"] if tgt_path else [])
    app = FaceAnalysis(allowed_modules=mods, root=AUX, providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(512, 512))
except Exception as e:
    print("SKIP:init:" + str(e)[:160]); sys.exit(0)

def mouth_ratio(face):
    """嘴部张开度 ≈ (下唇均y − 上唇均y) / 嘴宽；判不出返 None。

    与 deploy/face/face_server.py 里的 _mouth_open_ratio 同构（子进程无法 import 主进程函数）。
    2d106 点序号可用 WEAVEORA_LIPSYNC_MOUTH_IDX 覆盖；过不了合理性校验就跳过。
    """
    try:
        lm = getattr(face, "landmark_2d_106", None)
        if lm is None:
            return None
        pts = np.asarray(lm, dtype=np.float32)
        if pts.ndim != 2 or pts.shape[0] < 100:
            return None
        spec = (os.environ.get("WEAVEORA_LIPSYNC_MOUTH_IDX") or "52-71").strip()
        sep = "-" if "-" in spec else ":"
        try:
            a, b = [int(x) for x in spec.split(sep, 1)]
        except Exception:
            a, b = 87, 105
        if not (0 <= a < b <= 106):
            a, b = 87, 105
        mouth = pts[a:b + 1]
        bx0, by0, bx1, by1 = [float(v) for v in face.bbox[:4]]
        fw, fh = bx1 - bx0, by1 - by0
        if fw <= 0 or fh <= 0 or mouth.shape[0] < 6:
            return None
        mx, my = mouth[:, 0], mouth[:, 1]
        mw = float(mx.max() - mx.min())
        if mw <= 0 or not (0.15 <= mw / fw <= 0.75):
            return None
        if abs(float(mx.mean()) - (bx0 + fw / 2.0)) > 0.35 * fw:
            return None
        if float(my.mean()) < by0 + 0.5 * fh:
            return None
        # 张开度 = 嘴部**中央区域**的上下唇最大间距 / 嘴宽。
        # 不用「按中位数分组再取均值」：那会把极值平均掉（实测真值 0.5 只测出 0.237 → 漏判）。
        cx = float(mx.mean())
        cen = my[np.abs(mx - cx) <= 0.25 * mw]
        if cen.size < 4:
            return None
        return float(cen.max() - cen.min()) / mw
    except Exception:
        return None

def face_area_ratio(face, frame):
    try:
        bx0, by0, bx1, by1 = [float(v) for v in face.bbox[:4]]
        h, w = frame.shape[0], frame.shape[1]
        if w <= 0 or h <= 0:
            return None
        return max(0.0, (bx1 - bx0) * (by1 - by0)) / float(w * h)
    except Exception:
        return None

tgt = None
if tgt_path:
    try:
        tgt = np.asarray(json.load(open(tgt_path, encoding="utf-8")), dtype=np.float32).reshape(-1)
        tgt = tgt / (float(np.linalg.norm(tgt)) or 1.0)
    except Exception:
        tgt = None
cap = cv2.VideoCapture(sys.argv[1])
if not cap.isOpened():
    print("SKIP:decode"); sys.exit(0)
n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
idx = sorted({int(i * (n - 1) / 5) for i in range(6)}) if n > 1 else [0]
hits = total = 0
best = -1.0
face_ratio = None
face_px = None
mouth_open = None
for i in idx:
    cap.set(cv2.CAP_PROP_POS_FRAMES, i)
    ok, fr = cap.read()
    if not ok:
        continue
    total += 1
    try:
        faces = app.get(fr)
    except Exception:
        faces = []
    if len(faces) > 0:
        hits += 1
    # 「底片体检」指标：取所有帧/所有脸的**最坏**值（最坏情况才是要拦的那个）
    for f in faces:
        r = face_area_ratio(f, fr)
        if r is not None:
            face_ratio = r if face_ratio is None else max(face_ratio, r)
        try:
            wpx = float(f.bbox[2]) - float(f.bbox[0])
            face_px = wpx if face_px is None else max(face_px, wpx)
        except Exception:
            pass
        mo = mouth_ratio(f)
        if mo is not None:
            mouth_open = mo if mouth_open is None else max(mouth_open, mo)
    if tgt is not None:
        for f in faces:
            emb = getattr(f, "normed_embedding", None)
            if emb is None:
                continue
            v = np.asarray(emb, dtype=np.float32)
            v = v / (float(np.linalg.norm(v)) or 1.0)
            s = float(np.dot(v, tgt))
            if s > best:
                best = s
cap.release()
print("RESULT:%d/%d/%s/%s/%s/%s" % (
    hits, total, ("%.4f" % best) if tgt is not None else "",
    ("%.5f" % face_ratio) if face_ratio is not None else "",
    ("%.4f" % mouth_open) if mouth_open is not None else "",
    ("%.1f" % face_px) if face_px is not None else ""))
'''


NO_FACE_MSG = ("该镜检测不到人脸（可能是远景/背影/空镜）——对口型只对正脸/侧脸可见的镜头有意义；"
               "请换一镜，或在导演里把该镜改成对话近景后重生成画面")
# 锁人时「目标人脸」的最低余弦相似度（低于它就不认，宁可不贴也不要贴错人）
TARGET_MIN_SIM = 0.28

# 预检失败原因（给调用方拼错误文案；单线程内单次使用，无需加锁）
_face_reason = {"msg": NO_FACE_MSG, "warn": ""}

# LatentSync 节点目录（本机人脸检测子进程用；可被「服务地址 → 人脸 → latentsyncDir」覆盖）
LATENTSYNC_DIR = os.environ.get("WEAVEORA_LATENTSYNC_DIR", "").strip()


def _face_probe(video_bytes, target_emb=None):
    """抽 6 帧跑人脸检测。

    返回 (hits, total, best_target_sim, extra)：
      hits/total = 检出人脸的帧数；给了 target_emb 时额外给出「最像目标的相似度」。
      extra = {"face_ratio": 最大脸框面积/画面面积, "mouth_open": 嘴部张开度}，
              取不到则为 None（调用方必须跳过门禁，而不是当成 0）。
    无法判定时返回 None（不拦不传）。
    """
    import subprocess
    import sys as _sys
    import tempfile
    if not video_bytes:
        return None
    # 配了「人脸服务」就走远端（新 GPU 机器集中算脸）；失败自动回退本机
    if FACE_URL:
        try:
            body = {"media_b64": base64.b64encode(video_bytes).decode("ascii"), "suffix": ".mp4"}
            if target_emb:
                body["target_embedding"] = [float(x) for x in target_emb]
            _st, resp = _post_json(FACE_URL + "/face/probe", body, timeout=900)
            hits = int(resp.get("hits") or 0)
            total = int(resp.get("total") or 0)
            best = resp.get("best")
            extra = {
                "face_ratio": (float(resp["face_ratio"]) if resp.get("face_ratio") is not None else None),
                "mouth_open": (float(resp["mouth_open"]) if resp.get("mouth_open") is not None else None),
                "face_px": (float(resp["face_px"]) if resp.get("face_px") is not None else None),
            }
            return hits, total, (float(best) if best is not None else None), extra
        except Exception as e:
            print("[comfy] 远端人脸服务不可用，回退本机：%s" % e, flush=True)
    d = tempfile.mkdtemp(prefix="weaveora_facechk_")
    fp = os.path.join(d, "in.mp4")
    tgt = os.path.join(d, "target.json")
    try:
        with open(fp, "wb") as fh:
            fh.write(video_bytes)
        if target_emb:
            with open(tgt, "w", encoding="utf-8") as fh:
                json.dump(list(target_emb), fh)
        r = subprocess.run([_sys.executable, "-c", _FACE_CHECK, fp, tgt if target_emb else ""],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=900)
        out = (r.stdout or "") + (r.stderr or "")
        for line in out.splitlines():
            if line.startswith("SKIP:"):
                print("[comfy] 人脸预检跳过（%s）" % line[5:], flush=True)
                return None
            if line.startswith("RESULT:"):
                parts = line[7:].split("/")
                hits, total = int(parts[0]), int(parts[1])
                # 无目标时脚本会输出 RESULT:h/t/（末段为空）——直接 float("") 会抛异常，
                # 导致“预检异常→不拦”，静默失去这道拦截
                best = float(parts[2]) if len(parts) > 2 and parts[2].strip() else None
                fr = float(parts[3]) if len(parts) > 3 and parts[3].strip() else None
                mo = float(parts[4]) if len(parts) > 4 and parts[4].strip() else None
                px = float(parts[5]) if len(parts) > 5 and parts[5].strip() else None
                return hits, total, best, {"face_ratio": fr, "mouth_open": mo, "face_px": px}
        print("[comfy] 人脸预检无结果，不拦：%s" % out[:120], flush=True)
        return None
    except Exception as e:
        print("[comfy] 人脸预检异常，不拦：%s" % e, flush=True)
        return None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _face_precheck(video_bytes, where="lipsync", target_emb=None, speaker="", force=False):
    """对口型前置门禁。三道：① 画面里有人脸；② （给了目标特征时）**说话人的脸真在画面里**；
    ③ 「底片体检」：脸是否太小 / 嘴是否张得太大（B）。

    为什么要第二道（2026-09-13 实例）：第 6 镜说话人是「袭人」，但她与「警幻」的定妆照
    几乎同一张脸（相似度 0.71）——锁定她时最高相似度只有 0.18，低于阈值 0.28。
    这时不能硬跑（会去驱动最大脸=另一个人，画面坏掉），也不能报一句误导的
    "Face not detected"，而应该**说清楚原因并让用户去修定妆照**。

    为什么要第三道（2026-09-14 实测，用户反馈「画面被破坏」）：第 5 镜 action
    「…抓住宝玉将他拖下溪去，宝玉失声惊叫」、正词 `his mouth open in a terrified scream`，
    底片（motion 片段）里嘴本来就大张，LatentSync 要先把嘴合上再按音频重开 →
    嘴部掩码区大幅形变。实测 mouth_open：第5镜 1.232/1.238（真·大张），
    而平静脸 0.44~0.66、第6镜 0.59/0.78 —— 阈值就按这两档分的（见常量区量化表）。
    底片本身不合格时应**提前拒绝并告诉他怎么换**，而不是烧十几分钟 GPU 出一段坏画面。

    逃生门：force=True（payload.lipsyncForce）或 env WEAVEORA_LIPSYNC_FORCE=1。
    """
    _face_reason["warn"] = ""
    r = _face_probe(video_bytes, target_emb)
    if r is None:
        return True
    hits, total, best = r[0], r[1], r[2]
    extra = r[3] if len(r) > 3 and isinstance(r[3], dict) else {}
    print("[comfy] 人脸预检 %s：%d/%d 帧可检出%s"
          % (where, hits, total, "，最像说话人的相似度=%.2f" % best if best is not None else ""),
          flush=True)
    if total <= 0:
        return True
    if hits == 0:
        _face_reason["msg"] = NO_FACE_MSG
        return False
    if hits < total:
        _face_reason["msg"] = (
            "该镜有 %d/%d 帧检测不到人脸——对口型（LatentSync）要求**每一帧**都能检出人脸，"
            "跑下去会在中途报 Face not detected；请换镜，或先把该镜画面重新生成"
            % (total - hits, total))
        return False
    if target_emb is not None and best is not None and best < TARGET_MIN_SIM:
        _face_reason["msg"] = (
            "画面里找不到说话人「%s」的人脸（最高的相似度仅 %.2f，阈值 %.2f）。"
            "常见原因：① 说话人不在画面里（画外音）；② 该角色的定妆照与画面形象差异过大；"
            "③ 两个角色的定妆照过于相似（实测「袭人」与「警幻」定妆照相似度 0.71，同一张脸），"
            "此时按脸认人本身就不可能。请先核对「%s」的定妆照（导入图）选对了没有。"
            % (speaker or "?", best, TARGET_MIN_SIM, speaker or "?"))
        return False
    return _base_health(extra, where, force, speaker)


def _base_health(extra, where, force=False, speaker=""):
    """底片体检（B）：嘴大张 / 脸极小 → 拒绝；嘴偏大 / 脸偏小 → 只提醒。拿不到指标就放行。

    为什么嘴优先于脸：① 嘴大张是「画面被破坏」的直接原因（第 5 镜实测）；② 合法宽景
    （脸宽 ~96px）不应该被“脸小”一刀切拦掉，坏画面才是真损失。
    拒绝时把**所有命中的原因**一起说出来，别让用户改完一条又撞下一条。
    """
    if not isinstance(extra, dict):
        return True
    fr = extra.get("face_ratio")
    px = extra.get("face_px")
    mo = extra.get("mouth_open")
    print("[comfy] 底片体检 %s：脸占比=%s，脸宽=%s，嘴张开度=%s%s"
          % (where, ("%.3f%%" % (fr * 100)) if fr is not None else "n/a",
             ("%.0fpx" % px) if px is not None else "n/a",
             ("%.3f" % mo) if mo is not None else "n/a",
             "（已开启强制，只记录不拦）" if force else ""), flush=True)
    if force:
        return True
    # 「脸太小」优先用像素宽（不受抽帧分辨率影响）；没有 px 才退到占画面比
    tiny = (px is not None and px < LIPSYNC_FACE_MIN_PX) \
        or (px is None and fr is not None and fr < LIPSYNC_FACE_MIN_RATIO)
    small = px is not None and LIPSYNC_FACE_MIN_PX <= px < LIPSYNC_FACE_WARN_PX
    mouth_bad = mo is not None and mo >= LIPSYNC_MOUTH_MAX
    mouth_warn = mo is not None and LIPSYNC_MOUTH_WARN <= mo < LIPSYNC_MOUTH_MAX
    if mouth_bad or tiny:
        parts = []
        if mouth_bad:
            parts.append(
                "**嘴部大张**（张开度 %.2f，阈值 %.2f）%s：LatentSync 要把大张的嘴先「合上」再按配音"
                "重开，嘴部区域会大幅形变 —— 实测这就是「画面被破坏」的主因"
                % (mo, LIPSYNC_MOUTH_MAX, "（说话人：%s）" % speaker if speaker else ""))
        if tiny:
            parts.append(
                "**人脸太小**（%s）：对口型对远景/小人物没有可见效果"
                % (("脸宽仅 %.0fpx，阈值 %.0fpx" % (px, LIPSYNC_FACE_MIN_PX)) if px is not None
                   else ("最大脸只占画面 %.2f%%，阈值 %.2f%%" % (fr * 100, LIPSYNC_FACE_MIN_RATIO * 100))))
        _face_reason["msg"] = (
            "该镜的**底片**不适合跑对口型：\n- %s\n\n请任选其一：\n"
            "① 换成该镜「嘴部自然（闭合/微张）」的**静帧关键帧**当底片（选镜弹窗 → 底片＝静帧）；\n"
            "② 如该镜本就是喊叫/惊恐，建议改成旁白/画外音或侧脸（导演层「分镜规避」）；\n"
            "③ 确实要试，打开「强制」后重跑（不保证画面完好）。"
            % "\n- ".join(parts))
        return False
    warns = []
    if mouth_warn:
        warns.append("底片嘴部偏大（%.2f）——对口型后嘴部可能变形" % mo)
    if small:
        warns.append("底片人脸偏小（脸宽 %.0fpx，建议 ≥ %.0fpx）——口型会偏糊" % (px, LIPSYNC_FACE_WARN_PX))
    if warns:
        _face_reason["warn"] = "；".join(warns) + "（选镜弹窗 → 底片＝静帧，或挑近景镜）"
    return True


# 预检失败原因（给调用方拼错误文案；线程内单次使用，无需加锁）



# 参考图人脸特征提取（子进程，**CPU**）：用于「锁人」——多人同框时只驱动说话人那张脸。
# 用 CPU 同样是为了不占显存（ComfyUI 还缓存着模型）。
_EMBED = r'''
import os, sys, json, cv2, numpy as np
# 注：不能写裸名 LATENTSYNC_DIR（子进程里不存在 → NameError → 静默回退最大脸）；只读 env + 默认值。
node = (os.environ.get("WEAVEORA_LATENTSYNC_DIR") or r"D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper")
sys.path.insert(0, node)
AUX = os.path.join(node, "checkpoints", "auxiliary")
try:
    from insightface.app import FaceAnalysis
    app = FaceAnalysis(allowed_modules=["detection", "recognition"], root=AUX,
                       providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(512, 512))
except Exception as e:
    print("SKIP:init:" + str(e)[:160]); sys.exit(0)
img = cv2.imdecode(np.fromfile(sys.argv[1], dtype=np.uint8), cv2.IMREAD_COLOR)
if img is None:
    print("SKIP:decode"); sys.exit(0)
faces = app.get(img)
if not faces:
    print("SKIP:noface"); sys.exit(0)
f = max(faces, key=lambda x: (x.bbox[2]-x.bbox[0])*(x.bbox[3]-x.bbox[1]))
print("EMB:" + json.dumps([float(v) for v in f.normed_embedding]))
'''


def _embed_reference(img_bytes):
    """取参考图（定妆照）里最大脸的 512 维特征；拿不到返回 None（调用方退回旧行为）。"""
    import subprocess
    import sys as _sys
    import tempfile
    if not img_bytes:
        return None
    if FACE_URL:
        try:
            _st, resp = _post_json(FACE_URL + "/face/embed",
                                   {"image_b64": base64.b64encode(img_bytes).decode("ascii"),
                                    "suffix": ".png"}, timeout=900)
            emb = resp.get("embedding")
            return [float(x) for x in emb] if emb else None
        except Exception as e:
            print("[comfy] 远端人脸服务不可用，回退本机：%s" % e, flush=True)
    d = tempfile.mkdtemp(prefix="weaveora_emb_")
    fp = os.path.join(d, "ref.png")
    try:
        with open(fp, "wb") as fh:
            fh.write(img_bytes)
        r = subprocess.run([_sys.executable, "-c", _EMBED, fp],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=900)
        out = (r.stdout or "") + (r.stderr or "")
        for line in out.splitlines():
            if line.startswith("SKIP:"):
                print("[comfy] 参考图人脸特征提取跳过（%s）" % line[5:], flush=True)
                return None
            if line.startswith("EMB:"):
                import json as _json
                return _json.loads(line[4:])
        print("[comfy] 参考图人脸特征无结果：%s" % out[:120], flush=True)
        return None
    except Exception as e:
        print("[comfy] 参考图人脸特征异常：%s" % e, flush=True)
        return None
    finally:
        import shutil as _sh
        _sh.rmtree(d, ignore_errors=True)


def _set_node_scalar(graph, node_class, key, value):
    """按 class_type 给节点写一个标量输入（返回命中数）。用于注入人脸特征文件路径等。"""
    hit = 0
    for node in (graph or {}).values():
        if isinstance(node, dict) and node.get("class_type") == node_class:
            node.setdefault("inputs", {})[key] = value
            hit += 1
    return hit


def _ffmpeg_exe():
    """本机 ffmpeg 路径。

    优先级：WEAVEORA_FFMPEG 环境变量 > imageio_ffmpeg 自带的 > PATH 里的 ffmpeg。
    加环境变量覆盖是因为 imageio-ffmpeg 在 Python 3.6 上只能装到 0.4.9，其自带
    ffmpeg 仅 4.2.2，不支持 `-fps_mode`（需 >= 4.3）。
    """
    p = os.environ.get("WEAVEORA_FFMPEG", "").strip()
    if p and os.path.exists(p):
        return p
    try:
        import imageio_ffmpeg as _iif
        return _iif.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def _run_ff(args, timeout=900):
    import subprocess
    r = subprocess.run([_ffmpeg_exe(), "-y", "-loglevel", "error"] + args,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=timeout)
    if r.returncode != 0:
        raise ComfyError("ffmpeg 失败: %s" % (r.stderr or "")[-300:])
    return r


def _cut_frames(video_bytes, a, b, tmp, tag):
    """按**帧号**切 [a, b)（帧精确，用于按段驱动）。

    为什么按帧号而不是按秒：分段驱动要把每段的结果拼回原位，时间戳一旦有偏差
    就会出现音视频错位。`select=between(n,a,b-1)` + `setpts=N/FRAME_RATE/TB` 让
    切出来的片从第 0 帧重新计时、且帧数与原视频一致。
    """
    ip = os.path.join(tmp, "%s_in.mp4" % tag)
    op = os.path.join(tmp, "%s_out.mp4" % tag)
    with open(ip, "wb") as fh:
        fh.write(video_bytes)
    _run_ff(["-i", ip, "-vf",
             "select='between(n\\,%d\\,%d)',setpts=N/FRAME_RATE/TB" % (a, b - 1),
             "-an", "-fps_mode", "passthrough", "-c:v", "libx264", "-pix_fmt", "yuv420p",
             "-crf", "18", op])
    with open(op, "rb") as fh:
        return fh.read()


def _mux_audio(video_bytes, audio_bytes, tmp):
    """给拼好的画面重新接上**完整配音**（音轨不动，保证与原来同一时间轴）。"""
    vp = os.path.join(tmp, "mux_v.mp4")
    ap = os.path.join(tmp, "mux_a.wav")
    op = os.path.join(tmp, "mux_out.mp4")
    with open(vp, "wb") as fh:
        fh.write(video_bytes)
    with open(ap, "wb") as fh:
        fh.write(audio_bytes)
    _run_ff(["-i", vp, "-i", ap, "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
             "-map", "0:v:0", "-map", "1:a:0", op])
    with open(op, "rb") as fh:
        return fh.read()


def _frame_count(video_bytes, tmp, tag="fc"):
    """数一段视频的帧数（用 -count_frames 太重，改用 python 侧 cv2）。"""
    import tempfile as _tf
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    n = int(cap.get(_cv.CAP_PROP_FRAME_COUNT)) if cap.isOpened() else 0
    cap.release()
    return n


def _decode_frames(video_bytes, tmp, tag="dec"):
    """把一段视频解码成帧列表（BGR numpy）。"""
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    frames = []
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        frames.append(fr)
    cap.release()
    return frames


def _encode_frames(frames, fps, tmp, tag="enc"):
    """帧列表 → mp4（复用 _encode_frames_mp4：写 PNG 序列再交 ffmpeg）。"""
    import cv2 as _cv
    pngs = []
    for fr in frames:
        ok, buf = _cv.imencode(".png", fr)
        if ok:
            pngs.append(buf.tobytes())
    if not pngs:
        raise ComfyError("编码失败：没有帧")
    out_dir = os.path.join(tmp, "enc_%s" % tag)
    os.makedirs(out_dir, exist_ok=True)
    return _encode_frames_mp4(pngs, max(1, int(fps)), out_dir)


def _source_fps(video_bytes, tmp, tag="fps"):
    """源片帧率（分段驱动要靠它把秒换算成帧号）。"""
    import cv2 as _cv
    p = os.path.join(tmp, "%s.mp4" % tag)
    with open(p, "wb") as fh:
        fh.write(video_bytes)
    cap = _cv.VideoCapture(p)
    fps = cap.get(_cv.CAP_PROP_FPS) if cap.isOpened() else 0
    cap.release()
    return float(fps or 25.0) or 25.0


def _splice(source_bytes, seg_results, fps, tmp):
    """把各段的处理结果**按原时间轴替换回原帧序列**。

    为什么不用「切段→拼接」：LatentSync 是按**音频长度**出帧的，段内产出的帧数
    与切出来的帧数可能差一两帧；一旦用拼接，后面的每一段都会整体前/后移，音画就溧了。
    替换回原位则**总帧数与帧位置完全不变**，音轨也就能原封不动接上。
    帧数不足时重复末帧（宁多不少），多了就截掉。
    """
    frames = _decode_frames(source_bytes, tmp, "src")
    if not frames:
        raise ComfyError("源片解码失败（0 帧）")
    for a, b, pf in seg_results:
        if not pf:
            continue
        if a >= len(frames):
            continue
        # 只把**实际产出**的帧放回 [a, a+len(pf))，其余保持原帧：
        # 不外推、不重复末帧 —— 因为 LatentSync 的产出帧数与切出来的帧数本来就可能不等
        # （产出长度由**音频**决定，见 loop_video），重复外推会把后面的画面“冻结”几帧。
        n = min(len(pf), max(0, b - a), len(frames) - a)
        if n > 0:
            frames[a:a + n] = pf[:n]
    return _encode_frames(frames, fps, tmp, "spliced")


def _post_json(url, obj, timeout=900):
    """POST JSON → (status, dict)。"""
    import urllib.request as _ur
    req = _ur.Request(url, data=json.dumps(obj).encode("utf-8"),
                      headers={"Content-Type": "application/json"})
    with _ur.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def apply_services(svc):
    """按任务下发（claim 响应）的「服务地址」覆盖本进程运行期配置。

    为什么这样做：这些地址原先只能靠 worker 机器的环境变量决定，换 GPU 服务器就得改脚本、
    重启 worker。现在用户可在「生成引擎配置 → 服务地址」里随时改，随任务下发即刻生效。
    空值/未配置一律**不覆盖**（保持环境变量默认，向后兼容）。
    """
    global COMFY, LIPSYNC_WORKFLOW, LIPSYNC_TIMEOUT, LIPSYNC_FPS, FACE_URL, LATENTSYNC_DIR, MOTION_OVERRIDES
    if not isinstance(svc, dict):
        return
    def g(*path):
        cur = svc
        for k in path:
            if not isinstance(cur, dict):
                return None
            cur = cur.get(k)
        return cur
    # 引擎配置下发的 motion 档位（preset/steps/lora_*/cfg_* …）：收白名单键，switch 归一成 switch_step
    motion = g("motion")
    if isinstance(motion, dict):
        clean = {}
        for k, v in motion.items():
            if v is None:
                continue
            k2 = _snake_key(k)          # camelCase 兼容（前端两种写法都可能出现）
            if k2 not in MOTION_SERVICE_KEYS:
                continue
            clean["switch_step" if k2 == "switch" else k2] = v
        if clean:
            MOTION_OVERRIDES = clean
            print("[comfy] motion 档位（引擎配置下发）：%s" % clean, flush=True)
    comfy = g("lipsync", "comfyUrl")
    if isinstance(comfy, str) and comfy.strip():
        COMFY = comfy.strip().rstrip("/")
    wf = g("lipsync", "workflow")
    if isinstance(wf, str) and wf.strip():
        LIPSYNC_WORKFLOW = wf.strip()
    t = g("lipsync", "timeout")
    if isinstance(t, (int, float)) and t > 0:
        LIPSYNC_TIMEOUT = float(t)
    f = g("lipsync", "fps")
    if isinstance(f, (int, float)):
        LIPSYNC_FPS = int(f)
    face = g("face", "url")
    if isinstance(face, str) and face.strip():
        FACE_URL = face.strip().rstrip("/")
    node = g("face", "latentsyncDir")
    if isinstance(node, str) and node.strip():
        LATENTSYNC_DIR = node.strip()
    print("[comfy] 服务地址：comfy=%s lipsync_wf=%s timeout=%s fps=%s face=%s"
          % (COMFY, LIPSYNC_WORKFLOW or "(env 默认)", LIPSYNC_TIMEOUT, LIPSYNC_FPS,
             FACE_URL or "(本机)"), flush=True)


def _clamp_node_scalar(class_type, key, value):
    """按节点 schema 把标量夹进 min/max（越界会被 ComfyUI 直接 400）。

    为什么必须夹：实测 LatentSyncNode 的 `lips_expression` 是 min 1.0 / max 3.0，
    而「降低嘴部形变」的自然想法是给 0.8 → ComfyUI 报
    `prompt_outputs_failed_validation: Value 0.8 smaller than min of 1.0`，整个对口型任务秒失败。
    拿不到 schema 时原样返回（不因为探测失败而拦任务）。
    """
    lo = hi = None
    try:
        info = _node_info(class_type) or {}
        node = next(iter(info.values()), None) or {}
        spec = ((node.get("input") or {}).get("required") or {}).get(key)
        if isinstance(spec, list) and len(spec) > 1 and isinstance(spec[1], dict):
            lo, hi = spec[1].get("min"), spec[1].get("max")
    except Exception:
        lo = hi = None
    try:
        v = value
        if lo is not None and float(v) < float(lo):
            v = float(lo)
        if hi is not None and float(v) > float(hi):
            v = float(hi)
        return v, lo, hi
    except Exception:
        return value, lo, hi


def _run_lipsync_graph(client_id, vbytes, abytes, emb_path, on_tick=None, lips_expression=None):
    """跑一次对口型工作流，返回产物 mp4 bytes（整镜 / 单段共用）。

    lips_expression：嘴部驱动强度（工作流默认 1.5）。极端表情（惊恐/喊叫）的底片降到 0.8，
    减少「先合上再重开」造成的嘴部形变；None = 不改工作流原值。
    """
    import uuid as _uuid
    if not LIPSYNC_WORKFLOW or not os.path.exists(LIPSYNC_WORKFLOW):
        raise ComfyError("对口型工作流不存在：检查 WEAVEORA_LIPSYNC_WORKFLOW=%s" % LIPSYNC_WORKFLOW)
    _tok = _uuid.uuid4().hex[:8]
    vname = _upload_any(vbytes, "weaveora_lipsync_%s_in.mp4" % _tok, "video/mp4")
    if not vname:
        raise ComfyError("上传画面失败")
    aname = _upload_any(abytes, "weaveora_lipsync_%s_voice.wav" % _tok, "audio/wav")
    if not aname:
        raise ComfyError("上传配音失败")
    with open(LIPSYNC_WORKFLOW, "r", encoding="utf-8") as fh:
        graph = json.load(fh)
    vh = _set_node_input(graph, LIPSYNC_VIDEO_TITLE, vname, LIPSYNC_VIDEO_INPUT)
    ah = _set_node_input(graph, LIPSYNC_AUDIO_TITLE, aname, LIPSYNC_AUDIO_INPUT, want_video=False)
    if not vh or not ah:
        raise ComfyError(
            "工作流里没找到标题为「%s」/「%s」的节点：请在 ComfyUI 里把承载视频/音频的节点标题改成这两个值"
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
    if emb_path:
        _n = _set_node_scalar(graph, LIPSYNC_NODE_CLASS, "target_embedding_path", emb_path)
        print("[comfy] 已注入 target_embedding_path（命中 %d 个 %s）" % (_n, LIPSYNC_NODE_CLASS), flush=True)
    if lips_expression is not None:
        _val, _lo, _hi = _clamp_node_scalar(LIPSYNC_NODE_CLASS, "lips_expression", float(lips_expression))
        _n = _set_node_scalar(graph, LIPSYNC_NODE_CLASS, "lips_expression", _val)
        print("[comfy] 嘴部驱动强度 lips_expression=%.2f（请求 %.2f；节点允许 %s~%s；命中 %d 个 %s）"
              % (_val, float(lips_expression), _lo, _hi, _n, LIPSYNC_NODE_CLASS), flush=True)
    _mode, _nodes = _apply_fps_policy(graph)
    print("[comfy] 对口型 fps 策略：%s（节点：%s）" % (_mode, ",".join(str(x) for x in _nodes)), flush=True)
    # 裸节点图 → /prompt 要的是 {"prompt": 图, "client_id": ...}
    pid = _post_prompt({"prompt": graph, "client_id": client_id}, client_id)
    try:
        rec = _poll_history(client_id, pid, timeout=LIPSYNC_TIMEOUT, on_tick=on_tick)
    except ComfyError as e:
        try:
            _comfy("POST", "/interrupt", payload={}, timeout=30)
        except Exception:
            pass
        if "Face not detected" in str(e):
            raise ComfyError(NO_FACE_MSG)
        raise ComfyError("对口型推理失败：%s" % e)
    outs = _download_outputs(rec, prefix)
    if not outs:
        outs = _download_outputs(rec, "weaveora")
    if not outs:
        raise ComfyError("对口型无输出视频（prefix=%s）" % prefix)
    return outs[0]["bytes"]


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
        r = subprocess.run([_ffmpeg_exe(), "-i", p],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=180)
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
    """对口型（lipsync 任务）：画面 + 配音 → 嘴型对齐的视频。返回 [{bytes, mime}]。

    两种模式：
      · **单人镜**：整镜跑一次，并把脸锁在该说话人上（防止逐帧取最大脸中途换人）；
      · **多人镜（按段驱动）**：按方案的台词时间窗分段，每段只驱动**该段说话人**的脸，
        再把处理后的帧按原时间轴拼回去 —— LatentSync 每帧只能驱动一张脸，不分段就必然配错人。
    """
    import uuid as _uuid
    import tempfile
    if not LIPSYNC_WORKFLOW or not os.path.exists(LIPSYNC_WORKFLOW):
        raise ComfyError("未配置对口型工作流：见 docs/lipsync-setup.md（WEAVEORA_LIPSYNC_WORKFLOW）")
    # 先确认 GPU 机上的节点补丁版本/能力（缺就直接失败，不静默降级成「最大脸」）
    _require_node_features()
    if os.environ.get("WEAVEORA_LIPSYNC_DEBUG_BOX", "") == "1":
        print("[comfy] 已开启对口型调试画框（产物上会标出锁定的脸与是否驱动）", flush=True)
    vkey = (payload.get("videoKey") or "").strip()
    vkeys = [k for k in (payload.get("voiceKeys") or []) if k]
    if not vkey or not vkeys:
        raise ComfyError("对口型缺少输入：videoKey/voiceKeys")

    if progress_fn:
        progress_fn(15, "upload")
    # fetch_reference_bytes 返回 (bytes, content_type)
    vdata, vctype = fetch_reference_bytes(vkey)
    adata = _concat_voice(vkeys)          # 完整配音（最终音轨用它，音画同一时间轴）
    still_mode = (bool(payload.get("videoIsStill")) or bool(payload.get("isStill"))
                  or str(vctype or "").startswith("image"))
    if still_mode:
        vdata = _still_to_video(vdata, adata, payload.get("duration_sec"))
        print("[comfy] lipsync 底片为静帧 → 已转成与配音等长的 mp4（等比缩放，不做 pad/裁切）", flush=True)

    speakers = payload.get("speakers") if isinstance(payload.get("speakers"), dict) else {}
    segs = payload.get("segments") if isinstance(payload.get("segments"), list) else []
    speaker_count = int(payload.get("speakerCount") or len(speakers) or 0)
    shot_no = payload.get("shot_no") or "?"

    # 每个说话人的人脸特征（定妆照）—— 锁人用；拿不到就退回「最大脸」
    embs = {}
    for name, pkey in speakers.items():
        try:
            pb, _ = fetch_reference_bytes(pkey)
            e = _embed_reference(pb)
            if e:
                embs[name] = e
                print("[comfy] 已提取说话人「%s」的人脸特征（%d 维）" % (name, len(e)), flush=True)
        except Exception as e:
            print("[comfy] 说话人「%s」特征提取失败：%s" % (name, e), flush=True)

    face_hints = payload.get("faceHints") if isinstance(payload.get("faceHints"), dict) else {}

    def _spec_path_of(name):
        """锁定规格文件：**点选位置优先**，其次定妆照人脸特征（见 face_detector 两种模式）。

        点选（faceHints）不受画风影响、完全确定；识别式在 480p/AI 古风这类素材上
        区分度会崩（实测同一人只有 0.2 上下且互相混淆），所以有坐标就优先用坐标。
        """
        spec = {}
        h = face_hints.get(name)
        if isinstance(h, dict):
            try:
                spec["point"] = [float(h["x"]), float(h["y"])]
            except (KeyError, TypeError, ValueError):
                spec.pop("point", None)
        if embs.get(name):
            spec["embedding"] = embs[name]
        if not spec:
            return ""
        # 调试画框：worker 设 WEAVEORA_LIPSYNC_DEBUG_BOX=1 时，产物上会标出「锁定的哪张脸 + 这一帧有没有驱动」
        # （cross-machine 排查用：worker 在 API 机、节点在 GPU 机，看不到节点日志）
        if os.environ.get("WEAVEORA_LIPSYNC_DEBUG_BOX", "") == "1":
            spec["debugBox"] = True
        try:
            # ★ 返回**内联 JSON**（不是文件路径）：worker 与 ComfyUI 可能不在同一台机
            # （worker 在 API 服务器、ComfyUI 在 GPU 服务器），文件路径在节点侧根本不存在。
            # 节点/inference.py 已支持「以 { 开头 = 直接当 JSON 解析」。
            print("[comfy] 说话人「%s」锁定规格：point=%s embedding=%s"
                  % (name, spec.get("point") and [round(v, 3) for v in spec["point"]],
                     bool(spec.get("embedding"))), flush=True)
            return json.dumps(spec, separators=(",", ":"))
        except Exception as ex:
            print("[comfy] 锁定规格序列化失败（退回最大脸）: %s" % ex, flush=True)
            return ""

    # 先预检：画面里有人脸；且（有特征时）说话人的脸真在画面里；以及「底片体检」（B）
    # ★ 但**有「点选人脸」提示时不做识别式预检**：识别在风格化/低分辨率素材上不可信
    #   （实测「袭人」定妆照与「警幻」相似度 0.71＝同一张脸，画面里最高相似度仅 0.21
    #   < 阈值 0.28 → 会把本来能跑的任务直接判失败）。用户点的位置本身就是权威信号，
    #   点选没点到脸的情况交给管线的「就近选脸 + 沿用上一帧」兜底。
    # 注：B 的底片体检（脸太小/嘴大张）**不看这个**——它用的是全部脸的最坏值，与锁谁无关。
    _first = next(iter(embs)) if len(embs) == 1 else ""
    _emb_pre = None if (_first and face_hints.get(_first)) else embs.get(_first)
    _force = bool(payload.get("lipsyncForce")) or LIPSYNC_FORCE
    if not _face_precheck(vdata, where="第%s镜" % shot_no,
                          target_emb=_emb_pre, speaker=_first, force=_force):
        raise ComfyError(_face_reason["msg"])
    _warn = _face_reason.get("warn") or ""
    if _warn:
        print("[comfy] 第%s镜底片体检提醒：%s" % (shot_no, _warn), flush=True)
        if progress_fn:
            progress_fn(18, _warn)
    # C：方案侧标了「极端表情（惊恐/喊叫）」的镜头 → 降低嘴部驱动强度（1.5 → 0.8）
    _lips_expr = None
    if bool(payload.get("expressionRisk")):
        _lips_expr = LIPSYNC_EXPRESSION_RISK
        print("[comfy] 第%s镜标记为极端表情（张口/喊叫）→ lips_expression=%.2f"
              % (shot_no, _lips_expr), flush=True)

    _free_comfy_models()
    _last_min = [-1]

    def _tick(elapsed):
        m = int(elapsed // 60)
        if m == _last_min[0]:
            return
        _last_min[0] = m
        if progress_fn and m > 0:
            progress_fn(40, "lipsync 已运行 %d 分钟" % m)

    def _wav_bytes_seconds(b):
        """一段音频的秒数（优先 wave；拿不到返回 None）。"""
        try:
            import wave as _w
            import io as _io2
            with _w.open(_io2.BytesIO(b), "rb") as w:
                return w.getnframes() / float(w.getframerate() or 1)
        except Exception:
            return None

    tmp = tempfile.mkdtemp(prefix="weaveora_splice_")
    try:
        if speaker_count <= 1 or not segs:
            # ---------- 单人镜：整镜一次 ----------
            _who = _first or (next(iter(speakers)) if speakers else "")
            ep = _spec_path_of(_who)
            print("[comfy] 第%s镜单人模式：说话人=%s，整镜一次" % (shot_no, _who or "?"), flush=True)
            # 预警：LatentSync 的产出帧数由音频决定，音频比画面长时它会「正放+倒放」循环视频凑帧
            # （loop_video）→ 时间轴会异常。本机无法凭空补画面，只能提醒上游保证画面 ≥ 配音。
            _vms = (_probe_video_meta(vdata)[2] or 0) / 1000.0
            _asec = _wav_bytes_seconds(adata) or 0
            if _asec > _vms + 0.35:
                print("[comfy] ⚠ 第%s镜配音 %.2fs 明显长于画面 %.2fs —— LatentSync 会循环凑帧，"
                      "可能出现时间轴异常；建议把该镜画面时长做到 ≥ 配音" % (shot_no, _asec, _vms), flush=True)
            if progress_fn:
                progress_fn(40, "lipsync")
            out = _run_lipsync_graph(client_id, vdata, adata, ep, on_tick=_tick, lips_expression=_lips_expr)
        else:
            # ---------- 多人镜：按段驱动 ----------
            fps = _source_fps(vdata, tmp)
            total = _frame_count(vdata, tmp, "cnt")
            print("[comfy] 第%s镜多人模式：%d 人说话 / %d 段，源片 %d 帧 @%.2ffps"
                  % (shot_no, speaker_count, len(segs), total, fps), flush=True)
            results = []
            # ★ 各段的帧范围按**配音的真实时间轴**平铺，而不是方案里的 at_sec/end_sec：
            #   平台混音时是把各段配音**首尾相接**拼成一条音轨的（行间没有空隙），
            #   而方案的 at_sec/end_sec 只是大致窗口（实测 第1镜 宝玉窗 0–3.2s、
            #   实际配音 3.3s → 按窗口切 96 帧、音频要 99 帧 → 触发 LatentSync 的
            #   loop_video「正放+倒放」凑帧 → 时间轴错乱）。
            #   所以用「累加配音时长」定位每段的起点，并给 +4 帧余量保证「视频帧 ≥ 音频需求」。
            plan = []
            t_cum = 0.0
            for i, s in enumerate(segs):
                who = (s.get("subject") or "").strip()
                vk = (s.get("voiceKey") or "").strip()
                seg_audio = None
                if vk:
                    try:
                        seg_audio, _ = fetch_reference_bytes(vk)
                    except Exception as e:
                        print("[comfy] 第%s段配音获取失败，退回整轨: %s" % (i + 1, e), flush=True)
                dur = _wav_bytes_seconds(seg_audio) if seg_audio else None
                if dur is None:
                    # 拿不到音频时长（极少数）：退回方案窗口
                    seg_audio = seg_audio or adata
                    try:
                        dur = max(0.1, (float(s.get("endMs", 0)) - float(s.get("startMs", 0))) / 1000.0)
                    except (TypeError, ValueError):
                        dur = 0.1
                a = max(0, int(round(t_cum * fps)))
                want = max(1, int(round(dur * fps)) + 4)
                b = min(a + want, total)
                plan.append((i, s, who, seg_audio, a, b, dur))
                t_cum += dur
            if plan:
                print("[comfy] 第%s镜各段（按配音时间轴）：%s"
                      % (shot_no, " / ".join("%s %d-%d帧(%.2fs)" % (q[2] or "?", q[4], q[5], q[6]) for q in plan)),
                      flush=True)
            for (i, s, who, seg_audio, a, b, dur) in plan:
                if b <= a:
                    continue
                seg_video = _cut_frames(vdata, a, b, tmp, "s%d" % i)
                ep = _spec_path_of(who)
                # 该段先确认「这位说话人的脸真在这一段里」—— 比跑到一半失败便宜得多
                # （多人镜里很常见，比如某段是画外音、或某角色只在这一段背对着镜头）
                # 注：走了点选（faceHints）就不做识别式预检 —— 用户点的位置本身就是权威信号，
                # 而识别在风格化素材上不可信；那种情况交给管线的“就近选脸 + 沿用上一帧”。
                if embs.get(who) and not face_hints.get(who):
                    _pr = _face_probe(seg_video, embs[who])
                    if _pr and _pr[1] > 0 and (_pr[0] == 0 or _pr[2] is None or _pr[2] < TARGET_MIN_SIM):
                        raise ComfyError(
                            "第%d段（%s %.1f–%.1fs）里找不到该说话人的脸（检出 %d/%d 帧，"
                            "最像的相似度 %.2f，阈值 %.2f）。常见原因：这段是画外音；或「%s」的"
                            "定妆照与画面差异过大、与其它角色过于相似。\n"
                            "（多人镜按段驱动：每段只驱动该段说话人的脸，找不到就无法对口型）"
                            % (i + 1, who or "?", a / fps, b / fps, _pr[0], _pr[1], (_pr[2] or 0),
                               TARGET_MIN_SIM, who or "?"))
                if progress_fn:
                    progress_fn(40, "lipsync 第%d/%d段（%s）" % (i + 1, len(segs), who or "?"))
                seg_mp4 = _run_lipsync_graph(client_id, seg_video, seg_audio, ep, on_tick=_tick,
                                             lips_expression=_lips_expr)
                pf = _decode_frames(seg_mp4, tmp, "seg%d" % i)
                results.append((a, b, pf))
                cursor = b
                print("[comfy] 第%s段完成：%s 帧 %d-%d（产出 %d 帧）" % (i + 1, who or "?", a, b, len(pf)),
                      flush=True)
            if not results:
                raise ComfyError("按段驱动失败：没有可处理的有效段（检查台词的 at_sec/end_sec）")
            if progress_fn:
                progress_fn(75, "lipsync 拼接画面")
            merged = _splice(vdata, results, fps, tmp)
            out = _mux_audio(merged, adata, tmp)
            print("[comfy] 第%s镜按段驱动完成：%d 段已按原时间轴拼回并接回完整音轨" % (shot_no, len(results)),
                  flush=True)
    finally:
        # 锁定规格已内联进工作流（不再有临时文件），只需清临时目录
        import shutil as _sh
        _sh.rmtree(tmp, ignore_errors=True)

    if progress_fn:
        progress_fn(100, "done")
    w, h, dur = _probe_video_meta(out)
    return [{"bytes": out, "mime": "video/mp4", "width": w, "height": h, "duration_ms": dur}]

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
    """本机 ffmpeg 路径。

    优先级：WEAVEORA_FFMPEG 环境变量 > imageio_ffmpeg 自带的 > PATH 里的 ffmpeg。
    加环境变量覆盖是因为 imageio-ffmpeg 在 Python 3.6 上只能装到 0.4.9，其自带
    ffmpeg 仅 4.2.2，不支持 `-fps_mode`（需 >= 4.3）。
    """
    p = os.environ.get("WEAVEORA_FFMPEG", "").strip()
    if p and os.path.exists(p):
        return p
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


def _still_to_video(img_bytes, audio_bytes, duration_sec=None, fps=25, max_long=1280):
    """关键帧静帧 + 配音 → mp4（口型工作流需要视频轨）。时长以实际配音为准。

    为什么必须做：LatentSync 工作流是 LoadVideo → GetVideoComponents，
    直接把 png 当 mp4 传进去 LoadVideo 会解码失败。

    为什么**不再 pad 成 512x512**（2026-09-14 实测）：原来是 scale=512:512 + pad=512:512，
    于是 2560x1440 的 16:9 静帧被压成**带黑边的方片**，LatentSync 产物也就成了 512x512 方视频
    —— 成片画幅被破坏，而且平白把脸缩小（黑边还占了一半像素）。现在只做**等比缩放**：
    长边 ≤ max_long（默认 1280，与实测能跑通的片段底片 1280x720 同一量级），不 pad 不裁。
    可用 WEAVEORA_LIPSYNC_STILL_MAX 覆盖。
    """
    import subprocess, tempfile
    if not img_bytes:
        raise ComfyError("静帧画面为空，无法生成对口型输入视频")
    try:
        max_long = int(os.environ.get("WEAVEORA_LIPSYNC_STILL_MAX") or max_long)
    except Exception:
        pass
    d = tempfile.mkdtemp(prefix="weaveora_still_")
    ip = os.path.join(d, "in.png")
    ap = os.path.join(d, "a.wav")
    op = os.path.join(d, "out.mp4")
    with open(ip, "wb") as fh:
        fh.write(img_bytes)
    with open(ap, "wb") as fh:
        fh.write(audio_bytes or b"")
    dur = _wav_seconds(ap) or float(duration_sec or 0) or 3.0
    vf = ("scale='if(gt(iw,ih),min(%d,iw),-2)':'if(gt(iw,ih),-2,min(%d,ih))'"
          % (max_long, max_long))
    r = subprocess.run(
        [_ffmpeg_exe(), "-y", "-loglevel", "error", "-loop", "1", "-i", ip, "-t", "%.3f" % dur,
         "-r", str(fps), "-vf", vf, "-pix_fmt", "yuv420p", "-c:v", "libx264", op],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=300)
    if r.returncode != 0 or not os.path.exists(op):
        raise ComfyError("静帧转视频失败: %s" % (r.stderr or "")[-300:])
    _w, _h, _dur = _probe_video_meta(open(op, "rb").read())
    print("[comfy] 静帧 → 视频：%sx%s（长边封顶 %d，等比不 pad）/ %.2fs"
          % (_w, _h, max_long, dur), flush=True)
    with open(op, "rb") as fh:
        return fh.read()
