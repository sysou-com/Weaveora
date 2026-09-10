#!/usr/bin/env python3
"""Weaveora 云引擎适配器（引擎中立；先支持 Replicate，便于无 GPU 的前期测试）。

环境变量：
  WEAVEORA_CLOUD_PROVIDER  replicate（默认）
  WEAVEORA_REPLICATE_TOKEN  Replicate API token
  WEAVEORA_REPLICATE_MODEL  模型 owner/name:version（默认 stability-ai/sdxl 固定版本）
  WEAVEORA_CLOUD_DELAY_MS   相邻云调用间隔（默认 1500ms，避免短时多次调用被限流，
                            复现过镜像短时间多调失败的问题）
  WEAVEORA_CLOUD_RETRIES    创建重试次数（默认 4，429/5xx 指数退避）
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("WEAVEORA_REPLICATE_TOKEN", "")
MODEL = os.environ.get(
    "WEAVEORA_REPLICATE_MODEL",
    "stability-ai/sdxl:39ed52f2a78e934b3ba6e2a89f5b1c712de7dfea535525255b1aa35c5565e08b")
API = "https://api.replicate.com/v1"
DELAY_MS = int(os.environ.get("WEAVEORA_CLOUD_DELAY_MS", "1500"))
RETRIES = int(os.environ.get("WEAVEORA_CLOUD_RETRIES", "4"))

# ---- 云 API 模型默认（生产以用户引擎配置为准；测试模型需显式开启）----
# §11.6 测试口径：仅当 WEAVEORA_REPLICATE_TEST_MODELS=1（或显式 env 指定模型）时生效。
TEST_MODE = os.environ.get("WEAVEORA_REPLICATE_TEST_MODELS", "").lower() in ("1", "true", "yes", "on")
TEST_IMAGE_MODEL = "stability-ai/stable-diffusion:ac732df83cea7fff18b8472768c88ad041fa750ff7682a21affe81863cbe77e4"
TEST_VIDEO_MODEL = "prunaai/p-video"
_env_img = os.environ.get("WEAVEORA_REPLICATE_IMAGE_MODEL")
DEFAULT_IMAGE_MODEL = _env_img if _env_img is not None else (TEST_IMAGE_MODEL if TEST_MODE else "")
_env_vid = os.environ.get("WEAVEORA_REPLICATE_VIDEO_MODEL")
DEFAULT_VIDEO_MODEL = _env_vid if _env_vid is not None else (TEST_VIDEO_MODEL if TEST_MODE else "")
_env_draft = os.environ.get("WEAVEORA_REPLICATE_VIDEO_DRAFT")
VIDEO_DRAFT = (_env_draft.lower() not in ("0", "false", "no")) if _env_draft is not None else TEST_MODE
env_res = os.environ.get("WEAVEORA_REPLICATE_VIDEO_RESOLUTION")
VIDEO_RESOLUTION = env_res if env_res is not None else ("720p" if TEST_MODE else "")
# 尾帧引导参数名（payload.tailKey）：p-video 无末帧参数，留空即忽略；Wan 系可设如 last_frame_image
VIDEO_LAST_FRAME_PARAM = os.environ.get("WEAVEORA_VIDEO_LAST_FRAME_PARAM", "").strip()

TRANSIENT = {429, 500, 502, 503, 504}


class CloudError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _headers():
    if not TOKEN:
        raise CloudError("缺少 WEAVEORA_REPLICATE_TOKEN")
    return {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
            "Prefer": "wait"}


def _post(path, payload, timeout=300):
    req = urllib.request.Request(API + path, data=json.dumps(payload).encode(),
                                 headers=_headers(), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = e.read()[:300].decode(errors="replace")
        raise CloudError("replicate %s -> %s %s" % (path, e.code, body), status=e.code)


def _open_retry(req, timeout=120, attempts=4):
    """网络层瞬断重试（Errno 101 Network unreachable / 111 / 104 / 超时）；HTTPError 直接上抛。"""
    last = None
    for i in range(1, attempts + 1):
        try:
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError:
            raise
        except Exception as e:
            last = e
            if i < attempts:
                wait = min(3 * i, 12)
                print("[cloud] network retry %d/%d after %ss: %s" % (i, attempts, wait, e), flush=True)
                time.sleep(wait)
    raise CloudError("replicate 网络不可达（重试 %d 次后仍失败）: %s" % (attempts, last))


def _get(path, timeout=60):
    target = path if path.startswith("http") else API + path
    req = urllib.request.Request(target, headers=_headers())
    try:
        with _open_retry(req, timeout=timeout, attempts=3) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate %s -> %s %s" % (path, e.code, e.read()[:300]),
                         status=e.code)


def _create_with_retry(body):
    """创建预测：429/5xx 指数退避重试（限流友好）。"""
    for attempt in range(1, RETRIES + 1):
        try:
            return _post("/predictions", body)
        except CloudError as e:
            if e.status not in TRANSIENT or attempt == RETRIES:
                raise
            wait = min(5 * (2 ** (attempt - 1)) + 2, 40)
            print("[cloud] create retry %d/%d after %ss (%s)" % (attempt, RETRIES, wait, e.status),
                  flush=True)
            time.sleep(wait)
    raise CloudError("replicate 创建预测失败（重试耗尽）")


def _download(url):
    req = urllib.request.Request(url)
    with _open_retry(req, timeout=180, attempts=3) as r:
        return r.read()


def generate_still(payload, progress_fn=None):
    """云 txt2img；返回 [(bytes, mime, w, h, None)]。调用前按间隔限速。"""
    if DELAY_MS > 0:
        time.sleep(DELAY_MS / 1000.0)
    if progress_fn:
        progress_fn(10, "cloud_submit")
    params = payload.get("params") or {}
    version = MODEL.split(":", 1)[1] if ":" in MODEL else MODEL
    body = {
        "version": version,
        "input": {
            "prompt": payload.get("positive_prompt", ""),
            "negative_prompt": payload.get("negative_prompt", ""),
            "width": int(params.get("width") or 1024),
            "height": int(params.get("height") or 1024),
            "num_outputs": 1,
            "scheduler": "DPMSolverMultistep",
            "num_inference_steps": int(params.get("steps", 30)),
            "guidance_scale": float(params.get("cfg", 7.5)),
            "seed": int(payload.get("seed") or int(time.time() % 10 ** 9)),
        },
    }
    if progress_fn:
        progress_fn(25, "cloud_wait")
    pred = _create_with_retry(body)
    url = pred.get("urls", {}).get("get")
    if not url:
        raise CloudError("replicate 无预测查询 URL")
    deadline = time.time() + 420
    state = None
    while time.time() < deadline:
        st = _get(url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            err = str(st.get("error"))[:300]
            # 任务端瞬时失败（模型超时/排队被拒）也可整体重试一次
            if err and ("timeout" in err.lower() or "rate" in err.lower()) and RETRIES > 1:
                time.sleep(6)
                pred = _create_with_retry(body)
                url = pred.get("urls", {}).get("get")
                deadline = time.time() + 420
                continue
            raise CloudError("replicate 预测失败: %s" % err, status=500)
        time.sleep(4)
    else:
        raise CloudError("replicate 预测超时", status=504)
    outputs = st.get("output") or []
    if not outputs:
        raise CloudError("replicate 无输出", status=500)
    out0 = outputs[0] if isinstance(outputs[0], str) else outputs[0]["url"]
    data = _download(out0)
    w = int(params.get("width") or 1024)
    h = int(params.get("height") or 1024)
    return [(data, "image/png", w, h, None)]


# ---------- 云图片（Replicate 通道：模型如 black-forest-labs/flux-2-pro） ----------

def replicate_image(payload, token, model, progress_fn=None):
    """Replicate 通用 txt2img（model=owner/name 或 owner/name:version）。返回 [(bytes,'image/png',w,h,None)]。

    §11.6：model 为空时用测试固定版本 stability-ai/stable-diffusion:ac732df8…；
    SD 系需 width/height 为 64 倍数，并支持 steps/cfg → num_inference_steps/guidance_scale。"""
    if not token:
        raise CloudError("图片云未配置 API Key（Replicate）")
    model = model or DEFAULT_IMAGE_MODEL
    if not model:
        raise CloudError("图片云未配置模型（Replicate）：请在引擎设置中填写模型；"
                         "调试可设 WEAVEORA_REPLICATE_TEST_MODELS=1 使用 §11.6 测试模型")
    positive = payload.get("positive_prompt", "")
    params = payload.get("params") or {}
    inp = {"prompt": positive}
    # 参考图：多主体必须带全 + 保留“第 i 张=哪个主体”的映射；单图模型不得拿多图当 img2img（会整图串脸）
    refs = payload.get("referenceKeys") or []
    subjects = payload.get("referenceSubjects") or []
    primary = payload.get("primarySubject") or ""
    ml_lower = (model or "").lower()
    if refs:
        picked_idx = list(range(len(refs)))
        if len(refs) > 1:
            # 单图/img2img 系模型：只允许用“该镜主主体”对应的那张，其余不挂（避免两张脸互相带偏）
            multi_ok = ("flux" in ml_lower) or ("kontext" in ml_lower) or ("nano-banana" in ml_lower)
            if not multi_ok:
                chosen = None
                for i, s in enumerate(subjects):
                    if primary and s == primary:
                        chosen = i
                        break
                if chosen is None:
                    chosen = 0
                picked_idx = [chosen]
                print("[cloud-image] 模型 %s 不支持多图，只用主主体图 idx=%d subject=%s"
                      % (model, chosen, (subjects[chosen] if chosen < len(subjects) else "-")), flush=True)
        urls = []
        mapping = []
        for i in picked_idx:
            try:
                blob = _fetch_asset(refs[i])
                u = _upload_file(token, refs[i].split("/")[-1] or "ref.png", blob)
                urls.append(u)
                subj = subjects[i] if i < len(subjects) else ""
                if subj:
                    mapping.append("%d) %s" % (len(urls), subj))
            except Exception as e:
                print("[cloud-image] ref#%d 上传失败，跳过: %s" % (i, e), flush=True)
        if urls:
            if "flux" in ml_lower or len(urls) > 1:
                inp["input_images"] = urls
            else:
                inp["image"] = urls[0]
            if len(urls) > 1 and mapping:
                # 把“图序→主体”写进 prompt，降低串脸
                inp["prompt"] = "Reference images in order: " + "; ".join(mapping) + ". " + positive
            print("[cloud-image] refs=%d subjects=%s model=%s" % (len(urls), mapping, model), flush=True)
    ar = payload.get("aspect_ratio")
    if (model or "").lower().startswith("stability-ai/") or "sdxl" in (model or "").lower():
        # SD 系按 width/height 出图（aspect_ratio 不生效；尺寸必须为 64 倍数）
        inp["width"] = _round64(params.get("width") or 1024)
        inp["height"] = _round64(params.get("height") or 1024)
        inp["num_outputs"] = 1
        steps = int(params.get("steps") or 30)
        inp["num_inference_steps"] = max(10, min(100, steps))
        cfg = params.get("cfg")
        if isinstance(cfg, (int, float)):
            inp["guidance_scale"] = float(cfg)
        neg = payload.get("negative_prompt") or ""
        if neg:
            inp["negative_prompt"] = neg
    elif ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
        inp["aspect_ratio"] = ar
    body_in = {"input": inp}
    if ":" in model:
        body_in["version"] = model.split(":", 1)[1]
    if progress_fn:
        progress_fn(20, "cloud_submit")
    if "version" in body_in:
        req = urllib.request.Request(API + "/predictions", data=json.dumps(body_in).encode(),
                                     headers=_auth(token), method="POST")
    else:
        parts = model.split("/")
        owner = parts[0] if parts else ""
        name = parts[1] if len(parts) > 1 else ""
        if not owner or not name:
            raise CloudError("图片云模型名不完整（如 black-forest-labs/flux-2-pro）")
        req = urllib.request.Request(API + "/models/%s/%s/predictions" % (owner, name),
                                     data=json.dumps(body_in).encode(), headers=_auth(token), method="POST")
    try:
        with _open_retry(req, timeout=120) as r:
            pred = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate 图片创建失败: %s %s" % (e.code, e.read()[:300]), status=e.code)
    get_url = pred.get("urls", {}).get("get")
    if not get_url:
        raise CloudError("replicate 图片无查询 URL")
    deadline = time.time() + 420
    state = None
    while time.time() < deadline:
        st = _get(get_url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            raise CloudError("replicate 图片预测失败: %s" % str(st.get("error"))[:300])
        time.sleep(4)
    else:
        raise CloudError("replicate 图片预测超时")
    outputs = st.get("output")
    url = None
    if isinstance(outputs, str):
        url = outputs
    elif isinstance(outputs, list) and outputs:
        o = outputs[0]
        url = o if isinstance(o, str) else (o.get("url") if isinstance(o, dict) else None)
    elif isinstance(outputs, dict):
        url = outputs.get("url") or outputs.get("video") or outputs.get("output")
        if not isinstance(url, str):
            url = None
    if not url or not url.startswith("http"):
        raise CloudError("replicate 图片无有效输出 url（output=%s）" % str(outputs)[:120])
    data = _download(url)
    w = int(params.get("width") or 1024)
    h = int(params.get("height") or 1024)
    return [(data, "image/png", w, h, None)]


# ---------- 云视频（Replicate 通道，每用户 token/model） ----------

_API_ENV = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
_TOKEN_ENV = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")


def _auth(token):
    if not token:
        raise CloudError("云视频未配置 Replicate Token")
    return {"Authorization": "Bearer " + token, "Content-Type": "application/json"}


def _fetch_asset(storage_key):
    """经 weaveora internal 通道取参考帧原始字节。"""
    import base64 as _b64
    q = urllib.parse.quote(_b64.urlsafe_b64encode(storage_key.encode()).decode(), safe="")
    req = urllib.request.Request("%s/internal/assets?key=%s" % (_API_ENV, q),
                                 headers={"X-Worker-Token": _TOKEN_ENV})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def _upload_file(token, filename, data, ctype="image/png"):
    boundary = "----wvfile" + str(int(time.time() * 1e6))
    head = ("--%s\r\nContent-Disposition: form-data; name=\"content\"; "
            "filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, filename, ctype)).encode()
    body = head + data + ("\r\n--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(API + "/files", data=body,
                                 headers={"Authorization": "Bearer " + token,
                                          "Content-Type": "multipart/form-data; boundary=" + boundary},
                                 method="POST")
    last = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                resp = json.loads(r.read())
            urls = resp.get("urls") or {}
            get_url = urls.get("get")
            if get_url:
                return get_url
            raise CloudError("replicate 文件上传无 get url")
        except CloudError:
            raise
        except Exception as e:
            last = e
            time.sleep(3 * attempt)
    raise CloudError("replicate 文件上传失败: %s" % last)


def _round64(v):
    try:
        n = int(v)
    except (TypeError, ValueError):
        n = 1024
    return max(64, int(round(n / 64.0)) * 64)


def _video_input(model, positive, image_url):
    ml = (model or "").lower()
    if "minimax" in ml:
        return {"prompt": positive}          # minimax/video-01 文生视频
    return {"prompt": positive, "image": image_url}  # 图生视频（默认）


def generate_motion_via_replicate(payload, token, model, progress_fn=None):
    """云视频：参考帧上传 → 模型预测 → 轮询 → 下载 mp4。返回 [(bytes,'video/mp4',w,h,None)]。

    §11.6 测试口径（仅 WEAVEORA_REPLICATE_TEST_MODELS=1 时生效）：model 为空 → prunaai/p-video，
    强制 draft=ON、resolution ≤720p；payload.tailKey 在配置 WEAVEORA_VIDEO_LAST_FRAME_PARAM 时作为末帧上传。"""
    model = model or DEFAULT_VIDEO_MODEL or "minimax/video-01"
    if progress_fn:
        progress_fn(15, "cloud_upload")
    key = payload.get("keyframeKey")
    img_url = None
    if key:
        try:
            data = _fetch_asset(key)
            img_url = _upload_file(token, key.split("/")[-1] or "frame.png", data)
        except Exception as e:
            if "minimax" not in (model or "").lower():
                raise CloudError("参考帧上传失败: %s" % e)
    positive = payload.get("positive_prompt", "")
    inp = _video_input(model, positive, img_url)
    ml = (model or "").lower()
    if "p-video" in ml:
        inp["fps"] = 24
        try:
            dur = int(round(float(payload.get("duration_sec") or 5)))
        except (TypeError, ValueError):
            dur = 5
        inp["duration"] = max(1, min(20, dur))
        # 测试档硬约束 draft ON + ≤720p；生产（TEST_MODE=0）仅在显式 env 指定时注入
        if TEST_MODE:
            inp["draft"] = True
            inp["resolution"] = VIDEO_RESOLUTION or "720p"
            inp["prompt_upsample"] = False
        else:
            if VIDEO_DRAFT:
                inp["draft"] = True
            if VIDEO_RESOLUTION:
                inp["resolution"] = VIDEO_RESOLUTION
        ar = payload.get("aspect_ratio")
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            inp["aspect_ratio"] = ar
    elif "minimax" not in ml and "wan" not in ml:
        # kling 等图生视频：画幅与负面词可选注入（wan 系不支持 aspect_ratio，随参考图比例）
        ar = payload.get("aspect_ratio")
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            inp["aspect_ratio"] = ar
        neg = payload.get("negative_prompt") or ""
        if neg:
            inp["negative_prompt"] = neg
    # P2 尾帧引导（双关键帧）：按模型参数名可选注入
    tail = payload.get("tailKey")
    if tail and VIDEO_LAST_FRAME_PARAM:
        try:
            tdata = _fetch_asset(tail)
            turl = _upload_file(token, tail.split("/")[-1] or "tail.png", tdata)
            inp[VIDEO_LAST_FRAME_PARAM] = turl
            print("[cloud-video] last frame attached param=%s" % VIDEO_LAST_FRAME_PARAM, flush=True)
        except Exception as e:
            print("[cloud-video] tailKey 上传失败，忽略: %s" % e, flush=True)
    elif tail:
        print("[cloud-video] 模型 %s 无末帧参数（tailKey 忽略；如需可设 WEAVEORA_VIDEO_LAST_FRAME_PARAM）"
              % model, flush=True)
    # owner/name 或 owner/name:version
    body_in = {"input": inp}
    if ":" in model:
        ver = model.split(":", 1)[1]
        body_in["version"] = ver
    if progress_fn:
        progress_fn(30, "cloud_submit")
    parts = model.split(":", 1)[0].split("/")
    owner, name = (parts[0], parts[1]) if len(parts) > 1 else ("minimax", "video-01")
    if "version" in body_in:
        req = urllib.request.Request(API + "/predictions", data=json.dumps(body_in).encode(),
                                     headers=_auth(token), method="POST")
    else:
        req = urllib.request.Request(API + "/models/%s/%s/predictions" % (owner, name),
                                     data=json.dumps(body_in).encode(), headers=_auth(token), method="POST")
    try:
        with _open_retry(req, timeout=120) as r:
            pred = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate 视频创建失败: %s %s" % (e.code, e.read()[:300]), status=e.code)
    get_url = pred.get("urls", {}).get("get")
    if not get_url:
        raise CloudError("replicate 无预测查询 URL")
    if progress_fn:
        progress_fn(40, "cloud_wait")
    deadline = time.time() + 1500
    state = None
    while time.time() < deadline:
        st = _get(get_url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            raise CloudError("replicate 视频预测失败: %s" % str(st.get("error"))[:300])
        time.sleep(5)
    else:
        raise CloudError("replicate 视频预测超时")
    outputs = st.get("output")
    print("[cloud-video] output=", repr(outputs)[:200], flush=True)
    first = None
    if isinstance(outputs, str):
        first = outputs
    elif isinstance(outputs, list) and outputs:
        o = outputs[0]
        first = o if isinstance(o, str) else (o.get("url") if isinstance(o, dict) else None)
    elif isinstance(outputs, dict):
        first = outputs.get("url") or outputs.get("video") or outputs.get("output")
    if not first or not str(first).startswith("http"):
        raise CloudError("replicate 视频无有效输出 url（%s）" % str(outputs)[:200])
    print("[cloud-video] download=", str(first)[:100], flush=True)
    data = _download(str(first))
    w = int((payload.get("params") or {}).get("width") or 720)
    h = int((payload.get("params") or {}).get("height") or 1280)
    return [(data, "video/mp4", w, h, None)]
