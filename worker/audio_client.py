#!/usr/bin/env python3
"""自托管音频客户端（P7）：配音 CosyVoice / 配乐音乐生成。

服务地址（GPU 机器本机）：
  WEAVEORA_TTS_URL    默认 http://127.0.0.1:8091   （deploy/audio/tts_server.py）
  WEAVEORA_MUSIC_URL  默认 http://127.0.0.1:8092   （deploy/audio/music_server.py）
仅依赖标准库。
"""
import json
import os
import urllib.error
import urllib.request

TTS_URL = os.environ.get("WEAVEORA_TTS_URL", "http://127.0.0.1:8091").rstrip("/")
MUSIC_URL = os.environ.get("WEAVEORA_MUSIC_URL", "http://127.0.0.1:8092").rstrip("/")


class AudioError(Exception):
    pass


def _post(url, body, timeout):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            mime = (r.headers.get("Content-Type") or "audio/wav").split(";")[0]
            ms = r.headers.get("X-Duration-Ms")
            return data, mime, (int(ms) if ms and ms.isdigit() else None)
    except urllib.error.HTTPError as e:
        raise AudioError("%s -> %s %s" % (url, e.code, e.read()[:300].decode(errors="replace")))
    except Exception as e:
        raise AudioError("%s 不可达: %s" % (url, e))


def tts(payload):
    """配音：返回 (bytes, mime, duration_ms?)。payload: text/voice/speed/target_sec。"""
    text = (payload.get("text") or "").strip()
    if not text:
        raise AudioError("voice 任务缺少 text")
    body = {
        "text": text,
        "voice": payload.get("voice") or "中文女",
        "speed": float(payload.get("speed") or 1.0),
        "target_sec": float(payload.get("target_sec") or 0),
    }
    timeout = int(os.environ.get("WEAVEORA_TTS_TIMEOUT", "900"))
    return _post(TTS_URL + "/tts", body, timeout)


def music(payload):
    """配乐：返回 (bytes, mime, duration_ms?)。payload: prompt/duration_sec/seed。"""
    prompt = (payload.get("prompt") or "").strip() or "cinematic instrumental score, emotional, no vocals"
    try:
        dur = float(payload.get("duration_sec") or 30)
    except (TypeError, ValueError):
        dur = 30.0
    body = {"prompt": prompt, "duration_sec": max(5.0, min(180.0, dur)),
            "seed": int(payload.get("seed") or 0)}
    timeout = int(os.environ.get("WEAVEORA_MUSIC_TIMEOUT", "1800"))
    data, mime, ms = _post(MUSIC_URL + "/music", body, timeout)
    return data, mime, (ms or int(body["duration_sec"] * 1000))
