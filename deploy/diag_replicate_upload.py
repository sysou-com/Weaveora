#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 Replicate 文件上传 401：对比几种鉴权/写法，打印真实响应体。"""
import json
import sys
import urllib.error
import urllib.request

TOKEN = sys.argv[1]
IMG = sys.argv[2] if len(sys.argv) > 2 else "/tmp/up.jpg"
data = open(IMG, "rb").read()


def probe(label, url, headers, body=None, method="POST"):
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            print("%-28s -> %s %s" % (label, r.status, r.read()[:160]))
    except urllib.error.HTTPError as e:
        print("%-28s -> %s %s" % (label, e.code, e.read()[:220]))
    except Exception as e:
        print("%-28s -> ERR %s" % (label, e))


H = {"Authorization": "Bearer " + TOKEN,
     "User-Agent": "Mozilla/5.0 (compatible; Weaveora/1.0)"}

# 1) 账户（验证 token 是否有效/余额）
probe("GET /v1/account", "https://api.replicate.com/v1/account", H, method="GET")

# 2) 文件上传（带浏览器 UA）
b = b"----wvprobe\r\nContent-Disposition: form-data; name=\"content\"; filename=\"p.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n" + data + b"\r\n----wvprobe--\r\n"
probe("POST /v1/files (Bearer)",
      "https://api.replicate.com/v1/files",
      dict(H, **{"Content-Type": "multipart/form-data; boundary=----wvprobe"}), b)

# 3) 旧式 Token 前缀
probe("POST /v1/files (Token)",
      "https://api.replicate.com/v1/files",
      {"Authorization": "Token " + TOKEN, "Content-Type": "multipart/form-data; boundary=----wvprobe"}, b)

# 4) 不带 User-Agent（与 worker 现状一致）
probe("POST /v1/files (no UA)",
      "https://api.replicate.com/v1/files",
      {"Authorization": "Bearer " + TOKEN, "Content-Type": "multipart/form-data; boundary=----wvprobe"}, b)
