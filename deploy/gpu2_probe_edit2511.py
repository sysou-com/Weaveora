# -*- coding: utf-8 -*-
"""继续探测 Qwen-Image-Edit-2511 / 2509 的仓库与文件名（只发 Range 请求）。"""
import re
import urllib.parse
import urllib.request

API = "https://modelscope.cn/api/v1/models/%s/repo?Revision=master&FilePath=%s"
NAMES = [
    "qwen_image_edit_2511_fp8_e4m3fn.safetensors",
    "qwen_image_edit_2511_fp8_e4m3fn_scaled.safetensors",
    "qwen_image_edit_2509_fp8_e4m3fn.safetensors",
    "split_files/diffusion_models/qwen_image_edit_2511_fp8_e4m3fn.safetensors",
    "split_files/diffusion_models/qwen_image_edit_2509_fp8_e4m3fn.safetensors",
]
REPOS = [
    "Comfy-Org/Qwen-Image-Edit_ComfyUI",
    "Comfy-Org/Qwen-Image-Edit-2511_ComfyUI_files",
    "Comfy-Org/Qwen-Image-Edit-2509_ComfyUI",
    "AI-ModelScope/Qwen-Image-Edit-2511",
    "Qwen/Qwen-Image-Edit-2511",
    "Comfy-Org/Qwen-Image-Edit-2511-ComfyUI",
]


def probe(repo, path):
    url = API % (repo, urllib.parse.quote(path, safe=""))
    try:
        req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            m = re.search(r"/(\d+)$", r.headers.get("Content-Range") or "")
            return ("OK %d bytes (%.2f GiB)" % (int(m.group(1)), int(m.group(1)) / 2 ** 30)) if m else "200(小文件?)"
    except Exception as e:
        return "ERR %s" % str(e)[:40]


for repo in REPOS:
    hits = [(p, probe(repo, p)) for p in NAMES]
    good = [(p, r) for p, r in hits if r.startswith("OK")]
    print("== %s ==  %s" % (repo, good if good else "（都没命中）"))
