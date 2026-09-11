#!/usr/bin/env python3
"""测用户自己的参考音：声纹相似度 + 转写文本对克隆的影响。

用法：把用户的参考音放到 /mnt/d/model/_dl/ref_A.wav（或传路径），运行本脚本。
"""
import json
import os
import sys
import urllib.request

import numpy as np
import onnxruntime
import torch
import torchaudio
import torchaudio.compliance.kaldi as kaldi

CAMP = "/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B/campplus.onnx"
_sess = onnxruntime.InferenceSession(CAMP, providers=["CPUExecutionProvider"])
_in = _sess.get_inputs()[0].name
FEAT = "目标已锁定，准备击落。"
REF = sys.argv[1] if len(sys.argv) > 1 else "/mnt/d/model/_dl/ref_A.wav"


def load16k(path):
    wav, sr = torchaudio.load(path, backend="soundfile")
    wav = wav.mean(dim=0, keepdim=True)
    if sr != 16000:
        wav = torchaudio.transforms.Resample(sr, 16000)(wav)
    return wav


def spk_emb(path_or_bytes):
    if isinstance(path_or_bytes, (bytes, bytearray)):
        import tempfile
        fd, p = tempfile.mkstemp(suffix=".wav")
        with os.fdopen(fd, "wb") as f:
            f.write(path_or_bytes)
        try:
            wav = load16k(p)
        finally:
            os.remove(p)
    else:
        wav = load16k(path_or_bytes)
    feat = kaldi.fbank(wav, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    emb = _sess.run(None, {_in: feat.unsqueeze(dim=0).cpu().numpy()})[0].flatten()
    return emb / (np.linalg.norm(emb) + 1e-9)


def tts(text, voice, prompt_text=""):
    body = {"text": text, "voice": voice, "speed": 1.0, "target_sec": 0, "seed": 4242}
    if prompt_text:
        body["prompt_text"] = prompt_text
    req = urllib.request.Request("http://127.0.0.1:8091/tts", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()


# 参考音信息
info = torchaudio.info(REF)
dur = info.num_frames / info.sample_rate
e_ref = spk_emb(REF)
print("参考音：%s  %.2fs  %dHz  %dch" % (os.path.basename(REF), dur, info.sample_rate, info.num_channels))
print("参考音自相似度（应≈1.0）= %.3f" % float(np.dot(e_ref, e_ref)))

# 转写文本对克隆的影响
VARIANTS = [
    ("带 whisper 转写", os.environ.get("PT", "")),
    ("空 prompt_text", ""),
    ("带你说过的那句", "前方战机，请快速离开我国领海上空，否则击落。"),
]
print()
print("%-18s %8s %10s" % ("变体", "相似度", "时长(s)"))
for label, pt in VARIANTS:
    try:
        raw = tts(FEAT, REF, pt)
    except Exception as e:
        print("%-18s FAIL %s" % (label, str(e)[:60]))
        continue
    import io
    import wave
    with wave.open(io.BytesIO(raw), "rb") as w:
        d = w.getnframes() / w.getframerate()
    sim = float(np.dot(e_ref, spk_emb(raw)))
    print("%-18s %8.3f %10.2f   %s" % (label, sim, d, "➜ 像参考音" if sim > 0.5 else "➜ 不像！"))

print()
print("对照：内置音色与参考音的相似度")
for v in ("中文女", "中文男"):
    raw = tts(FEAT, v)
    print("  内置%-4s = %.3f" % (v, float(np.dot(e_ref, spk_emb(raw)))))
