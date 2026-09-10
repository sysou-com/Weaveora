#!/usr/bin/env python3
"""Weaveora 自托管 TTS 服务（P7 配音）——仅标准库 HTTP，模型在进程内常驻。

双模型分工（8GB 卡上**同时只保留一个**，切换时先卸载）：
  - CosyVoice2-0.5B（`WEAVEORA_COSYVOICE_MODEL`）：zero-shot 音色克隆
  - CosyVoice-300M-SFT（`WEAVEORA_COSYVOICE_SFT_MODEL`）：带 spk2info 的内置音色（7 个）

启动（GPU 机器）：
  export WEAVEORA_COSYVOICE_DIR=/data/audio/CosyVoice
  export WEAVEORA_COSYVOICE_MODEL=pretrained_models/CosyVoice2-0.5B
  export WEAVEORA_COSYVOICE_SFT_MODEL=pretrained_models/CosyVoice-300M-SFT
  python3 tts_server.py 8091

接口：
  POST /tts  {"text":"...", "voice":"中文女", "speed":1.0, "target_sec":5}
    → audio/wav 字节；响应头 X-Duration-Ms 给时长
  voice 取值（按序判定）：
    1. 存在的 wav 路径        → CosyVoice2 zero-shot 克隆
    2. SFT 模型的 spk2info 名 → CosyVoice-300M-SFT inference_sft（中文女/中文男/英文女/英文男/日语男/韩语女/粤语女）
    3. 其他                   → 回退仓库自带 asset/zero_shot_prompt.wav 做 zero-shot（并打日志）
  target_sec>0 时按目标时长对齐：首次结果偏差 >25% 就按比例修语速重合成一次。
"""
import io
import json
import os
import sys
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COSY_DIR = os.environ.get("WEAVEORA_COSYVOICE_DIR", "/data/audio/CosyVoice")
# zero-shot 克隆用（无内置音色）
MODEL_DIR = os.environ.get("WEAVEORA_COSYVOICE_MODEL", "pretrained_models/CosyVoice2-0.5B")
# 内置音色用（v1 结构：cosyvoice.yaml + speech_tokenizer_v1.onnx + spk2info.pt）
SFT_DIR = os.environ.get("WEAVEORA_COSYVOICE_SFT_MODEL", "pretrained_models/CosyVoice-300M-SFT")
DEFAULT_VOICE = os.environ.get("WEAVEORA_TTS_DEFAULT_VOICE", "中文女")
# 既不是内置 spk、也不是参考音频路径时的兜底参考音频（CosyVoice2 zero-shot）
_FALLBACK_PROMPT_WAV = os.environ.get("WEAVEORA_TTS_PROMPT_WAV",
                                      os.path.join(COSY_DIR, "asset", "zero_shot_prompt.wav"))
_FALLBACK_PROMPT_TEXT = os.environ.get(
    "WEAVEORA_TTS_PROMPT_TEXT", "希望你以后能够做的比我还好唷。")

_lock = threading.Lock()
# 8GB 卡：只保留一个模型实例。key: "v2"(zero-shot) / "sft"(内置音色)
_models = {}
# 预热：服务启动即在后台加载模型并跑一次小样推理，避免首个任务等 1-3 分钟
PRELOAD = os.environ.get("WEAVEORA_TTS_PRELOAD", "1").lower() not in ("0", "false", "no")
_state = {"warm": False, "kind": None, "spks": [], "warmed_kind": None}


def _resolve(p):
    return p if os.path.isabs(p) else os.path.join(COSY_DIR, p)


def _sft_spks():
    """直接读 SFT 的 spk2info.pt 拿内置音色名。

    只在需要判定路由时读一个 10KB 的文件，**避免为了判断而加载整个模型**。
    """
    path = os.path.join(_resolve(SFT_DIR), "spk2info.pt")
    try:
        import torch
        return list(torch.load(path, map_location="cpu").keys())
    except Exception:
        return []


def _load(kind):
    """懒加载模型。kind='v2'（zero-shot）/ 'sft'（内置音色）。"""
    with _lock:
        if _models.get(kind) is not None:
            return _models[kind]
        sys.path.insert(0, COSY_DIR)
        sys.path.insert(0, os.path.join(COSY_DIR, "third_party", "Matcha-TTS"))
        if _models:
            print("[tts] 卸载 %s，切换为 %s（单卡互斥）" % (list(_models), kind), flush=True)
            _models.clear()
            import gc
            gc.collect()
            try:
                import torch
                torch.cuda.empty_cache()
            except Exception:
                pass
        if kind == "sft":
            from cosyvoice.cli.cosyvoice import CosyVoice  # noqa
            path = _resolve(SFT_DIR)
            m = CosyVoice(path, load_jit=False, load_trt=False, fp16=False)
        else:
            from cosyvoice.cli.cosyvoice import CosyVoice2  # noqa
            path = _resolve(MODEL_DIR)
            m = CosyVoice2(path, load_jit=False, load_trt=False, fp16=False)
        _models[kind] = m
        _state["kind"] = kind
        try:
            _state["spks"] = list(m.list_available_spks())
        except Exception:
            _state["spks"] = []
        print("[tts] %s 已加载: %s (sr=%s, spks=%d)"
              % (kind, path, getattr(m, "sample_rate", "?"), len(_state["spks"])), flush=True)
        return m


