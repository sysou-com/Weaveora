# -*- coding: utf-8 -*-
"""用 ModelScope 搜索接口找 Qwen-Image-Edit-2511 / 2512 的正确仓库（避免瞎猜仓库名）。"""
import json
import urllib.parse
import urllib.request


def search(kw):
    url = "https://modelscope.cn/api/v1/dolphin/models?" + urllib.parse.urlencode(
        {"PageSize": 20, "PageNumber": 1, "Name": kw, "SortBy": "Default"})
    try:
        with urllib.request.urlopen(url, timeout=25) as r:
            d = json.loads(r.read().decode())
    except Exception as e:
        return ["ERR %s" % str(e)[:80]]
    out = []
    for m in (d.get("Data", {}).get("Model", {}).get("Models") or []):
        out.append("%s/%s  (%s)" % (m.get("Path", "?").split("/")[0], m.get("Name"), m.get("ChineseName") or ""))
    return out[:12]


for kw in ("Qwen-Image-Edit-2511", "Qwen-Image-2512", "Qwen-Image-Edit-2509", "Qwen-Image-Edit"):
    print("== 搜索: %s ==" % kw)
    for line in search(kw):
        print("   ", line)
