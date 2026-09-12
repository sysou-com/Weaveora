#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「云图片产物 mime 按实际字节判定」补到 VPS 上的 stub_worker.py（幂等）。"""
import io
import sys

P = "/opt/weaveora/stub_worker.py"
s = io.open(P, encoding="utf-8").read()

OLD = (
    '                    media = [(o[0], "image/png",\n'
    '                              int(params.get("width") or 1024), int(params.get("height") or 1024), None)\n'
    '                             for o in outs]'
)
NEW = (
    '                    media = [(o[0], o[1],\n'
    '                              int(params.get("width") or 1024), int(params.get("height") or 1024), None)\n'
    '                             for o in outs]'
)

if OLD not in s:
    if NEW in s:
        print("already patched")
        sys.exit(0)
    print("FAIL: 找不到云图片 media 拼装处")
    sys.exit(1)

io.open(P, "w", encoding="utf-8", newline="\n").write(s.replace(OLD, NEW, 1))
print("patched stub_worker.py (云图片 mime 按字节判定)")
