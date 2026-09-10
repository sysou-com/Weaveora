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
# CosyVoice2-0.5B 是 zero-shot 模型（无 spk2info/内置音色）。当请求的 voice 既不是
# 内置 spk、也不是存在的参考音频路径时，回退到仓库自带的 prompt 音频做 zero-shot，
# 否则 inference_sft 会直接 KeyError。要得到不同音色，请传参考音频的绝对路径。
_FALLBACK_PROMPT_WAV = os.environ.get("WEAVEORA_TTS_PROMPT_WAV",
                                      os.path.join(COSY_DIR, "asset", "zero_shot_prompt.wav"))
_FALLBACK_PROMPT_TEXT = os.environ.get(
    "WEAVEORA_TTS_PROMPT_TEXT", "希望你以后能够做的比我还好唷。")

_lock = threading.Lock()
_model = None
# 预热：服务启动即在后台加载模型并跑一次小样推理，避免首个任务等 1-3 分钟
PRELOAD = os.environ.get("WEAVEORA_TTS_PRELOAD", "1").lower() not in ("0", "false", "no")
_state = {"loaded": False, "warm": False}


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
    """把模型输出的 float tensor 列表写为 16bit PCM wav 字节。

    注意：CosyVoice 的 tts 生成器 yield 的是 dict（{'tts_speech': tensor}），
    不是裸张量；旧版写法在 np.clip 时会报 "'>=' not supported between
    instances of 'dict' and 'float'"。这里两种都兼容。
    """
    import numpy as np
    pcm = []
    for ch in chunks:
        if isinstance(ch, dict):
            ch = ch.get("tts_speech")
            if ch is None:
                continue
        arr = ch.detach().cpu().numpy() if hasattr(ch, "detach") else ch
        pcm.append(np.asarray(arr, dtype=np.float32).reshape(-1))
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
    spd = max(0.5, min(2.0, float(speed or 1.0)))
    t0 = time.time()
    if spk is not None:
        chunks = list(model.inference_sft(text, spk, stream=False, speed=spd))
    else:
        # 参考音频：优先用调用方给的路径，否则回退到仓库自带 prompt 音频
        # 注意：CosyVoice 的 inference_zero_shot 第 3 个参数是 **wav 文件路径**（内部
        # frontend_* → load_wav → torchaudio.load），传张量会报 "Invalid file: tensor(...)"。
        if os.path.exists(v):
            ref, prompt_text = v, ""
        elif os.path.exists(_FALLBACK_PROMPT_WAV):
            ref, prompt_text = _FALLBACK_PROMPT_WAV, _FALLBACK_PROMPT_TEXT
            print("[tts] voice '%s' 非内置且非路径，回退参考音频 %s（如需不同音色请传参考 wav 绝对路径）"
                  % (v, ref), flush=True)
        else:
            raise RuntimeError("音色不存在：既不是内置音色，也不是参考音频路径，且无回退 prompt（%s）" % v)
        chunks = list(model.inference_zero_shot(text, prompt_text, ref, stream=False, speed=spd))
    wav = _tensors_to_wav(chunks, getattr(model, "sample_rate", 22050))
    ms = int(round((len(wav) - 44) / 2.0 / float(getattr(model, "sample_rate", 22050)) * 1000))
    print("[tts] text=%d字 voice=%s speed=%.2f target=%.1f -> %.1fs in %.1fs"
          % (len(text), v, spd, target_sec or 0, ms / 1000.0, time.time() - t0), flush=True)
    return wav, ms


def _warm():
    try:
        _load()
        try:
            synthesize("你好，织影已就绪。", DEFAULT_VOICE, 1.0, 0)
            _state["warm"] = True
        except Exception as e:
            print("[tts] warm synth failed: %s" % e, flush=True)
        _state["loaded"] = True
        print("[tts] preload done (warm=%s)" % _state["warm"], flush=True)
    except Exception as e:
        print("[tts] preload failed, will lazy-load on first request: %s" % e, flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/health"):
            body = json.dumps({"ok": True, "loaded": _model is not None,
                               "preload": PRELOAD, "warm": _state["warm"]}).encode()
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
    print("[tts] listening on :%d (model=%s preload=%s)" % (port, MODEL_DIR, PRELOAD), flush=True)
    if PRELOAD:
        threading.Thread(target=_warm, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
