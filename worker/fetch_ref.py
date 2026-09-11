#!/usr/bin/env python3
"""按存储 key 从内部通道把参考音拉到本地文件（排障用）。

用法：python fetch_ref.py <存储key> <输出路径>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import comfy_client as c  # noqa: E402

sk = sys.argv[1]
out = sys.argv[2]
raw, mime = c.fetch_reference_bytes(sk)
with open(out, "wb") as f:
    f.write(raw)
print("fetched %d bytes (%s) -> %s" % (len(raw), mime, out))
