#!/usr/bin/env python3
"""验证新的语速语义：
  1) 默认（ALIGN=0）—— 任何 target_sec 都不改语速
  2) seed 固定 —— 同一文案两次输出完全一致（可复现）
"""
import hashlib
import json
import urllib.request

URL = "http://127.0.0.1:8091/tts"
TEXT = "夜色温柔，愿你安然入睡。"


def post(text, voice="中文女", target=0.0, seed=None):
    body = {"text": text, "voice": voice, "speed": 1.0, "target_sec": target}
    if seed is not None:
        body["seed"] = seed
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        data = r.read()
        return data, int(r.headers.get("X-Duration-Ms") or 0)


def h(b):
    return hashlib.sha256(b).hexdigest()[:10]


print("1) 默认不改语速（target_sec 只做比对报告）")
for t in (0, 3.0, 10.0):
    d, ms = post(TEXT, target=t)
    print("   target=%-5s -> %.2fs  %s" % (t, ms / 1000.0, h(d)))

print("\n2) seed 固定 → 可复现")
a, ma = post(TEXT, seed=20260911)
b, mb = post(TEXT, seed=20260911)
c, mc = post(TEXT, seed=20260912)
print("   seed=20260911 #1  %.2fs %s" % (ma / 1000.0, h(a)))
print("   seed=20260911 #2  %.2fs %s   %s" % (mb / 1000.0, h(b), "一致 ✓" if h(a) == h(b) else "不一致 ✗"))
print("   seed=20260912     %.2fs %s   %s" % (mc / 1000.0, h(c), "不同种子不同输出 ✓" if h(c) != h(a) else "同种子结果相同 ✗"))

print("\n3) 不传 seed → 每次不同（自然采样）")
d1, _ = post(TEXT)
d2, _ = post(TEXT)
print("   %s vs %s  %s" % (h(d1), h(d2), "不同 ✓" if h(d1) != h(d2) else "相同"))
