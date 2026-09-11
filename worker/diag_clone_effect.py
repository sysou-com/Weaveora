#!/usr/bin/env python3
"""诊断：克隆音色到底有没有生效。

同一句话分别用：
  a) 用户的参考音 A（歼36）+ 它的 prompt_text  → zero-shot
  b) 用户的参考音 B（B21） + "人"              → zero-shot
  c) 内置 中文女（SFT）
  d) 内置 中文男（SFT）
然后比 频谱质心（音色亮度的粗略代理）。
若 a/b 与 c/d 几乎一样 → 克隆没生效；若接近各自参考音且彼此不同 → 克隆生效。
"""
import io
import json
import os
import sys
import urllib.request
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

FEAT = "目标已锁定，准备击落。"


def ref_bytes(storage_key):
    """经 worker 内部通道按存储 key 取参考音（复用 comfy_client 的实现）"""
    import comfy_client as c
    return c.fetch_reference_bytes(storage_key)[0]


def tts(text, voice, prompt_text=""):
    body = {"text": text, "voice": voice, "speed": 1.0, "target_sec": 0, "seed": 4242}
    if prompt_text:
        body["prompt_text"] = prompt_text
    req = urllib.request.Request("http://127.0.0.1:8091/tts", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()


def stats(raw):
    with wave.open(io.BytesIO(raw), "rb") as w:
        sr, n = w.getframerate(), w.getnframes()
        pcm = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    if n == 0:
        return 0.0, 0.0, 0.0
    seg = pcm[: min(n, 1 << 16)]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    freqs = np.fft.rfftfreq(len(seg), 1.0 / sr)
    cen = float((spec * freqs).sum() / (spec.sum() + 1e-9))
    return n / sr, float(np.sqrt((pcm ** 2).mean())), cen


def save(name, raw):
    p = os.path.join(r"D:\model\_dl", name)
    with open(p, "wb") as f:
        f.write(raw)
    return p


REFS = {
    "A 歼36": (os.environ.get("REF_A", ""), "显参战进,请快速离开火光,灵海上岗保折进落。"),
    "B B21": (os.environ.get("REF_B", ""), "人"),
}

print("%-16s %8s %8s %10s" % ("样本", "时长(s)", "rms", "频谱质心"))
ref_cent = {}
for label, (sk, _pt) in REFS.items():
    if not sk:
        continue
    raw = ref_bytes(sk)
    d, r, c = stats(raw)
    ref_cent[label] = c
    save("ref_%s.wav" % label.split()[0], raw)
    print("%-16s %8.2f %8.4f %10.0f" % ("参考音 " + label, d, r, c))

print()
print("%-16s %8s %8s %10s" % ("生成结果", "时长(s)", "rms", "频谱质心"))
cases = []
for label, (sk, pt) in REFS.items():
    if sk:
        cases.append(("clone " + label, "REF:" + sk, pt))
cases.append(("内置 中文女", "中文女", ""))
cases.append(("内置 中文男", "中文男", ""))

out = {}
for label, voice, pt in cases:
    if voice.startswith("REF:"):
        # zero-shot：先把参考音落到本地临时文件
        raw = ref_bytes(voice[4:])
        tmp = os.path.join(os.environ.get("TEMP", r"D:\model\_dl"), "ref_tmp_%d.wav" % abs(hash(label)))
        with open(tmp, "wb") as f:
            f.write(raw)
        v = tmp
    else:
        v = voice
    try:
        raw = tts(FEAT, v, pt)
    except Exception as e:
        print("%-16s FAIL %s" % (label, str(e)[:80]))
        continue
    d, r, c = stats(raw)
    out[label] = c
    save("out_%s.wav" % label.replace(" ", "_"), raw)
    print("%-16s %8.2f %8.4f %10.0f" % (label, d, r, c))
    if v.startswith(os.environ.get("TEMP", "___")):
        try:
            os.remove(v)
        except OSError:
            pass

print()
print("结论参考：")
for k, c in out.items():
    near = ""
    for rl, rc in ref_cent.items():
        if abs(c - rc) < 200:
            near = "（接近参考音 %s）" % rl
    print("  %-16s 质心=%.0f %s" % (k, c, near))
