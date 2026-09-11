#!/usr/bin/env python3
"""决定性实验：用 CosyVoice 自带的声纹模型 campplus 算「输出 vs 参考音」的说话人相似度。

  - sim(参考音, 克隆输出) 高  → 零样本确实把音色搬过去了
  - sim(参考音, 内置音色输出) 低 → 对照

campplus 的用法与 CosyVoiceFrontEnd._extract_spk_embedding 完全一致
（16k → kaldi fbank 80 维 → 去均值 → onnx 推理）。
"""
import io
import json
import os
import urllib.request
import wave

import numpy as np
import onnxruntime
import torch
import torchaudio
import torchaudio.compliance.kaldi as kaldi

SAVE = "/data/audio/diag"
os.makedirs(SAVE, exist_ok=True)
CAMP = "/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B/campplus.onnx"
_sess = onnxruntime.InferenceSession(CAMP, providers=["CPUExecutionProvider"])
_in = _sess.get_inputs()[0].name


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


def cos(a, b):
    return float(np.dot(a, b))


def tts(text, voice, prompt_text="", out=None):
    body = {"text": text, "voice": voice, "speed": 1.0, "target_sec": 0, "seed": 4242}
    if prompt_text:
        body["prompt_text"] = prompt_text
    req = urllib.request.Request("http://127.0.0.1:8091/tts", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        raw = r.read()
    if out:
        with open(out, "wb") as f:
            f.write(raw)
    return raw


FEAT = "目标已锁定，准备击落。"

print("== 1. 参考音自身的声纹 ==")
OFFICIAL = "/data/audio/CosyVoice/asset/zero_shot_prompt.wav"
e_off = spk_emb(OFFICIAL)
print("  官方参考音 已取声纹")

print("== 2. 用官方参考音做克隆（带正确文本）==")
o1 = tts(FEAT, OFFICIAL, "希望你以后能够做的比我还好唷。", os.path.join(SAVE, "clone_official.wav"))
e_c1 = spk_emb(o1)
print("  相似度 官方参考音 vs 克隆输出 = %.3f" % cos(e_off, e_c1))

print("== 3. 对照：同一句话用内置音色 ==")
for v in ("中文女", "中文男"):
    o = tts(FEAT, v, "", os.path.join(SAVE, "builtin_%s.wav" % v))
    e = spk_emb(o)
    print("  相似度 官方参考音 vs 内置%-4s = %.3f" % (v, cos(e_off, e)))

print()
print("读法：若「参考音 vs 克隆输出」明显高于「参考音 vs 内置音色」，说明零样本生效。")
