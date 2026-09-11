#!/usr/bin/env python3
"""对照实验：用 CosyVoice 自带的官方参考音跑 zero-shot，看输出音色是否贴近参考音。

若官方参考音的克隆结果也不贴近它自己 → 说明零样本调用有问题（我的参数/路径）；
若贴近 → 说明用户的样本本身不适合克隆（杂音/非人声/文本不匹配）。
"""
import io
import json
import urllib.request
import wave

import numpy as np

TTS = "http://127.0.0.1:8091/tts"
REF = "/data/audio/CosyVoice/asset/zero_shot_prompt.wav"
REF_TEXT = "希望你以后能够做的比我还好唷。"
FEAT = "目标已锁定，准备击落。"


def stats_wav_bytes(raw):
    with wave.open(io.BytesIO(raw), "rb") as w:
        sr, n = w.getframerate(), w.getnframes()
        pcm = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    return _stats(pcm, sr, n)


def stats_wav_file(path):
    import soundfile as sf
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    pcm = data.mean(axis=1)
    return _stats(pcm, sr, len(pcm))


def _stats(pcm, sr, n):
    if n == 0:
        return 0.0, 0.0, 0.0, 0.0
    seg = pcm[: min(n, 1 << 16)]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    freqs = np.fft.rfftfreq(len(seg), 1.0 / sr)
    cen = float((spec * freqs).sum() / (spec.sum() + 1e-9))
    # 过零率（清浊/噪声的粗代理）
    zcr = float(np.mean(np.abs(np.diff(np.sign(seg))) > 0))
    return n / sr, float(np.sqrt((pcm ** 2).mean())), cen, zcr


def tts(text, voice, prompt_text=""):
    body = {"text": text, "voice": voice, "speed": 1.0, "target_sec": 0, "seed": 4242}
    if prompt_text:
        body["prompt_text"] = prompt_text
    req = urllib.request.Request(TTS, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()


def show(label, d, rms, cen, zcr):
    print("  %-26s %6.2fs rms=%.4f 质心=%6.0fHz zcr=%.3f" % (label, d, rms, cen, zcr))


print("== 参考音（官方 zero_shot_prompt.wav）==")
show("官方参考音", *stats_wav_file(REF))

print("== 用它做 zero-shot（带正确文本）==")
out = tts(FEAT, REF, REF_TEXT)
show("clone(官方参考音)", *stats_wav_bytes(out))
with open("/data/audio/diag_clone_official.wav", "wb") as f:
    f.write(out)

print("== 同一句话用内置音色（对照）==")
for v in ("中文女", "中文男"):
    o = tts(FEAT, v)
    show("内置 " + v, *stats_wav_bytes(o))