def _tensors_to_wav(chunks, sample_rate):
    """把模型输出的 tensor 列表写为 16bit PCM wav 字节。

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
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(int16.tobytes())
    return buf.getvalue()


def _render(text, v, spd):
    """按 voice 路由跑一次推理 → (chunks, sample_rate, used_label)。"""
    if os.path.exists(v):
        # 1) 参考音频路径 → CosyVoice2 zero-shot 克隆
        #    注意：inference_zero_shot 第 3 参是 **wav 文件路径**（内部 frontend_* →
        #    load_wav → torchaudio.load），传张量会报 "Invalid file: tensor([...])"。
        m = _load("v2")
        return list(m.inference_zero_shot(text, "", v, stream=False, speed=spd)), \
            m.sample_rate, "zero-shot:%s" % os.path.basename(v)
    spks = _sft_spks()
    if v in spks:
        # 2) 内置音色 → CosyVoice-300M-SFT
        m = _load("sft")
        return list(m.inference_sft(text, v, stream=False, speed=spd)), \
            m.sample_rate, "sft:%s" % v
    # 3) 兜底：CosyVoice2 + 仓库自带参考音频
    if os.path.exists(_FALLBACK_PROMPT_WAV):
        print("[tts] voice '%s' 非内置(%s)且非路径，回退参考音频 %s" % (v, "/".join(spks) or "无", _FALLBACK_PROMPT_WAV),
              flush=True)
        m = _load("v2")
        return list(m.inference_zero_shot(text, _FALLBACK_PROMPT_TEXT, _FALLBACK_PROMPT_WAV,
                                          stream=False, speed=spd)), \
            m.sample_rate, "fallback:%s" % os.path.basename(_FALLBACK_PROMPT_WAV)
    raise RuntimeError("音色不存在：既不是内置音色(%s)，也不是参考音频路径，且无回退 prompt"
                       % ("/".join(spks) or "SFT 模型缺失"))


def synthesize(text, voice, speed, target_sec):
    v = (voice or DEFAULT_VOICE).strip()
    spd = max(0.5, min(2.0, float(speed or 1.0)))
    tgt = float(target_sec or 0)
    t0 = time.time()

    chunks, sr, used = _render(text, v, spd)
    wav = _tensors_to_wav(chunks, sr)
    ms = int(round((len(wav) - 44) / 2.0 / float(sr) * 1000))

    # 目标时长对齐（配音贴合镜头）：偏差 >25% 时按比例修语速重合成一次
    if tgt > 1.0 and ms > 0:
        want = tgt * 1000.0
        if ms < want * 0.75 or ms > want * 1.25:
            spd2 = max(0.5, min(2.0, spd * (ms / want)))
            if abs(spd2 - spd) > 0.02:
                print("[tts] 时长对齐: %.2fs 目标 %.2fs，语速 %.2f -> %.2f 重合成"
                      % (ms / 1000.0, tgt, spd, spd2), flush=True)
                chunks, sr, used = _render(text, v, spd2)
                wav = _tensors_to_wav(chunks, sr)
                ms = int(round((len(wav) - 44) / 2.0 / float(sr) * 1000))
                spd = spd2

    print("[tts] text=%d字 voice=%s[%s] speed=%.2f target=%.1f -> %.1fs in %.1fs"
          % (len(text), v, used, spd, tgt, ms / 1000.0, time.time() - t0), flush=True)
    return wav, ms


def _warm():
    try:
        synthesize("你好，织影已就绪。", DEFAULT_VOICE, 1.0, 0)
        _state["warm"] = True
        _state["warmed_kind"] = _state["kind"]
        print("[tts] preload done (warm=%s kind=%s)" % (_state["warm"], _state["kind"]), flush=True)
    except Exception as e:
        print("[tts] preload failed, will lazy-load on first request: %s" % e, flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/health"):
            body = json.dumps({"ok": True, "loaded": bool(_models), "kind": _state["kind"],
                               "spks": _state["spks"], "sft_spks": _sft_spks(),
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
    print("[tts] listening on :%d (zero-shot=%s | sft=%s | preload=%s)"
          % (port, MODEL_DIR, SFT_DIR, PRELOAD), flush=True)
    if PRELOAD:
        threading.Thread(target=_warm, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
