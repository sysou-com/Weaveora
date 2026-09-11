#!/usr/bin/env python3
"""交叉对比，定位克隆不像的原因。"""
import numpy as np
import onnxruntime
import soundfile as sf
import torch
import torchaudio
import torchaudio.compliance.kaldi as kaldi

CAMP = "/data/audio/CosyVoice/pretrained_models/CosyVoice2-0.5B/campplus.onnx"
BUNDLED = "/data/audio/CosyVoice/asset/zero_shot_prompt.wav"
_sess = onnxruntime.InferenceSession(CAMP, providers=["CPUExecutionProvider"])
_in = _sess.get_inputs()[0].name

FILES = {
    "用户当前参考音": "/mnt/d/model/_dl/ref_now.wav",
    "用户旧参考音": "/mnt/d/model/_dl/ref_A.wav",
    "用户生成产物": "/mnt/d/model/_dl/user_gen_out.wav",
    "自带兜底参考音": BUNDLED,
}


def emb(path):
    wav, sr = torchaudio.load(path, backend="soundfile")
    wav = wav.mean(dim=0, keepdim=True)
    if sr != 16000:
        wav = torchaudio.transforms.Resample(sr, 16000)(wav)
    feat = kaldi.fbank(wav, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    v = _sess.run(None, {_in: feat.unsqueeze(dim=0).cpu().numpy()})[0].flatten()
    return v / (np.linalg.norm(v) + 1e-9)


print("== 音频基本信息 ==")
embs = {}
for k, p in FILES.items():
    try:
        data, sr = sf.read(p, dtype="float32", always_2d=True)
        pcm = data.mean(axis=1)
        rms = float(np.sqrt((pcm ** 2).mean()))
        zcr = float(np.mean(np.abs(np.diff(np.sign(pcm))) > 0))
        print("  %-14s %5.2fs  rms=%.4f  zcr=%.3f" % (k, len(pcm) / sr, rms, zcr))
        embs[k] = emb(p)
    except Exception as e:
        print("  %-14s 读取失败: %s" % (k, e))

print()
print("== 声纹相似度矩阵 ==")
names = list(embs.keys())
print("  %-14s %s" % ("", " ".join("%9s" % n[:4] for n in names)))
for a in names:
    row = " ".join("%9.3f" % float(np.dot(embs[a], embs[b])) for b in names)
    print("  %-14s %s" % (a, row))

print()
print("判读：")
print("  用户当前参考音 vs 用户旧参考音  —— 若很低，说明两次录音很可能不是同一个人/后者是噪声")
print("  用户生成产物   vs 自带兑底参考音 —— 若很高，说明产物是兜底音色（没用上克隆）")
print("  用户生成产物   vs 用户当前参考音 —— 应 >0.5 才算克隆生效")
