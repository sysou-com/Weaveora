#!/usr/bin/env python3
"""P9 克隆配音链路直调测试：完全走 worker 的真实代码路径。

验证三件事：
  1. 内部通道能按**存储 key** 拉到参考音（refAssetKey，不是资产 UUID）
  2. 临时文件能被 tts_server 当作 zero-shot 参考音读取
  3. prompt_text（whisper 转写）能传进去
用法：先设好 WEAVEORA_API_BASE / WEAVEORA_WORKER_TOKEN / WEAVEORA_TTS_URL 再运行。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stub_worker as w  # noqa: E402

# 取线上那条 404 的资产（项目歼36击落B21）的存储 key
SK = os.environ.get("TEST_REF_KEY", "")
if not SK:
    print("需要设置 TEST_REF_KEY=<存储 key>")
    raise SystemExit(2)

payload = {
    "kind": "voice",
    "text": os.environ.get("TEST_TEXT", "显参战进，请快速离开火光，灵海上岗保折进落。"),
    "refAssetKey": SK,
    "refPromptText": os.environ.get("TEST_PROMPT", "显参战进,请快速离开火光,灵海上岗保折进落。"),
    "seed": 12345,
}

print("API=%s TTS=%s" % (os.environ.get("WEAVEORA_API_BASE"), os.environ.get("WEAVEORA_TTS_URL")))
print("refAssetKey=%s" % SK)
t0 = time.time()
try:
    media = w._voice_media(payload)
except Exception as e:
    print("FAIL: %s" % e)
    raise SystemExit(1)
b, mime, _w_, _h, ms = media[0]
out = os.environ.get("TEST_OUT", r"D:\model\_dl\clone_tts_test.wav")
with open(out, "wb") as f:
    f.write(b)
print("OK bytes=%d mime=%s dur=%sms elapsed=%.1fs -> %s" % (len(b), mime, ms, time.time() - t0, out))
