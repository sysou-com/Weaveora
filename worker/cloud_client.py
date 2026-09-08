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


def _get(path, timeout=60):
    target = path if path.startswith("http") else API + path
    req = urllib.request.Request(target, headers=_headers())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
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
    with urllib.request.urlopen(req, timeout=180) as r:
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
    """Replicate 通用 txt2img（model=owner/name 或 owner/name:version）。返回 [(bytes,'image/png',w,h,None)]。"""
    if not token:
        raise CloudError("图片云未配置 API Key（Replicate）")
    model = model or ""
    positive = payload.get("positive_prompt", "")
    params = payload.get("params") or {}
    inp = {"prompt": positive}
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
        with urllib.request.urlopen(req, timeout=120) as r:
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


def _video_input(model, positive, image_url):
    ml = (model or "").lower()
    if "minimax" in ml:
        return {"prompt": positive}          # minimax/video-01 文生视频
    return {"prompt": positive, "image": image_url}  # kling/其它 图生视频（默认）


def generate_motion_via_replicate(payload, token, model, progress_fn=None):
    """云视频：参考帧上传 → 模型预测 → 轮询 → 下载 mp4。返回 [(bytes,'video/mp4',w,h,None)]。"""
    model = model or "minimax/video-01"
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
    if "minimax" not in (model or "").lower():
        # kling 等图生视频：画幅与负面词可选注入
        ar = payload.get("aspect_ratio")
        if ar in ("16:9", "9:16", "1:1", "3:2", "2:3"):
            inp["aspect_ratio"] = ar
        neg = payload.get("negative_prompt") or ""
        if neg:
            inp["negative_prompt"] = neg
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
        with urllib.request.urlopen(req, timeout=120) as r:
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
