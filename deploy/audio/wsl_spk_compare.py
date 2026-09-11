#!/usr/bin/env python3
"""比对：用户实际生成的配音 vs 用户的音色参考音（声纹余弦相似度）。
用法：python spk_compare.py <参考音> <生成产物> [内置音色做对照]
"""
import sys

import numpy as np
import onnxruntime
import torch
import torchaudio
import torchaudio.compliance.kaldi as kaldi

CAMP = "/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B/campplus.onnx"
_sess = onnxruntime.InferenceSession(CAMP, providers=["CPUExecutionProvider"])
_in = _sess.get_inputs()[0].name


def emb(path):
    wav, sr = torchaudio.load(path, backend="soundfile")
    wav = wav.mean(dim=0, keepdim=True)
    if sr != 16000:
        wav = torchaudio.transforms.Resample(sr, 16000)(wav)
    feat = kaldi.fbank(wav, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    v = _sess.run(None, {_in: feat.unsqueeze(dim=0).cpu().numpy()})[0].flatten()
    return v / (np.linalg.norm(v) + 1e-9)


a, b = emb(sys.argv[1]), emb(sys.argv[2])
print("参考音   : %s" % sys.argv[1])
print("生成产物 : %s" % sys.argv[2])
print("声纹相似度 = %.3f   %s" % (float(np.dot(a, b)),
      "✅ 同一个人（克隆生效）" if float(np.dot(a, b)) > 0.5 else "❌ 不像"))
