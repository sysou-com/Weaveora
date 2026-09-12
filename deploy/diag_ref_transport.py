#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探测参考图的可行传输方式：Replicate /v1/files 上传 vs 直接 data URI。

背景：/v1/files 出现 500/401（Replicate 侧抽风），而 worker 上传失败只打警告就继续生成 →
出图完全没参考。需要一个不依赖该接口的稳妥路径。
"""
import base64
import json
import sys
import time
import urllib.error
import urllib.request

TOKEN = sys.argv[1]
IMG = sys.argv[2]
MODEL = sys.argv[3] if len(sys.argv) > 3 else "black-forest-labs/flux-2-klein-9b"
data = open(IMG, "rb").read()
H = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
     "User-Agent": "Mozilla/5.0 (compatible; Weaveora/1.0)"}
API = "https://api.replicate.com/v1"


def post(path, obj):
    req = urllib.request.Request(API + path, data=json.dumps(obj).encode(), headers=H, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read()), None
    except urllib.error.HTTPError as e:
        return None, "%s %s" % (e.code, e.read()[:260])


def wait(pred, timeout=300):
    url = (pred.get("urls") or {}).get("get")
    end = time.time() + timeout
    while time.time() < end:
        req = urllib.request.Request(url, headers=H)
        with urllib.request.urlopen(req, timeout=60) as r:
            st = json.loads(r.read())
        if st.get("status") == "succeeded":
            return st.get("output")
        if st.get("status") in ("failed", "canceled"):
            return {"__error__": st.get("error")}
        time.sleep(3)
    return {"__error__": "timeout"}


print("模型:", MODEL, "| 参考图:", len(data), "bytes")

# 方式 A：/v1/files 上传（尝试 3 次）
gurl = None
for i in range(1, 4):
    b = (b'----wvprobe\r\nContent-Disposition: form-data; name="content"; filename="p.jpg"\r\n'
         b'Content-Type: image/jpeg\r\n\r\n' + data + b'\r\n----wvprobe--\r\n')
    req = urllib.request.Request(API + "/files", data=b, method="POST",
                                 headers={"Authorization": "Bearer " + TOKEN,
                                          "Content-Type": "multipart/form-data; boundary=----wvprobe"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            gurl = (json.loads(r.read()).get("urls") or {}).get("get")
        if gurl:
            print("A) /v1/files 上传: OK（第 %d 次）" % i)
            break
    except urllib.error.HTTPError as e:
        print("A) /v1/files 上传: %s %s（第 %d 次）" % (e.code, e.read()[:120], i))
        time.sleep(4)
    except Exception as e:
        print("A) /v1/files 上传: ERR %s（第 %d 次）" % (e, i))
        time.sleep(4)

# 方式 B：data URI（不依赖 files 接口）
data_uri = "data:image/jpeg;base64," + base64.b64encode(data).decode()

for label, img_field in (("A) 上传 URL", gurl), ("B) data URI", data_uri)):
    if not img_field:
        print("%s: 跳过（不可用）" % label)
        continue
    parts = MODEL.split("/")
    body = {"input": {"prompt": "The person from the reference image, portrait, plain background",
                      "images": [img_field], "aspect_ratio": "1:1", "megapixels": "1"}}
    pred, err = post("/models/%s/%s/predictions" % (parts[0], parts[1]), body)
    if err:
        print("%s 创建失败: %s" % (label, err))
        continue
    out = wait(pred)
    if isinstance(out, dict) and out.get("__error__"):
        print("%s 预测失败: %s" % (label, str(out["__error__"])[:160]))
    else:
        u = out[0] if isinstance(out, list) and out else out
        u = u if isinstance(u, str) else (u or {}).get("url")
        print("%s 成功，输出=%s" % (label, str(u)[:80]))
        try:
            with urllib.request.urlopen(urllib.request.Request(u), timeout=90) as r:
                d = r.read()
            p = "/tmp/probe_%s.jpg" % label[0].lower()
            open(p, "wb").write(d)
            print("   已存 %s (%d bytes)" % (p, len(d)))
        except Exception as e:
            print("   下载失败:", e)
