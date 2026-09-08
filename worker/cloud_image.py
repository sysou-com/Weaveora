#!/usr/bin/env python3
"""OpenAI Images 兼容云图适配器（引擎中立，用于用户配置的图片云 API）。
协议：POST {baseUrl}/v1/images/generations
      Authorization: Bearer <apiKey>  |  Basic base64(username:password)
      body {"model":?,"prompt":...,"n":1,"size":"WxH"}  → data[0].b64_json | url
仅依赖标准库；base 需以 http(s):// 开头。
"""
import base64
import json
import os
import urllib.request
import urllib.error
import io


def _headers(cfg):
    auth_type = (cfg.get("authType") or "api_key")
    h = {"Content-Type": "application/json"}
    key = cfg.get("apiKey") or ""
    user = cfg.get("username") or ""
    pwd = cfg.get("password") or ""
    if auth_type == "basic" and user:
        token = base64.b64encode(("%s:%s" % (user, pwd)).encode()).decode()
        h["Authorization"] = "Basic " + token
    else:
        if not key:
            raise RuntimeError("云图片缺少 API Key")
        h["Authorization"] = "Bearer " + key
    return h


def generate(positive, params, cfg, timeout=300):
    """调用 OpenAI-images 兼容接口，返回 PNG 字节列表。"""
    base = (cfg.get("baseUrl") or "").rstrip("/")
    if not base.startswith("http"):
        raise RuntimeError("云图片未配置 BaseURL")
    w = int((params or {}).get("width") or 1024)
    h = int((params or {}).get("height") or 1024)
    body = {
        "prompt": positive,
        "n": 1,
        "size": "%dx%d" % (w, h),
    }
    model = cfg.get("model") or ""
    if model:
        body["model"] = model
    req = urllib.request.Request(base + "/v1/images/generations",
                                 data=json.dumps(body).encode(),
                                 headers=_headers(cfg), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            resp = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError("cloud image %s -> %s %s" % (e.code, e.reason,
                                                        e.read()[:300].decode(errors="replace")))
    items = (resp.get("data") or [])
    if not items:
        raise RuntimeError("cloud image 响应无 data")
    item = items[0]
    b64 = item.get("b64_json")
    if b64:
        return [base64.b64decode(b64)]
    url = item.get("url")
    if url:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return [r.read()]
    raise RuntimeError("cloud image 响应既无 b64_json 也无 url")
