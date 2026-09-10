#!/usr/bin/env python3
"""TTS 音色路由实测：7 个内置音色 + zero-shot 路径 + 未知音色兜底。

客观区分"不同音色"：算每条的时长 / RMS / 频谱质心（亮度的粗略代理）。
同文本下，男声的频谱质心应明显低于女声。
"""
import hashlib
import json
import urllib.request
import wave

import numpy as np

URL = "http://127.0.0.1:8091/tts"
TEXT = "夜色温柔，愿你安然入睡。"


def post(text, voice, target=0.0):
    body = json.dumps({"text": text, "voice": voice, "speed": 1.0, "target_sec": target}).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read(), r.headers.get("X-Duration-Ms")


def stats(wav_bytes):
    import io
    with wave.open(io.BytesIO(wav_bytes), "rb") as w:
        sr, n = w.getframerate(), w.getnframes()
        pcm = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    dur = n / sr
    rms = float(np.sqrt((pcm ** 2).mean())) if n else 0.0
    # 频谱质心
    if n > 2048:
        seg = pcm[: 1 << 16] * np.hanning(min(n, 1 << 16))
        spec = np.abs(np.fft.rfft(seg))
        freqs = np.fft.rfftfreq(len(seg), 1.0 / sr)
        cen = float((spec * freqs).sum() / (spec.sum() + 1e-9))
    else:
        cen = 0.0
    return dur, rms, cen


VOICES = ["中文女", "中文男", "英文女", "英文男", "日语男", "韩语女", "粤语女"]

print("%-10s %8s %8s %10s %-10s %s" % ("voice", "dur(s)", "rms", "centroid", "sha8", "status"))
results = {}
for v in VOICES:
    try:
        data, ms = post(TEXT, v)
        dur, rms, cen = stats(data)
        h = hashlib.sha256(data).hexdigest()[:8]
        results[v] = (dur, rms, cen, h)
        print("%-10s %8.2f %8.4f %10.0f %-10s OK  (hdr=%sms, %d B)" % (v, dur, rms, cen, h, ms, len(data)))
    except Exception as e:
        print("%-10s %s" % (v, "FAIL: %s" % str(e)[:80]))

# 唯一性：7 条音频的 sha 应互不相同
shas = [r[3] for r in results.values()]
print("\n音色互异: %s (%d 个不同 / %d 条)" % ("是" if len(set(shas)) == len(shas) else "否！有重复",
                                     len(set(shas)), len(shas)))

# 男/女对比（质心应差得明显）
if "中文女" in results and "中文男" in results:
    cw, cm = results["中文女"][2], results["中文男"][2]
    print("男女声质心: 中文女=%.0fHz 中文男=%.0fHz  差=%.0fHz" % (cw, cm, abs(cw - cm)))

# 未知音色 → 应走 zero-shot 兜底（不报错）
try:
    data, ms = post(TEXT, "不存在的音色XYZ")
    print("\n未知音色兜底: OK (%d B, hdr=%sms)" % (len(data), ms))
except Exception as e:
    print("\n未知音色兜底: FAIL %s" % str(e)[:100])

# zero-shot 参考音频路径 → 应切到 CosyVoice2
try:
    data, ms = post(TEXT, "/data/audio/CosyVoice/asset/zero_shot_prompt.wav")
    dur, rms, cen = stats(data)
    print("zero-shot 路径: OK (%.2fs, %d B)  —— 会触发模型切换" % (dur, len(data)))
    print("\n切换后 health:")
    with urllib.request.urlopen("http://127.0.0.1:8091/health", timeout=10) as r:
        print("  " + r.read().decode())
except Exception as e:
    print("zero-shot 路径: FAIL %s" % str(e)[:150])

# 目标时长对齐
try:
    data, ms = post(TEXT, "中文女", target=10.0)
    print("\n时长对齐(target=10s): 实际 %.2fs (hdr=%sms)" % (stats(data)[0], ms))
except Exception as e:
    print("\n时长对齐: FAIL %s" % str(e)[:100])
