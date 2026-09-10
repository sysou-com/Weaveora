#!/usr/bin/env python3
"""自托管配乐生成服务（P7）——仅标准库 HTTP，模型进程内常驻。

引擎（WEAVEORA_MUSIC_ENGINE）：
  ace-step     （默认，Apache-2.0，可商用；快：数十秒出 30s）
  stable-audio （Stability AI Stable Audio Open 1.0 Community License：年营收 <$1M 可商用）

启动（GPU 机器）：
  export WEAVEORA_MUSIC_ENGINE=ace-step
  export WEAVEORA_MUSIC_CKPT=/data/audio/ACE-Step/checkpoints
  python3 music_server.py 8092

接口：POST /music {"prompt":"cinematic, emotional","duration_sec":30,"seed":123}
      → audio/wav；响应头 X-Duration-Ms。
"""
import io
import json
import os
import sys
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENGINE = os.environ.get("WEAVEORA_MUSIC_ENGINE", "ace-step").strip().lower()
CKPT = os.environ.get("WEAVEORA_MUSIC_CKPT", "/data/audio/ACE-Step/checkpoints")
SAMPLE_RATE = 44100

_lock = threading.Lock()
_pipe = None


def _load():
    global _pipe
    with _lock:
        if _pipe is not None:
            return _pipe
        if ENGINE == "stable-audio":
            import torch
            from stable_audio_tools import get_pretrained_model
            model, cfg = get_pretrained_model(
                os.environ.get("WEAVEORA_MUSIC_MODEL", "stabilityai/stable-audio-open-1.0"))
            model = model.to("cuda").eval()
            _pipe = {"kind": "stable-audio", "model": model, "cfg": cfg,
                     "torch": torch, "target": cfg["sample_rate"]}
        else:
            from acestep.pipeline_ace_step import ACEStepPipeline
            _pipe = {"kind": "ace-step",
                     "pipe": ACEStepPipeline(checkpoint_dir=CKPT, device="cuda", dtype="bfloat16")}
        print("[music] engine=%s loaded" % ENGINE, flush=True)
        return _pipe


def _to_wav(samples, sample_rate):
    import numpy as np
    arr = np.asarray(samples).reshape(-1)
    arr = np.clip(arr, -1.0, 1.0)
    int16 = (arr * 32767.0).astype("<i2")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(int16.tobytes())
    return buf.getvalue()


def generate(prompt, duration_sec, seed):
    dur = max(5.0, min(180.0, float(duration_sec or 30)))
    t0 = time.time()
    p = _load()
    if p["kind"] == "stable-audio":
        torch = p["torch"]
        from stable_audio_tools.inference.generation import generate_diffusion_cond
        sr = p["target"]
        sample_size = int(sr * dur)
        g = torch.Generator(device="cuda").manual_seed(int(seed) & 0x7fffffff) if seed else None
        out = generate_diffusion_cond(
            p["model"], steps=int(os.environ.get("WEAVEORA_MUSIC_STEPS", "100")),
            cfg_scale=float(os.environ.get("WEAVEORA_MUSIC_CFG", "7")),
            conditioning=[{"prompt": prompt, "seconds_start": 0, "seconds_total": int(dur)}],
            sample_size=sample_size, sampler_type="dpmpp-3m-sde", device="cuda", seed=seed or None)
        audio = out[0] if isinstance(out, (list, tuple)) else out
        if hasattr(audio, "detach"):
            audio = audio.detach().cpu().float().numpy()
        if getattr(audio, "ndim", 1) > 1:
            audio = audio.mean(axis=0)
        wav = _to_wav(audio, sr)
    else:
        # ACE-Step：官方 pipeline 调用，返回 wav 路径或音频数组（随版本略有差异）
        import numpy as np
        res = p["pipe"](prompt=prompt, lyrics="", audio_duration=int(dur),
                        infer_step=int(os.environ.get("WEAVEORA_MUSIC_STEPS", "60")),
                        guidance_scale=float(os.environ.get("WEAVEORA_MUSIC_CFG", "15")),
                        save_path=None, manual_seeds=[int(seed)] if seed else None)
        if isinstance(res, str) and os.path.exists(res):
            with open(res, "rb") as f:
                wav = f.read()
        else:
            audio = np.asarray(res).reshape(-1)
            wav = _to_wav(audio, int(os.environ.get("WEAVEORA_MUSIC_SR", "48000")))
    ms = int(dur * 1000)
    print("[music] prompt=%s dur=%.1fs seed=%s -> %.1fs in %.1fs"
          % (prompt[:60], dur, seed, ms / 1000.0, time.time() - t0), flush=True)
    return wav, ms


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/health"):
            body = json.dumps({"ok": True, "engine": ENGINE, "loaded": _pipe is not None}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if not self.path.startswith("/music"):
            self.send_error(404)
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(n) or b"{}")
            wav, ms = generate(req.get("prompt", ""), req.get("duration_sec", 30), req.get("seed", 0))
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
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8092
    print("[music] listening on :%d engine=%s" % (port, ENGINE), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
