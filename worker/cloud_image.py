#!/usr/bin/env python3
"""OpenAI Images 兼容云图适配器（引擎中立；用于用户配置的图片云 API）。

协议：POST {base}/images/generations 或 {base}/v1/images/generations
      Authorization: Bearer <apiKey>  |  Basic base64(username:password)
      body {"model":?,"prompt":...,"n":1,"size":"WxH","image":?}  → data[0].url | b64_json

实测踩过的坑（2026-09）：
  1) 端点拼接：用户把**完整端点**（如 https://ark.cn-beijing.volces.com/api/v3/images/generations）
     填进 BaseUrl，旧代码无条件再拼一层 "/v1/images/generations" →
     请求打到 /api/v3/images/generations/v1/images/generations → 404 InvalidAction。
     现在按 base 的形状选端点，并在 404 时自动换下一个候选。
  2) 参考图：旧代码**完全不发**参考图 → 出图与人物参考无关。Ark/seedream 用 `image`
     （字符串或数组，URL 或 data URI）。
  3) 尺寸：Ark 系对 size 有下限要求；项目尺寸偏小时（如 16:9 的 1280x704）会被拒。
     这里把短边抬到 ≥1024 并取偶数，长边不超过 4096。
  4) 水印：火山方舟部分模型默认**带水印**，会毁掉成片 → 显式 watermark=false。
"""
import base64
import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request

# 火山方舟（doubao-seedream 系）要求总像素不小于 1920x1920；不足直接 400 InvalidParameter。
# 可用 WEAVEORA_ARK_MIN_PIXELS 覆盖。
ARK_MIN_PIXELS = int(os.environ.get("WEAVEORA_ARK_MIN_PIXELS", "3686400"))
# 方舟 seedream 单次最多参考图张数（超出会被拒；这里主动裁剪并告警）
ARK_MAX_REFS = int(os.environ.get("WEAVEORA_ARK_MAX_REFS", "15"))
# 常见画幅的「标准尺寸」（均满足上述像素下限，长边 ≤ 4096）
_ASPECT_CANON = {
    (16, 9): (2560, 1440),
    (9, 16): (1440, 2560),
    (1, 1): (1920, 1920),
    (3, 2): (2304, 1536),
    (2, 3): (1536, 2304),
    (4, 3): (2240, 1680),
    (3, 4): (1680, 2240),
}


def _is_ark(base: str) -> bool:
    host = urllib.parse.urlparse(base).netloc.lower()
    return "volces.com" in host or "ark." in host


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


def _endpoints(base: str):
    """按 base 形状给出候选端点（第一个最可能）。"""
    b = (base or "").rstrip("/")
    path = urllib.parse.urlparse(b).path.rstrip("/")
    if path.endswith("/images/generations"):
        return [b]                                   # 用户直接填了完整端点
    import re
    if re.search(r"/(v\d+|api/v\d+)$", path):        # 已经带版本路径（/v1、/api/v3）
        return [b + "/images/generations", b + "/v1/images/generations"]
    return [b + "/v1/images/generations", b + "/images/generations"]


def _even(v: int) -> int:
    v = int(round(v))
    return v if v % 2 == 0 else v + 1


def _fit_size(w: int, h: int, min_side: int = 1024, max_side: int = 4096, min_pixels: int = 0):
    """把项目尺寸抬到云服务能接受的范围（保持画幅比例）。

    min_pixels > 0（火山方舟）时：优先取该画幅的标准尺寸（如 16:9 → 2560x1440），
    否则按面积放大到满足像素下限；长边不超 max_side。
    """
    w, h = max(1, int(w)), max(1, int(h))
    if min_pixels > 0 and w * h < min_pixels:
        # 按比例找最接近的标准画幅（容差内就用标准尺寸，输出更“正规”）
        ratio = float(w) / h
        best, best_err = None, 1e9
        for (rw, rh), size in _ASPECT_CANON.items():
            err = abs(ratio - float(rw) / rh)
            if err < best_err:
                best, best_err = size, err
        if best and best_err / max(ratio, 1e-6) < 0.05 and best[0] * best[1] >= min_pixels:
            return best
        k = math.sqrt(float(min_pixels) / float(w * h))
        w, h = _even(w * k), _even(h * k)
    short = min(w, h)
    if short < min_side:
        k = float(min_side) / short
        w, h = _even(w * k), _even(h * k)
    long_side = max(w, h)
    if long_side > max_side:
        k = float(max_side) / long_side
        w, h = _even(w * k), _even(h * k)
    return w, h


def _data_uri(blob: bytes, mime: str = "image/jpeg") -> str:
    return "data:%s;base64,%s" % (mime, base64.b64encode(blob).decode())


def generate(positive, params, cfg, timeout=300, ref_blobs=None):
    """调用 OpenAI-images 兼容接口，返回图片字节列表。

    :param ref_blobs 参考图字节列表（可选）：Ark/seedream 用 `image` 传 URL 或 data URI，
                     多张时为数组。注意各大网关字段名不一，这里按 OpenAI 兼容生态里
                     最常见的 `image` 发送；不认识该字段的网关会忽略它（不影响出图）。
    """
    base = (cfg.get("baseUrl") or "").rstrip("/")
    if not base.startswith("http"):
        raise RuntimeError("云图片未配置 BaseURL")
    w, h = _fit_size(int((params or {}).get("width") or 1024),
                     int((params or {}).get("height") or 1024),
                     min_pixels=(ARK_MIN_PIXELS if _is_ark(base) else 0))
    body = {"prompt": positive, "n": 1, "size": "%dx%d" % (w, h)}
    model = cfg.get("model") or ""
    if model:
        body["model"] = model
    if ref_blobs:
        # 上限：优先用用户在引擎配置里按官方文档填的值（如方舟 doubao-seedream-5 = 14），
        # 否则方舟通道用内置默认，其它网关不限制（由对方报错）
        cap = int((cfg or {}).get("refsMax") or 0) or (ARK_MAX_REFS if _is_ark(base) else 0)
        if cap > 0 and len(ref_blobs) > cap:
            print("[cloud-image] 警告：本模型单次最多 %d 张参考图，已裁掉 %d 张"
                  % (cap, len(ref_blobs) - cap), flush=True)
            ref_blobs = ref_blobs[:cap]
        uris = [_data_uri(b) for b in ref_blobs]
        body["image"] = uris if len(uris) > 1 else uris[0]
    if _is_ark(base):
        # 火山方舟专有：默认带水印会毁成片；sequential_image_generation 关掉避免一次出多图
        body["watermark"] = False
        body["sequential_image_generation"] = "disabled"

    payload = json.dumps(body).encode()
    last_err = None
    for url in _endpoints(base):
        req = urllib.request.Request(url, data=payload, headers=_headers(cfg), method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                resp = json.loads(r.read().decode())
            break
        except urllib.error.HTTPError as e:
            detail = e.read()[:400].decode(errors="replace")
            last_err = "cloud image %s -> %s %s (%s)" % (e.code, e.reason, detail, url)
            if e.code == 404:
                continue                      # 端点不对 → 试下一个候选
            raise RuntimeError(last_err)
    else:
        raise RuntimeError(last_err or "cloud image 无可用端点")

    items = (resp.get("data") or [])
    if not items:
        raise RuntimeError("cloud image 响应无 data：%s" % json.dumps(resp)[:240])
    item = items[0]
    b64 = item.get("b64_json")
    if b64:
        return [base64.b64decode(b64)]
    url = item.get("url")
    if url:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return [r.read()]
    raise RuntimeError("cloud image 响应既无 b64_json 也无 url")
