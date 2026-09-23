#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用项目自己的 LLM（OpenAI 兼容，支持 image_url）看一张公网图片，把结论以文本返回。

用途：助手自身模型没有视觉能力时，替代「人眼看一下」这一步（照 docs/lipsync-setup.md 的看图办法）。
Key 只从 /etc/weaveora/weaveora-api.env 读，**不打印、不落盘**。

用法（API 机）：python3 ask_image.py <image_url> "<问题>"
"""
import json
import os
import sys
import urllib.request

ENVF = "/etc/weaveora/weaveora-api.env"


def env(name):
    v = os.environ.get(name)
    if v:
        return v
    try:
        with open(ENVF, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, val = line.split("=", 1)
                if k.strip() == name:
                    return val.strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    url, question = sys.argv[1], sys.argv[2]
    base = env("WEAVEORA_LLM_BASE_URL").rstrip("/")
    key = env("WEAVEORA_LLM_API_KEY")
    model = env("WEAVEORA_LLM_MODEL")
    if not (base and key and model):
        print("[ask] LLM 未配置齐全（base=%s model=%s key=%s）"
              % (bool(base), model or "-", "有" if key else "无"))
        return 3
    body = {
        "model": model,
        "max_tokens": int(os.environ.get("ASK_MAX_TOKENS", "12000")),
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": question},
            {"type": "image_url", "image_url": {"url": url}},
        ]}],
    }
    req = urllib.request.Request(base + "/chat/completions",
                                data=json.dumps(body).encode("utf-8"),
                                headers={"Content-Type": "application/json",
                                         "Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=300) as r:
        d = json.loads(r.read().decode("utf-8"))
    ch = (d.get("choices") or [{}])[0]
    msg = ch.get("message") or {}
    print("[ask] model=%s finish=%s" % (d.get("model"), ch.get("finish_reason")))
    print("---- content ----")
    print((msg.get("content") or "").strip())
    rc = (msg.get("reasoning_content") or "").strip()
    if rc:
        print("---- reasoning(前 800 字) ----")
        print(rc[:800])
    return 0


if __name__ == "__main__":
    sys.exit(main())
