"""jaw-lip（整脸音频驱动）客户端 —— 把「该镜静帧 + 该镜配音」交给 talk 服务，拿回 mp4。

服务端 = GPU 机上的 `deploy/gpu2_talk_server.py`（EchoMimicV3 整脸生成 + 我们的下颌曲线层）：
  · `jaw_gain=1.0` → 零改动（原生 EchoMimic 输出）
  · `jaw_gain>1`   → 按音频响度包络把"下半脸"再往下拉一点（喊叫更开，1.25 是建议增强档）

为什么单独一个 client 而不是塞进 comfy_client：
  · talk 不跑 ComfyUI，而是 GPU 机上一个独立 HTTP 服务（独立 venv，避免污染 LatentSync 环境）；
  · 但要复用 comfy_client 的取素材/拼配音/探测视频元信息能力（同一套存储与 ffmpeg）。
"""
import base64
import json
import os
import urllib.error
import urllib.request

import comfy_client as cc


def talk_url():
    """talk 服务地址（含路径），例：http://180.127.11.166:10558/talk 。未配置则报错（不静默降级）。"""
    u = (os.environ.get("WEAVEORA_TALK_URL") or "").strip().rstrip("/")
    return u


def _probe_size(payload):
    """采样尺寸/段长：默认 512² + 81 帧（实测 48G 上 768²/113 帧会 OOM）。"""
    ss = payload.get("talkSampleSize")
    if not (isinstance(ss, list) and len(ss) == 2):
        ss = [512, 512]
    try:
        pvl = int(payload.get("talkPartialLen") or 81)
    except (TypeError, ValueError):
        pvl = 81
    return [int(ss[0]), int(ss[1])], pvl


def generate_talk(jid, payload, progress_fn=None):
    """返回 [{bytes, mime, width, height, duration_ms}]（与 comfy_client.generate_lipsync 同口径）。"""
    url = talk_url()
    if not url:
        raise cc.ComfyError(
            "未配置整脸口型服务地址：请设置 worker 环境变量 WEAVEORA_TALK_URL"
            "（例：http://<gpu-host>:<port>/talk，由 deploy/gpu2_talk_server.py 提供）")

    image_key = (payload.get("imageKey") or "").strip()
    voice_keys = [k for k in (payload.get("voiceKeys") or []) if k]
    if not image_key or not voice_keys:
        raise cc.ComfyError("talk 缺少输入：imageKey / voiceKeys")

    if progress_fn:
        progress_fn(15, "fetch_inputs")
    img, _ctype = cc.fetch_reference_bytes(image_key)
    audio = cc._concat_voice(voice_keys)          # 该镜多段配音首尾相接（与混音同一口径）

    sample_size, partial_len = _probe_size(payload)
    body = {
        "image_b64": base64.b64encode(img).decode("ascii"),
        "image_suffix": ".jpg",
        "audio_b64": base64.b64encode(audio).decode("ascii"),
        "audio_suffix": ".wav",
        "prompt": (payload.get("prompt") or "").strip(),
        "jaw_gain": float(payload.get("jawGain") or 1.0),
        "jaw_strength": float(payload.get("jawStrength") or 0.35),
        "jaw_attack_ms": float(payload.get("jawAttackMs") or 50),
        "jaw_decay_ms": float(payload.get("jawDecayMs") or 130),
        "steps": int(payload.get("talkSteps") or 8),
        "guidance": float(payload.get("talkGuidance") or 4.0),
        "audio_guidance": float(payload.get("talkAudioGuidance") or 2.9),
        "seed": int(payload.get("talkSeed") or 43),
        "fps": 25,
        "sample_size": sample_size,
        "partial_video_length": partial_len,
    }
    print("[talk] POST %s image=%dB audio=%dB jaw_gain=%.2f steps=%d sample=%s pvl=%d"
          % (url, len(img), len(audio), body["jaw_gain"], body["steps"], sample_size, partial_len), flush=True)
    if progress_fn:
        progress_fn(20, "talk_inference（整脸生成，单卡串行；4–5s 镜约 2–5 分钟）")

    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                headers={"Content-Type": "application/json"})
    timeout = float(os.environ.get("WEAVEORA_TALK_TIMEOUT", "3600"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read()[:600].decode("utf-8", "replace")
        raise cc.ComfyError("talk 服务返回 %s：%s" % (e.code, detail))
    except Exception as e:
        raise cc.ComfyError("talk 服务调用失败：%s" % e)

    vb = base64.b64decode(data.get("video_b64") or "")
    if not vb:
        raise cc.ComfyError("talk 服务没有返回视频：%s" % json.dumps(data.get("meta") or {})[:300])
    w, h, dur = cc._probe_video_meta(vb)
    meta = data.get("meta") or {}
    print("[talk] 完成 %dx%d %.2fs（引擎 %.1fs，boost=%s）"
          % (w or 0, h or 0, (dur or 0) / 1000.0, meta.get("infer_seconds") or 0,
             (meta.get("boost") or {}).get("applied")), flush=True)
    if progress_fn:
        progress_fn(100, "done")
    return [{"bytes": vb, "mime": "video/mp4", "width": w, "height": h, "duration_ms": dur}]
