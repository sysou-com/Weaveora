#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P12 诊断：同 seed 下「带参考图 vs 不带参考图」出图是否相同。

判据（客观、二值）：
  - 若两者**字节完全相同** → 模型根本没在用参考图（说明我们送参有问题）；
  - 若明显不同 → 参考图确实参与了生成，问题在模型能力/提示词指令。

产物写到 /tmp/ab_{with,without}.jpg 供本地比对。
"""
import io
import json
import os
import sys

sys.path.insert(0, "/opt/weaveora")
import cloud_client as cc  # noqa: E402

MODEL = "black-forest-labs/flux-2-klein-9b"
TOKEN = sys.argv[1]
REF_KEYS = sys.argv[2:4]
PROMPT = ("A young nobleman in white silk robe with jade pendant and a young woman in pale pink gown "
          "sitting close on a soft bed, whispering; classical Chinese dreamlike aesthetic, cinematic")

CFG = {
    "model": MODEL,
    "mapping": {"m": {"refs": "images", "refsIsArray": True, "refsMax": 5,
                      "prompt": "prompt", "aspect": "aspect_ratio", "seed": "seed"}},
    "schemaParams": [
        {"name": "images", "type": "array", "group": "refs", "userEditable": False},
        {"name": "prompt", "type": "string", "group": "prompt", "userEditable": False},
        {"name": "aspect_ratio", "type": "enum", "group": "quality", "userEditable": True},
        {"name": "megapixels", "type": "enum", "group": "quality", "userEditable": True},
    ],
    "params": {"megapixels": "1"},
}


def run(label, refs):
    payload = {
        "positive_prompt": PROMPT,
        "seed": 20260912,
        "aspect_ratio": "16:9",
        "params": {},
        "referenceKeys": refs,
        "referenceSubjects": ["秦可卿", "宝玉"][:len(refs)],
        "primarySubject": "宝玉",
    }
    outs = None
    for attempt in range(1, 6):
        try:
            outs = cc.replicate_image(payload, TOKEN, MODEL, cfg=dict(CFG))
            break
        except cc.CloudError as e:
            msg = str(e)
            if '429' in msg or 'throttled' in msg:
                print("429 限流，等 20s 重试（%d/5）" % attempt, flush=True)
                import time as _t; _t.sleep(20); continue
            raise
    if outs is None:
        raise SystemExit('重试耗尽')
    data = outs[0][0]
    path = "/tmp/ab_%s.jpg" % label
    io.open(path, "wb").write(data)
    import hashlib
    print("%-9s size=%-8d md5=%s" % (label, len(data), hashlib.md5(data).hexdigest()[:16]))
    return data


print("prompt:", PROMPT[:80], "…")
a = run("with", list(REF_KEYS))
b = run("without", [])
print()
print("RESULT", "REFS_IGNORED(字节相同)" if a == b else "REFS_INFLUENCE_OUTPUT(输出不同)")
