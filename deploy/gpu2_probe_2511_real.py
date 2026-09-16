# -*- coding: utf-8 -*-
"""探测 Qwen-Image-Edit-2511 / Qwen-Image-2512 的**真实文件名**与体积（Range 请求，不下载）。
   线索：Comfy-Org 仓库里 2511 的变体名是 fp8mixed / bf16 / int8_convrot（不是 fp8_e4m3fn）。"""
import re
import urllib.parse
import urllib.request

API = "https://modelscope.cn/api/v1/models/%s/repo?Revision=master&FilePath=%s"
CAND = [
    ("Comfy-Org/Qwen-Image-Edit_ComfyUI", [
        "split_files/diffusion_models/qwen_image_edit_2511_fp8mixed.safetensors",
        "split_files/diffusion_models/qwen_image_edit_2511_int8_convrot.safetensors",
        "split_files/diffusion_models/qwen_image_edit_2511_bf16.safetensors",
    ]),
    ("Comfy-Org/Qwen-Image_ComfyUI", [
        "split_files/diffusion_models/qwen_image_2512_fp8mixed.safetensors",
        "split_files/diffusion_models/qwen_image_2512_fp8_e4m3fn.safetensors",
        "split_files/diffusion_models/qwen_image_2512_bf16.safetensors",
    ]),
    ("1038lab/Qwen-Image-Edit-2511-FP8", [
        "qwen-image-edit-2511-transformer-fp8.safetensors",
    ]),
]


def probe(repo, path):
    url = API % (repo, urllib.parse.quote(path, safe=""))
    try:
        req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=25) as r:
            m = re.search(r"/(\d+)$", r.headers.get("Content-Range") or "")
            return ("OK %d bytes (%.2f GiB)" % (int(m.group(1)), int(m.group(1)) / 2 ** 30)) if m else "200(小文件?)"
    except Exception as e:
        return "ERR %s" % str(e)[:40]


for repo, paths in CAND:
    print("== %s ==" % repo)
    for p in paths:
        print("   %-58s %s" % (p.split("/")[-1], probe(repo, p)))
