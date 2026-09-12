#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 cfg= 透传补到 VPS 上的 stub_worker.py（云执行路径要拿到 schema 映射）。"""
import io
import sys

P = "/opt/weaveora/stub_worker.py"
s = io.open(P, encoding="utf-8").read()

if "cfg=icfg" in s and "cfg=vcfg" in s:
    print("already patched")
    sys.exit(0)

a = '''                outs = cloud.generate_motion_via_replicate(
                    payload, vcfg.get("apiKey") or "", vcfg.get("model") or "",'''
a2 = '''                outs = cloud.generate_motion_via_replicate(
                    payload, vcfg.get("apiKey") or "", vcfg.get("model") or "",
                    cfg=vcfg,'''
b = '''                    outs = cloud.replicate_image(
                        payload, icfg.get("apiKey") or "", icfg.get("model") or "",'''
b2 = '''                    outs = cloud.replicate_image(
                        payload, icfg.get("apiKey") or "", icfg.get("model") or "",
                        cfg=icfg,'''

ok = False
if a in s:
    s = s.replace(a, a2, 1)
    ok = True
if b in s:
    s = s.replace(b, b2, 1)
    ok = True
if not ok:
    print("FAIL: 找不到调用点，未改动")
    sys.exit(1)

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("patched stub_worker.py (cfg 透传)")
