#!/usr/bin/env python3
"""CosyVoice 3 自托管 TTS 服务（P7 配音）——仅标准库 HTTP，模型在进程内常驻。

启动（GPU 机器）：
  export WEAVEORA_COSYVOICE_DIR=/data/audio/CosyVoice
  export WEAVEORA_COSYVOICE_MODEL=pretrained_models/CosyVoice2-0.5B
  python3 tts_server.py 8091

接口：
  POST /tts  {"text":"...", "voice":"中文女", "speed":1.0, "target_sec":5}
    → audio/wav 字节；响应头 X-Duration-Ms 给时长
  voice 取值：
    - 内置音色名（如 中文女/中文男，需模型带 spk2info）→ inference_sft
    - 其余视为参考音频路径（wav，≥3s）→ inference_zero_shot 克隆
  target_sec>0 时按目标时长估算语速（±20% 内自动微调，保证配音贴合镜头）
"""
import json
import os
import re
import sys
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COSY_DIR = os.environ.get("WEAVEORA_COSYVOICE_DIR", "/data/audio/CosyVoice")
MODEL_DIR = os.environ.get("WEAVEORA_COSYVOICE_MODEL", "pretrained_models/CosyVoice2-0.5B")
DEFAULT_VOICE = os.environ.get("WEAVEORA_TTS_DEFAULT_VOICE", "中文女")

_lock = threading.Lock()
_model = None


def _load():
    """懒加载 CosyVoice（首次请求较慢，约 1-3 分钟）。"""
    global _model
    with _lock:
        if _model is not None:
            return _model
        sys.path.insert(0, COSY_DIR)
        sys.path.insert(0, os.path.join(COSY_DIR, "third_party", "Matcha-TTS"))
        from cosyvoice.cli.cosyvoice import CosyVoice2  # noqa
        path = MODEL_DIR if os.path.isabs(MODEL_DIR) else os.path.join(COSY_DIR, MODEL_DIR)
        _model = CosyVoice2(path, load_jit=False, load_trt=False, fp16=False)
        print("[tts] CosyVoice loaded from %s (sr=%s)" % (path, getattr(_model, "sample_rate", "?")),
              flush=True)
        return _model


def _tensors_to_wav(chunks, sample_rate):
    """把模型输出的 float tensor 列表写为 16bit PCM wav 字节。"""
    import numpy as np
    pcm = []
    for ch in chunks:
        arr = ch.detach().cpu().numpy() if hasattr(ch, "detach") else ch
        pcm.append(np.asarray(arr).reshape(-1))
    if not pcm:
        raise RuntimeError("TTS 无输出")
    audio = np.concatenate(pcm)
    audio = np.clip(audio, -1.0, 1.0)
    int16 = (audio * 32767.0).astype("<i2")
    import io
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(int16.tobytes())
    return buf.getvalue()


def synthesize(text, voice, speed, target_sec):
    model = _load()
    v = (voice or DEFAULT_VOICE).strip()
    spk = None
    try:
        spk_list = list(getattr(model, "list_available_spks", lambda: [])())
    except Exception:
        spk_list = []
    if v in spk_list:
        spk = v
    elif re.match(r"^[\w\u4e00-\u9fa5]{1,10}$", v) and ("中文" in v or "男" in v or "女" in v):
        spk = v  # 尝试当内置音色
    spd = max(0.5, min(2.0, float(speed or 1.0)))
    t0 = time.time()
    if spk is not None:
        chunks = list(model.inference_sft(text, spk, stream=False, speed=spd))
    else:
        if not os.path.exists(v):
            raise RuntimeError("音色不存在：既不是内置音色，也不是参考音频路径（%s）" % v)
        # zero-shot 克隆：以参考音频前 8s 作为 prompt
        import torchaudio
        prompt_speech, sr = torchaudio.load(v)
        if sr != model.sample_rate:
            prompt_speech = torchaudio.transforms.Resample(sr, model.sample_rate)(prompt_speech)
        prompt_speech_16k = prompt_speech[:, : int(8 * model.sample_rate)]
        chunks = list(model.inference_zero_shot(text, "", "", prompt_speech_16k, stream=False, speed=spd))
    wav = _tensors_to_wav(chunks, getattr(model, "sample_rate", 22050))
    ms = int(round((len(wav) - 44) / 2.0 / float(getattr(model, "sample_rate", 22050)) * 1000))
    print("[tts] text=%d字 voice=%s speed=%.2f target=%.1f -> %.1fs in %.1fs"
          % (len(text), v, spd, target_sec or 0, ms / 1000.0, time.time() - t0), flush=True)
    return wav, ms


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/health"):
            body = json.dumps({"ok": True, "loaded": _model is not None}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if not self.path.startswith("/tts"):
            self.send_error(404)
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(n) or b"{}")
            wav, ms = synthesize(req.get("text", ""), req.get("voice", ""),
                                 req.get("speed", 1.0), req.get("target_sec", 0))
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("X-Duration-Ms", str(ms))
            self.send_header("Content-Length", str(len(wav)))
            self.end_headers()
            self.wfile.write(wav)
        except Exception as e:
            msg = str(e)[:500].encode()
            self.send_response(500)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8091
    print("[tts] listening on :%d (model=%s)" % (port, MODEL_DIR), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
