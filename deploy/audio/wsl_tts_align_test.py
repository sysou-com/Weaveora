#!/usr/bin/env python3
"""验证 target_sec 对齐语义：只加速、不慢放。"""
import json
import urllib.request

URL = "http://127.0.0.1:8091/tts"
TEXT = "夜色温柔，愿你安然入睡。"          # 约 2.0~2.5s 的语句


def post(text, voice, target):
    body = json.dumps({"text": text, "voice": voice, "speed": 1.0, "target_sec": target}).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return len(r.read()), int(r.headers.get("X-Duration-Ms") or 0)


print("基线（target=0，自由时长）:")
n, ms = post(TEXT, "中文女", 0)
base = ms
print("  %d B, %.2fs\n" % (n, ms / 1000.0))

print("旁白远短于镜头（target=10s）→ 应保持 ~1.0x、不慢放:")
n, ms = post(TEXT, "中文女", 10.0)
print("  %.2fs (基线 %.2fs, 比值 %.2f)\n" % (ms / 1000.0, base / 1000.0, ms / base))

print("旁白略短于镜头（target=2.6s，差 ~10%）→ 应在容差内不动:")
n, ms = post(TEXT, "中文女", base / 1000.0 * 1.10)
print("  %.2fs\n" % (ms / 1000.0))

print("旁白长于镜头（target=1.2s）→ 应加速:")
n, ms = post(TEXT, "中文女", 1.2)
print("  %.2fs (基线 %.2fs, 比值 %.2f)\n" % (ms / 1000.0, base / 1000.0, ms / base))

print("长文本 + 大 target（防回归：以前这里会 0.5x）:")
long_text = "这是一段刻意写长的旁白，用来检查当镜头时长远远超过语句自然长度时，服务会不会把语音慢放得很难听。"
n, ms = post(long_text, "中文女", 30.0)
print("  %.2fs" % (ms / 1000.0))
