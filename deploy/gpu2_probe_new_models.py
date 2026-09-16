# -*- coding: utf-8 -*-
"""探测 Qwen-Image-Edit-2511 / Qwen-Image-2512 在 ModelScope 上的确切仓库与文件体积（只发 Range 请求，不下载）。"""
import re
import urllib.parse
import urllib.request

API = "https://modelscope.cn/api/v1/models/%s/repo?Revision=master&FilePath=%s"
CAND = [
    # (repo, 候选路径)
    ("Comfy-Org/Qwen-Image-Edit-2511_ComfyUI", [
        "split_files/diffusion_models/qwen_image_edit_2511_fp8_e4m3fn.safetensors",
        "split_files/diffusion_models/qwen_image_edit_2511_fp8_e4m3fn_scaled.safetensors",
        "split_files/diffusion_models/qwen_image_edit_2511_bf16.safetensors",
    ]),
    ("Comfy-Org/Qwen-Image-Edit-2511", [
        "qwen_image_edit_2511_fp8_e4m3fn.safetensors",
        "split_files/diffusion_models/qwen_image_edit_2511_fp8_e4m3fn.safetensors",
    ]),
    ("Comfy-Org/Qwen-Image-2512_ComfyUI", [
        "split_files/diffusion_models/qwen_image_2512_fp8_e4m3fn.safetensors",
        "split_files/diffusion_models/qwen_image_2512_fp8_e4m3fn_scaled.safetensors",
    ]),
    ("Comfy-Org/Qwen-Image_ComfyUI", [
        "split_files/diffusion_models/qwen_image_2512_fp8_e4m3fn.safetensors",
        "split_files/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors",
        "split_files/vae/qwen_image_vae.safetensors",
    ]),
]


def probe(repo, path):
    url = API % (repo, urllib.parse.quote(path, safe=""))
    try:
        req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=25) as r:
            m = re.search(r"/(\d+)$", r.headers.get("Content-Range") or "")
            if m:
                gb = int(m.group(1)) / 2 ** 30
                return "OK  %d bytes (%.2f GiB)" % (int(m.group(1)), gb)
            return "200 但无 Content-Range（可能是小文件或重定向）"
    except Exception as e:
        return "ERR %s" % str(e)[:70]


for repo, paths in CAND:
    print("== %s ==" % repo)
    for p in paths:
        print("   %-72s %s" % (p.split("/")[-1], probe(repo, p)))
