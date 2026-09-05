#!/usr/bin/env python3
"""Weaveora 云引擎适配器（引擎中立；先支持 Replicate，便于无 GPU 的前期测试）。

环境变量：
  WEAVEORA_CLOUD_PROVIDER  replicate（默认）
  WEAVEORA_REPLICATE_TOKEN  Replicate API token
  WEAVEORA_REPLICATE_MODEL  模型 owner/name:version（默认 stability-ai/sdxl 固定版本）

说明：早期测试用 txt2img 云 API；IP-Adapter 一致性在部分云上不可用，
一致性正式验收仍走本地 Comfy 引擎（GPU 稳定后再接）。视频云 API（Runway/Kling/Veo 等）
属高阶档，后续按 Model Preset 再加适配器。
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


class CloudError(Exception):
    pass


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
        raise CloudError("replicate %s -> %s %s" % (path, e.code, e.read()[:300]))


def _get(path, timeout=60):
    target = path if path.startswith("http") else API + path
    req = urllib.request.Request(target, headers=_headers())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise CloudError("replicate %s -> %s %s" % (path, e.code, e.read()[:300]))


def _download(url):
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def generate_still(payload, progress_fn=None):
    """云 txt2img；返回 [(bytes, mime, w, h, None)]。"""
    if progress_fn:
        progress_fn(10, "cloud_submit")
    params = payload.get("params") or {}
    body = {
        "version": MODEL.split(":", 1)[1] if ":" in MODEL else MODEL,
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
    pred = _post("/predictions", body)
    url = pred.get("urls", {}).get("get")
    if not url:
        raise CloudError("replicate 无预测查询 URL")
    deadline = time.time() + 300
    while time.time() < deadline:
        st = _get(url)
        state = st.get("status")
        if state == "succeeded":
            break
        if state in ("failed", "canceled"):
            raise CloudError("replicate 预测失败: %s" % str(st.get("error"))[:300])
        time.sleep(3)
    else:
        raise CloudError("replicate 预测超时")
    outputs = st.get("output") or []
    if not outputs:
        raise CloudError("replicate 无输出")
    data = _download(outputs[0] if isinstance(outputs[0], str) else outputs[0]["url"])
    w = int(params.get("width") or 1024)
    h = int(params.get("height") or 1024)
    return [(data, "image/png", w, h, None)]
