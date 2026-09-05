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
