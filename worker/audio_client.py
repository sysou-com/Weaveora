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


def apply_services(svc):
    """按任务下发（claim 响应）的「服务地址」覆盖配音/配乐地址。

    空值不覆盖（保持环境变量默认）；见 comfy_client.apply_services 的说明。
    """
    global TTS_URL, MUSIC_URL
    if not isinstance(svc, dict):
        return
    tts = (svc.get("tts") or {}).get("url")
    if isinstance(tts, str) and tts.strip():
        TTS_URL = tts.strip().rstrip("/")
    mus = svc.get("music") or {}
    if isinstance(mus.get("url"), str) and mus["url"].strip():
        MUSIC_URL = mus["url"].strip().rstrip("/")
    # 配乐走「本机 ComfyUI 里的 ACE-Step」时，引擎/权重名也要能配
    try:
        import comfy_client as _c
        if isinstance(mus.get("engine"), str) and mus["engine"].strip():
            # stub_worker 在任务执行时读环境变量 → 写回 env 即时生效
            os.environ["WEAVEORA_MUSIC_ENGINE"] = mus["engine"].strip()
        if isinstance(mus.get("ckpt"), str) and mus["ckpt"].strip() and hasattr(_c, "MUSIC_CKPT"):
            _c.MUSIC_CKPT = mus["ckpt"].strip()
    except Exception:
        pass
    print("[audio] 服务地址：tts=%s music=%s engine=%s"
          % (TTS_URL, MUSIC_URL, (svc.get("music") or {}).get("engine") or "(env 默认)"), flush=True)


class AudioError(Exception):
    pass


def unload(timeout=120):
    """让 TTS 服务卸载模型、归还显存（出视频前的让位动作）。

    24G 卡上 Wan2.2 14B 双专家（13.3GiB/专家）与 CosyVoice 常驻的 ~7GiB 无法共存；
    worker 在 clip 任务前调本函数，TTS 下次收到 /tts 会自动懒加载回来。
    返回服务端回包的 dict；服务不可达则抛 AudioError（调用方应只告警不阻塞）。
    """
    body = json.dumps({}).encode()
    req = urllib.request.Request(TTS_URL + "/unload", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode() or "{}")
    except Exception as e:
        raise AudioError("TTS /unload 失败: %s" % e)


def load(kind="v2", timeout=900):
    """显式预加载 TTS 模型（一般不用；懒加载已足够）。"""
    body = json.dumps({"kind": kind}).encode()
    req = urllib.request.Request(TTS_URL + "/load", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode() or "{}")
    except Exception as e:
        raise AudioError("TTS /load 失败: %s" % e)


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
    """配音：返回 (bytes, mime, duration_ms?)。payload: text/voice/speed/target_sec/seed。

    seed 非空时服务端会 torch.manual_seed，同一文案输出可复现
    （CosyVoice 是随机采样，不固定种子时同一句时长会在 1.9s~3.0s 间跳）。
    """
    text = (payload.get("text") or "").strip()
    if not text:
        raise AudioError("voice 任务缺少 text")
    body = {
        "text": text,
        "voice": payload.get("voice") or "中文女",
        "speed": float(payload.get("speed") or 1.0),
        "target_sec": float(payload.get("target_sec") or 0),
    }
    if payload.get("seed") is not None:
        body["seed"] = int(payload["seed"])
    # P9：克隆音色的样本转写文本（传给 CosyVoice 的 prompt_text，比空串明显更贴音色）
    if (payload.get("refPromptText") or "").strip():
        body["prompt_text"] = payload["refPromptText"].strip()
    # P9：参考音**字节**（base64）—— 跨系统（Windows worker / WSL 服务）传路径不可行
    if payload.get("refAudioB64"):
        body["ref_audio_b64"] = payload["refAudioB64"]
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
