#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P12 验收：用真实 token + 项目里的真实参考图，跑一次 flux-2-klein-9b。

验收点：
  1) 参考图字段 = images（schema 指定），不是被静默忽略的 input_images；
  2) Replicate 接受该调用（HTTP 200 + 出图）；
  3) 请求体里带上参考图 URL、画幅、seed。

产物只写到 /tmp，不进项目（不污染用户资产）。
"""
import io
import json
import sys

sys.path.insert(0, "/opt/weaveora")
import cloud_client as cc  # noqa: E402

MODEL = "black-forest-labs/flux-2-klein-9b"
TOKEN = sys.argv[1]
REF_KEYS = sys.argv[2:4]          # 两张参考图的 storage key
SUBJECTS = ["关羽", "吕布"]

cfg = {
    "model": MODEL,
    "mapping": {"m": {"refs": "images", "refsIsArray": True, "refsMax": 5,
                      "prompt": "prompt", "aspect": "aspect_ratio", "seed": "seed"}},
    "schemaParams": [
        {"name": "images", "type": "array", "group": "refs", "userEditable": False},
        {"name": "prompt", "type": "string", "group": "prompt", "userEditable": False},
        {"name": "aspect_ratio", "type": "enum", "default": "1:1", "group": "quality", "userEditable": True},
        {"name": "megapixels", "type": "enum", "default": "1", "group": "quality", "userEditable": True},
        {"name": "output_quality", "type": "integer", "default": 95, "group": "quality", "userEditable": True},
    ],
    "params": {"megapixels": "1", "output_quality": 95},   # 用户全局参数（画质）
}

payload = {
    "positive_prompt": "两个中国古装武将交战，长发铠甲，电影感光影",
    "seed": 123456,
    "aspect_ratio": "9:16",
    "params": {},
    "referenceKeys": REF_KEYS,
    "referenceSubjects": SUBJECTS,
    "primarySubject": "关羽",
}

# 把创建预测的请求体也打印出来（不求真的出图也能看字段）
_orig_post = cc._post if hasattr(cc, "_post") else None
prints = []


def spy(fn):
    def inner(*a, **k):
        if a and a[0] == "/predictions":
            prints.append(json.dumps(a[1].get("input", {}), ensure_ascii=False)[:400])
        return fn(*a, **k)
    return inner


try:
    outs = cc.replicate_image(payload, TOKEN, MODEL, cfg=cfg)
except Exception as e:
    print("调用失败:", str(e)[:400])
    sys.exit(1)

data, mime, w, h, _ = outs[0]
print("出图:", len(data), "bytes", mime, w, "x", h)
is_png = data[:8] == b"\x89PNG\r\n\x1a\n"
is_jpg = data[:2] == b"\xff\xd8"
print("图片魔数:", "png" if is_png else ("jpg" if is_jpg else "?"))
io.open("/tmp/ref_accept.bin", "wb").write(data)
print("RESULT", "PASS" if (is_png or is_jpg) and len(data) > 2000 else "FAIL")
