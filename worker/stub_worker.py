#!/usr/bin/env python3
"""Weaveora stub worker（§19：出站连 API，不反向连中间件）。

行为：注册节点 → 心跳（线程）→ 轮询 claim → 取到 job 后用纯 Python 生成一张占位 PNG
      → 上传 /internal/jobs/{id}/assets → complete。WEAVEORA_WORKER_MODE=stub|comfy 的语义在本文件=stub。

环境变量：
  WEAVEORA_API_BASE   默认 http://localhost:8080
  WEAVEORA_WORKER_TOKEN 默认 dev-worker-token（与 weaveora.worker.token 对应）
  WEAVEORA_WORKER_NAME  默认 stub-worker
  WEAVEORA_WORKER_WORKSPACE 可选 uuid（BYO）；不填 = 节点池
用法：python stub_worker.py            # 常驻循环
      python stub_worker.py --once    # 认领并完成一个任务后退出（便于联调）
"""
import argparse
import json
import os
import random
import socket as _socket
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

# VPS 无 IPv6 路由，而 api.replicate.com 等 CDN 域名偶发返回 AAAA → urllib 报
# [Errno 101] Network is unreachable。进程级强制 IPv4（失败再回退默认解析）。
_orig_getaddrinfo = _socket.getaddrinfo


def _ipv4_first(host, port, family=0, type=0, proto=0, flags=0):
    try:
        infos = _orig_getaddrinfo(host, port, _socket.AF_INET, type, proto, flags)
        if infos:
            return infos
    except Exception:
        pass
    return _orig_getaddrinfo(host, port, family, type, proto, flags)


_socket.getaddrinfo = _ipv4_first

API = os.environ.get("WEAVEORA_API_BASE", "http://localhost:8080").rstrip("/")
TOKEN = os.environ.get("WEAVEORA_WORKER_TOKEN", "dev-worker-token")
NAME = os.environ.get("WEAVEORA_WORKER_NAME", "stub-worker")
WORKSPACE = os.environ.get("WEAVEORA_WORKER_WORKSPACE")  # None = 节点池
MODE = os.environ.get("WEAVEORA_WORKER_MODE", "stub")  # stub|comfy

# P13 护栏：stub 模式已废弃，但历史上它会被注册成「GPU worker」并抢走 gpu 路由的
# 配音/配乐任务 —— 而跑它的机器（如 VPS）既没有 TTS 也没有 ACE-Step，只能走 http 兜底
# → 任务瞬间全部失败（实测：9 个 voice + 15 个 bgm 全挂，只有本机 comfy worker 抢到的成功）。
# 宁可拒绝启动，也不要静默抢占。
if MODE not in ("comfy", "cloud"):
    print("[worker] 拒绝启动：WEAVEORA_WORKER_MODE=%r 不是 comfy/cloud。" % MODE, flush=True)
    print("[worker] 生产只用：comfy（Windows，含 WSL-TTS + ComfyUI）或 cloud（云图片/视频）。", flush=True)
    raise SystemExit(2)


def _req(method, path, payload=None, files=None, timeout=30):
    headers = {"X-Worker-Token": TOKEN}
    data = None
    if files:
        boundary = "----wv" + str(random.randint(10 ** 10, 10 ** 12))
        parts = []
        for field, (fname, content, ctype) in files.items():
            parts.append(
                ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                 "Content-Type: %s\r\n\r\n" % (boundary, field, fname, ctype)).encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode())
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    # ★ 瞬断重试（2026-09-16）：部署 api-deploy.sh 会 restart weaveora-api，
    #   而 _req 以前只处理 HTTPError，**连接被拒/超时直接抛 URLError** →
    #   register()/claim/心跳全都会把进程/线程弄挂（日志一串 Failed with result 'exit-code'）。
    #   这里对网络层错误退避重试；HTTP 状态码错误照旧立刻返回给调用方判断。
    last = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                return r.status, json.loads(body.decode()) if body else {}
        except urllib.error.HTTPError as e:
            body = e.read().decode() or "{}"
            try:
                return e.code, json.loads(body)
            except Exception:
                return e.code, {"error": body[:300]}
        except Exception as e:      # URLError / timeout / 连接被拒
            last = e
            if attempt < 5:
                print("[stub] %s %s 网络异常（第 %d 次）：%s —— 2s 后重试"
                      % (method, path, attempt, e), flush=True)
                time.sleep(2)
    raise last


def make_png(width, height, seed):
    """纯标准库占位 PNG：按 seed 取色的横向渐变块。"""
    w, h = max(8, int(width or 1024)), max(8, int(height or 1024))
    r = (seed or 7) % 256
    g = (seed or 7) // 256 % 256
    b = (seed or 7) // 65536 % 256
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        t = (y * 255) // max(1, h - 1)
        for _ in range(w):
            raw += bytes(((r + t) % 256, (g + 255 - t) % 256, b))
    comp = zlib.compress(bytes(raw), 6)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")


FALLBACK_WEBP = "UklGRtIAAABXRUJQVlA4WAoAAAACAAAALwAALwAAQU5JTQYAAAAAAAAAAABBTk1GVAAAAAAAAAAAAC8AAC8AAMgAAAJWUDggPAAAAHADAJ0BKjAAMAA+bTaYSSQjIqEjiACADYlpAAIDiGGv401UAAD+9PQ/90n6n+i/6qp6/G7/eG4v7wwAAEFOTUZKAAAAAAAAAAAALwAALwAAyAAAAFZQOCAyAAAA9AIAnQEqMAAwAD5tMJFIgjgAANiWkAAgOKK/T4J4hgAA/vKpfzse4OezX/ELc/xCQAA="


def make_animated_webp(width, height, seed, frames=14, duration_ms=110):
    """stub motion 占位：优先 PIL 生成动画 WebP；无 PIL（精简环境）用内置动图回退。"""
    try:
        from PIL import Image, ImageDraw
    except Exception:
        import base64 as _b64
        return _b64.b64decode(FALLBACK_WEBP)
    import io as _io
    w, h = max(8, width or 320), max(8, height or 320)
    imgs = []
    for i in range(frames):
        img = Image.new("RGB", (w, h), ((seed * 3 + i * 7) % 256, (seed // 7 + i * 5) % 256, (seed // 13) % 256))
        d = ImageDraw.Draw(img)
        step = (w * 2) // max(1, frames)
        x0 = (i * step) % (w + 40) - 20
        d.rectangle([x0, h // 3, x0 + max(10, w // 6), h - h // 3], fill=(255, 240, 200))
        imgs.append(img)
    buf = _io.BytesIO()
    imgs[0].save(buf, format="WEBP", save_all=True, append_images=imgs[1:],
                 duration=duration_ms, loop=0)
    return buf.getvalue()

def _voice_media(payload):
    """P9：配音。若任务带 refAssetKey（克隆音色），先把参考音拉下来再喂 TTS。

    **关键：参考音以 base64 字节随请求传给 tts_server，不能传文件路径。**
    worker 跑在 Windows、tts_server 在 WSL（Linux）—— Windows 临时目录在 Linux 里不存在，
    `os.path.exists()` 为 False → 服务端会落到「自带兑底参考音」→ 用户听到标准女声，
    而克隆从未生效（实测产物与自带参考音声纹相似度 0.821，与用户参考音 -0.026）。

    refAssetKey 是**存储 key**（不是资产 UUID）。"""
    voice = (payload.get("voice") or "")
    ref = payload.get("refAssetKey")
    if isinstance(voice, str) and voice.startswith("clone:") and not ref:
        raise RuntimeError(
            "克隆音色参考音缺失（voice=%s 但任务里没有 refAssetKey）。"
            "这通常是重跑了旧任务；请在分镜里重新点「重新生成这条」，不要重跑旧任务。" % voice)
    if not ref:
        import audio_client as audio
        data, mime, dur_ms = audio.tts(payload)
        return [(data, mime, None, None, dur_ms)]

    import base64
    import comfy_client as c  # 仅用它的 fetch_reference_bytes（纯 urllib）
    import audio_client as audio
    raw, _mime = c.fetch_reference_bytes(ref)
    p = dict(payload)
    p["refAudioB64"] = base64.b64encode(raw).decode("ascii")
    data, mime, dur_ms = audio.tts(p)
    return [(data, mime, None, None, dur_ms)]


def _bgm_media(jid, payload):
    """配乐生成：默认走 ComfyUI（ACE-Step 1.5 原生节点 + all-in-one 权重）；
    WEAVEORA_MUSIC_ENGINE=http 时改走 deploy/audio/music_server.py（:8092）。
    返回 _complete 需要的 media 列表 [(bytes, mime, w, h, dur_ms)]。"""
    engine = os.environ.get("WEAVEORA_MUSIC_ENGINE", "comfy").strip().lower()
    if engine in ("comfy", "comfyui") and MODE == "comfy":
        import comfy_client as _c
        outs = _c.generate_music(
            "weaveora-stub-worker", payload,
            progress_fn=_prog(jid))
        return [(o["bytes"], o.get("mime") or "audio/mpeg", None, None,
                 o.get("duration_ms")) for o in outs]
    import audio_client as audio
    data, mime, dur_ms = audio.music(payload)
    return [(data, mime, None, None, dur_ms)]


def _complete(jid, payload, media):
    """media: list[(bytes, mime, w, h, dur_ms[, extra])]；上传第一个产物并 complete。

    extra（可选 dict）里的额外字段随 complete 上报给后端，例如 motion 的
    faceDetected —— 资产快照会存下它，供选镜弹窗提前标出无人脸的镜。
    """
    if not media:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "EMPTY_OUTPUT", "message": "引擎没有输出"})
        return False
    data, mime, w, h, dur = media[0][:5]
    extra = media[0][5] if len(media[0]) > 5 and isinstance(media[0][5], dict) else {}
    ext = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp",
           "video/mp4": "mp4", "video/webm": "webm",
           "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav",
           "audio/mpeg": "mp3", "audio/mp3": "mp3",
           "audio/flac": "flac", "audio/x-flac": "flac",
           "audio/mp4": "m4a", "audio/aac": "m4a", "audio/ogg": "ogg"}.get(mime, "bin")
    seed = int(payload.get("seed") or random.randint(1, 2 ** 31))
    st, up = _req("POST", "/internal/jobs/%s/assets" % jid, files={
        "file": ("out_%s.%s" % (jid[:8], ext), data, mime)})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "UPLOAD_FAIL", "message": str(up)[:200]})
        return False
    st, done = _req("POST", "/internal/jobs/%s/complete" % jid, {
        "assets": [{"key": up["key"], "mime": mime, "width": w, "height": h, "seed": seed,
                    "durationMs": dur, "faceDetected": extra.get("faceDetected")}]})
    if st != 200:
        _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "COMPLETE_FAIL", "message": str(done)[:200]})
        return False
    print("[%s] job %s ok kind=%s mime=%s key=%s" % (MODE, jid[:8], payload.get("kind"), mime, up["key"]),
          flush=True)
    return True


def _yield_vram_for_video(need_gb=None):
    """出视频前让 TTS 让出显存。

    背景：Wan2.2 I2V-A14B 是双专家（fp8 各 13.3GiB），而 CosyVoice 常驻要占 ~7GiB，
    24G 卡上两者无法共存。这里先看 ComfyUI 的显存余量：够用就不打扰 TTS，
    不够则 POST /unload 让 TTS 卸载模型（下次配音自动懒加载回来）。

    失败只告警不阻塞：ComfyUI 仍可通过 offload 慢跑，不能因为让位失败就废掉整个任务。

    阈值口径：A14B 双专家实测峰值 41.8~42.4 GiB（832×480/33 帧）→ 默认取
    comfy_client.MOTION_MIN_FREE_GB（环境变量 WEAVEORA_MOTION_MIN_FREE_GB，缺省 30），
    而不是旧 24G 卡时代的硬编码 15。
    """
    try:
        import comfy_client as _c
        if need_gb is None:
            need_gb = getattr(_c, "MOTION_MIN_FREE_GB", 30.0)
        free, total = _c.vram_stats()
        if free is not None and free >= need_gb:
            print("[stub] 视频前显存余量 %.1f/%.1f GiB ≥ %.1f，TTS 无需卸载"
                  % (free, total, need_gb), flush=True)
            return
        print("[stub] 视频前显存余量 %s，让 TTS 卸载归还显存…"
              % ("未知" if free is None else "%.1f/%.1f GiB" % (free, total)), flush=True)
        import audio_client as _a
        print("[stub] TTS /unload -> %s" % (_a.unload(),), flush=True)
    except Exception as e:
        print("[stub] 显存让位失败（继续执行，可能变慢或换入换出）：%s" % e, flush=True)


def _ram_available_gb():
    """系统可用内存（GiB）：/proc/meminfo 的 MemAvailable。拿不到返回 None。"""
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    return round(float(line.split()[1]) / 1048576.0, 1)
    except Exception:
        pass
    return None


def _gpu_resources():
    """从 **GPU 机**读 (vram_free_gb, ram_available_gb)。

    ★ 为什么不能读本地 /proc/meminfo：worker 跑在 VPS 上，读出来的是 **VPS 的内存**
    （实测日志里一直是 5.0 GiB，而 GPU 机 `free -m` 是 49.5G）—— 内存预算必须看跑模型的那台。
    走 talk 服务的 `/health`（talk 服务在 GPU 机上，已返回 `ram_available_gb`）；
    talk 不可达时退回 ComfyUI 的 `/system_stats`（只有显存）。
    """
    ram = None
    vram = None
    try:
        import talk_client as _tc
        base = _tc.talk_url()
        if base:
            import json as _json
            import urllib.request as _u
            with _u.urlopen(base.rstrip("/") + "/health", timeout=8) as r:
                d = _json.loads(r.read().decode())
            vram = d.get("vram_free_gb")
            ram = d.get("ram_available_gb")
    except Exception:
        pass
    if vram is None:
        try:
            import comfy_client as _cc
            vram, _t = _cc.vram_stats()
        except Exception:
            pass
    return vram, ram


def _yield_resources(need_vram_gb=0.0, need_ram_gb=0.0, label=""):
    """切换能力前的**统一资源让出入口**（所有重任务都必须走这里）。

    为什么需要收口：这台机器上三个“重资产”在争同一份 47G 显存 / 62G 内存 ——
      · ComfyUI（出图 Qwen-Image ~28G、出片 A14B 双专家 ~33G、对口型、配乐 的宿主，且默认会把权重缓存在**内存**里）；
      · talk 服务（每次请求 fork 子进程加载整套 EchoMimic 管线，实测 anon-rss **29.4G**）；
      · TTS（CosyVoice 常驻 ~7G 显存）。
    踩过的两个坑：① 显存：A14B 双专家与 Qwen-Image 同时驻留 → `KSamplerAdvanced` OOM；
                  ② 内存：ComfyUI 缓存 28G + talk 子进程 29.4G > 62G → **内核 oom_kill**（连 `weaveora-stack` 一起挂）。

    本函数做三件事（失败均不致命，但要打日志）：
      ① 请 ComfyUI `/free`（unload_models + free_memory）并**等显存真的回来**（`/free` 是异步的）；
      ② 请 TTS `/unload`（让 CosyVoice 交还显存，下次配音会自动懒加载回来）；
      ③ 打印让出前后的显存/内存，便于事后复盘。
    """
    tag = ("[%s] " % label) if label else ""
    vram0, ram0 = _gpu_resources()
    try:
        import comfy_client as _cc
        if need_vram_gb > 0:
            try:
                _cc._free_comfy_models(wait_gb=float(need_vram_gb), timeout=120)
            except Exception as e:
                print("[worker] %s让 ComfyUI 交还显存失败（忽略）：%s" % (tag, e), flush=True)
    except Exception as e:
        print("[worker] %s调用 comfy_client 失败（忽略）：%s" % (tag, e), flush=True)
    try:
        import audio_client as _ac
        _ac.unload()   # TTS 交还显存（常驻 ~7G）
    except Exception as e:
        print("[worker] %sTTS /unload 失败（忽略）：%s" % (tag, e), flush=True)
    vram1, ram1 = _gpu_resources()
    print("[worker] %s资源让出（GPU 机）：显存 %s → %s GiB（目标≥%.0f）｜可用内存 %s → %s GiB%s"
          % (tag,
             ("%.1f" % vram0) if vram0 is not None else "?",
             ("%.1f" % vram1) if vram1 is not None else "?",
             need_vram_gb,
             ("%.1f" % ram0) if ram0 is not None else "?",
             ("%.1f" % ram1) if ram1 is not None else "?",
             ("（talk 需≥%.0f）" % need_ram_gb) if need_ram_gb else ""), flush=True)
    if need_ram_gb and ram1 is not None and ram1 < need_ram_gb:
        print("[worker] %sWARN GPU 机可用内存 %.1f GiB < talk 需要 %.0f GiB：talk 服务会返回 503"
              "（不会再被内核 oom_kill，但要等资源真正空出来）" % (tag, ram1, need_ram_gb), flush=True)
    return vram1, ram1


# ── 取消控制（2026-09-16）──────────────────────────────────────────────────
# 背景：取消原先只改 API 的 state，worker 不知情 → 继续等 ComfyUI 跑完（24 步 720p 100+ 秒），
# 而 ComfyUI 串行 → 僵尸 prompt 把队列堵死（表现为「新任务一直 queued」「一张关键帧等好几分钟」）。
# 现在：API 的 /progress 会回 `cancelRequested`；worker 每轮轮询都问一次，取消即 /interrupt 并退出。
_LAST_PROGRESS = {"p": 10}


def _prog(jid):
    """统一进度上报：记录最近进度（供取消检查复用，避免进度回退）。"""
    def fn(p, stage):
        try:
            _LAST_PROGRESS["p"] = max(int(p or 0), _LAST_PROGRESS["p"])
        except (TypeError, ValueError):
            pass
        return _req("POST", "/internal/jobs/%s/progress" % jid,
                    {"progress": _LAST_PROGRESS["p"], "stage": stage})
    return fn


def _cancel_check(jid):
    """给 comfy_client 的钩子：返回 True = 本任务已被取消。"""
    def fn():
        try:
            _st, body = _req("POST", "/internal/jobs/%s/progress" % jid,
                             {"progress": _LAST_PROGRESS["p"], "stage": "cancel_check"}, timeout=15)
            return bool((body or {}).get("cancelRequested"))
        except Exception:
            return False
    return fn


def execute_job(job):
    jid = job["jobId"]
    _LAST_PROGRESS["p"] = 10
    try:
        import comfy_client as _cc_cancel
        _cc_cancel.CANCEL_CHECK = _cancel_check(jid)
        _cc_cancel.reset_job_state()          # 每任务重置"已清残留"标记（否则重试会自中断）
    except Exception:
        pass
    payload = job.get("payload") or {}
    kind = payload.get("kind") or "still"
    seed = int(payload.get("seed") or random.randint(1, 2 ** 31))
    params = payload.get("params") or {}
    width = int(params.get("width") or 1024)
    height = int(params.get("height") or 1024)
    _req("POST", "/internal/jobs/%s/progress" % jid, {"progress": 15, "stage": "loading_model"})

    # P7 自托管音频：配音(CosyVoice) / 配乐(ACE-Step 1.5) —— 与 MODE 无关，但 bgm 默认走本机 ComfyUI
    if kind in ("voice", "bgm"):
        try:
            if kind == "voice":
                media = _voice_media(payload)
            else:
                # 配乐跑在本机 ComfyUI（ACE-Step），先把别的模型/TTS 让出去
                _yield_resources(need_vram_gb=12.0, label="bgm")
                media = _bgm_media(jid, payload)
            return _complete(jid, payload, media)
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "AUDIO_ERROR", "message": str(e)[:500]})
            return False

    # P13 对口型（lipsync）：画面 + 配音 → 音频驱动嘴型。走本机 ComfyUI 工作流
    # （LatentSync/MuseTalk/Wav2Lip 任一，见 docs/lipsync-setup.md）
    if kind == "lipsync":
        import comfy_client as comfy
        try:
            # LatentSync 跑在 ComfyUI 里：先让出资源（含卸载缓存模型 + TTS 让位）
            _yield_resources(need_vram_gb=12.0, label="lipsync")
            outs = comfy.generate_lipsync(
                "weaveora-stub-worker", payload,
                progress_fn=_prog(jid))
            media = [(o["bytes"], o.get("mime") or "video/mp4", o.get("width"), o.get("height"),
                      o.get("duration_ms")) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "LIPSYNC_ERROR", "message": str(e)[:500]})
            return False

    # jaw-lip（整脸音频驱动）：静帧 + 配音 → EchoMimic 整脸表演（GPU 机上的 talk 服务，独立 venv）
    # 与 lipsync 的分工：lipsync 在**既有画面**上换嘴（保留运镜）；talk 从**静帧**重新表演（嘴+下颌+表情一起生成）。
    if kind == "talk":
        import talk_client
        try:
            # talk 服务要起 EchoMimic 全管线（~24G 权重 / 实测 RSS 29.4G），而它**不在 ComfyUI 里**：
            # 必须让 ComfyUI 把内存/显存都交出来，否则 28G(ComfyUI 缓存) + 29.4G(talk) > 62G → 内核 oom_kill。
            _yield_resources(need_vram_gb=1.0, need_ram_gb=34.0, label="talk")
            outs = talk_client.generate_talk(
                jid, payload,
                progress_fn=_prog(jid))
            media = [(o["bytes"], o.get("mime") or "video/mp4", o.get("width"), o.get("height"),
                      o.get("duration_ms")) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "TALK_ERROR", "message": str(e)[:500]})
            return False

    if MODE == "cloud":
        import cloud_client as cloud
        # 用户云凭据（图片=OpenAI-compatible；视频=Replicate），经 internal 通道拉取
        cfg = {}
        uid = (job or {}).get("userId") or payload.get("userId")
        if uid:
            try:
                _, cfg = _req("GET", "/internal/users/%s/cloud-config" % uid)
            except Exception:
                cfg = {}
        try:
            if kind == "lipsync":
                # 对口型不该走到云端（云视频模型没有口型能力）；给出明确原因而不是掉进图片分支
                raise RuntimeError("对口型需要本机 ComfyUI 口型工作流（见 docs/lipsync-setup.md）；"
                                   "当前任务被路由到云引擎，请确认 worker 的 WEAVEORA_LIPSYNC_WORKFLOW 已配置")
            if kind == "clip":
                vcfg = cfg.get("video") or {}
                outs = cloud.generate_motion_via_replicate(
                    payload, vcfg.get("apiKey") or "", vcfg.get("model") or "",
                    cfg=vcfg,
                    progress_fn=_prog(jid))
                media = [(o[0], o[1], o[2], o[3], o[4]) for o in outs]
            else:
                import cloud_image
                import cloud_client as _cc
                icfg = cfg.get("image") or {}
                base = icfg.get("baseUrl") or ""
                if base.startswith("http"):
                    keys = payload.get("referenceKeys") or []
                    # 参考图：OpenAI 兼容生态里用 `image`（Ark/seedream 支持单张或数组）
                    ref_blobs = []
                    for rk in keys:
                        try:
                            ref_blobs.append(_cc._fetch_asset(rk))
                        except Exception as e:
                            print("[cloud-image] 参考图准备失败，跳过: %s" % e, flush=True)
                    if keys and not ref_blobs:
                        # 不能静默降级成“无参考图”出图（会直接毁掉人物一致性）
                        raise RuntimeError("参考图全部准备失败（%d 张）；本镜已中止，"
                                           "请稍后重试（不会用无参考图的结果冒充）" % len(keys))
                    _pos = payload.get("positive_prompt", "")
                    _neg = (payload.get("negative_prompt") or "").strip()
                    if _neg:
                        # 网关（方舟等 OpenAI 兼容 images 接口）没有 negative_prompt → 并入提示词
                        _pos = _cc._merge_negative(_pos, _neg)
                        print("[cloud-image] 网关无 negative_prompt，负向词已并入提示词（%d 字）" % len(_neg),
                              flush=True)
                    raw = cloud_image.generate(
                        _pos, payload.get("params") or {}, icfg,
                        ref_blobs=ref_blobs)
                    blobs = raw if raw else []
                    if not blobs:
                        raise RuntimeError("云图片返回空结果")
                    size = _cc._image_size(blobs[0])
                    media = [(blobs[0], _cc._sniff_image_mime(blobs[0]),
                              size[0] if size else None, size[1] if size else None, None)]
                    print("[cloud-image] ark/openai 兼容出图 %d bytes refs=%d size=%s"
                          % (len(blobs[0]), len(ref_blobs), size), flush=True)
                else:
                    # BaseURL 留空 = Replicate 通道（apiKey 为 Replicate token）
                    if not (icfg.get("apiKey") or ""):
                        raise RuntimeError("未配置云图片凭据（图片云 API：填 BaseURL+Key 或 Replicate Key）")
                    params = payload.get("params") or {}
                    outs = cloud.replicate_image(
                        payload, icfg.get("apiKey") or "", icfg.get("model") or "",
                        cfg=icfg,
                        progress_fn=_prog(jid))
                    # 尺寸用云端返回的**真实**尺寸（模型可能按画幅自己定尺，如 768x1360）
                    media = [(o[0], o[1], o[2], o[3], None) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            import traceback as _tb
            _tb.print_exc()
            _req("POST", "/internal/jobs/%s/fail" % jid,
                 {"code": "CLOUD_ERROR", "message": str(e)[:500]})
            return False

    if MODE == "comfy":
        import comfy_client as engine
        try:
            if kind == "clip":
                # 出片：A14B 双专家要 ~44G，先把别的模型让出去（含等 /free 真生效）
                _yield_resources(need_vram_gb=float(payload.get("motionNeedVramGb") or 44.0), label="clip")
                _yield_vram_for_video()
                outs = engine.generate_motion("weaveora-stub-worker", payload,
                                              progress_fn=_prog(jid))
                media = [(o["bytes"], o.get("mime") or "image/webp",
                          o.get("width") or width, o.get("height") or height,
                          int(float(payload.get("duration_sec", 3.0)) * 1000),
                          {"faceDetected": o.get("face_detected")}) for o in outs]
            else:
                # 文生图：配了「本机 ComfyUI 工作流」（engine=comfy + 工作流 JSON）就走工作流出图，
                # 否则用 worker 自带的 SDXL/IP-Adapter 代码路径（builtin）。
                # 先让出资源：出图栈要 ~28-31G（Qwen-Image 20.4 + VL 7.9 + VAE/LoRA），别让 TTS 占着
                _yield_resources(need_vram_gb=31.0, label="still")
                if engine.image_workflow_ready():
                    print("[worker] 文生图走工作流：%s" % engine.IMAGE_TXT2IMG_WF, flush=True)
                    outs = engine.generate_via_workflow("weaveora-stub-worker", payload,
                                                        progress_fn=_prog(jid))
                else:
                    outs = engine.generate("weaveora-stub-worker", payload,
                                           progress_fn=_prog(jid))
                media = [(o["bytes"], "image/png", width, height, None) for o in outs]
            return _complete(jid, payload, media)
        except Exception as e:
            _req("POST", "/internal/jobs/%s/fail" % jid, {"code": "COMFY_ERROR", "message": str(e)[:500]})
            return False

    # stub：still → 占位 PNG；clip → 动画 WebP（motion 占位）
    _req("POST", "/internal/jobs/%s/progress" % jid, {"progress": 40, "stage": "sampling"})
    time.sleep(0.2)
    if kind == "clip":
        webp = make_animated_webp(width, height, seed)
        return _complete(jid, payload, [(webp, "image/webp", width, height,
                                         int(float(payload.get("duration_sec", 3.0)) * 1000))])
    png = make_png(width, height, seed)
    return _complete(jid, payload, [(png, "image/png", width, height, None)])


def register():
    engine = "cloud" if MODE == "cloud" else "gpu"
    caps = {"engine": engine, "gpu": MODE, "workflows": ["stub_txt2img", "stub_motion"]}
    if MODE == "comfy":
        caps = {"engine": "gpu", "gpu": "comfy", "audio": True,
                "workflows": ["sdxl_txt2img", "wan_i2v", "ipadapter", "cosyvoice_tts", "ace_step_music"]}
        try:
            import comfy_client as _ccap
            if _ccap.image_workflow_ready():
                caps["workflows"].append("workflow_txt2img")
                caps["imageWorkflow"] = _ccap.IMAGE_TXT2IMG_WF
        except Exception:
            pass
    st, body = _req("POST", "/internal/nodes/register", {
        "name": NAME,
        "workspaceId": WORKSPACE or None,
        "capabilities": caps,
    })
    if st != 200:
        raise SystemExit("register failed: %s %s" % (st, body))
    return body["nodeId"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true", help="完成一个任务即退出")
    args = ap.parse_args()

    # ★ 注册要能扛住 API 重启（2026-09-16）：部署 api-deploy.sh 会 restart weaveora-api，
    #   而这里一抛异常整个进程就退出 → systemd 每 10s 重启一次（日志里一串
    #   `Failed with result 'exit-code'`，看着像 worker 坏了）。改成有限重试 + 退避。
    node_id = None
    for attempt in range(1, 31):
        try:
            node_id = register()
            break
        except Exception as e:
            print("[stub] 注册失败（第 %d 次）：%s —— 5s 后重试（API 可能在重启）" % (attempt, e), flush=True)
            time.sleep(5)
    if not node_id:
        print("[stub] 注册连续失败 30 次，退出（等 systemd 重启）", flush=True)
        return
    print("[stub] node %s registered (workspace=%s)" % (node_id[:8], WORKSPACE or "pool"), flush=True)

    stop = threading.Event()

    def heartbeat():
        while not stop.is_set():
            try:
                _req("POST", "/internal/nodes/%s/heartbeat" % node_id, {})
            except Exception as e:
                # 心跳线程不能因为 API 重启就死掉（死了节点会被判离线，任务再也领不到）
                print("[stub] 心跳失败（忽略，继续）：%s" % e, flush=True)
            time.sleep(25)

    threading.Thread(target=heartbeat, daemon=True).start()

    worked = 0
    try:
        while not stop.is_set():
            try:
                st, body = _req("POST", "/internal/nodes/%s/claim" % node_id, {}, timeout=35)
            except Exception as e:
                # 重试后仍失败（API 还在重启/网络抖动）→ 继续轮询，别让进程退出
                print("[stub] 领任务失败（忽略，3s 后重试）：%s" % e, flush=True)
                time.sleep(3)
                continue
            job = (body or {}).get("job")
            if job:
                # 服务地址（配音/配乐、对口型、转写、人脸）随任务下发 —— 用户在
                # 「生成引擎配置 → 服务地址」里改即刻生效，不用改脚本/重启 worker。
                _svc = (body or {}).get("services")
                try:
                    import comfy_client as _cc
                    _cc.apply_services(_svc)
                except Exception as e:
                    print("[stub] 应用 comfy 服务地址失败：%s" % e, flush=True)
                try:
                    import audio_client as _ac
                    _ac.apply_services(_svc)
                except Exception as e:
                    print("[stub] 应用音频服务地址失败：%s" % e, flush=True)
                try:
                    import talk_client as _tc
                    _tc.apply_services(_svc)
                except Exception as e:
                    print("[stub] 应用整脸口型服务地址失败：%s" % e, flush=True)
            if not job:
                if args.once and worked == 0:
                    time.sleep(1)
                if args.once:
                    print("[stub] no job; exit", flush=True)
                    break
                time.sleep(2)
                continue
            if execute_job(job):
                worked += 1
            if args.once:
                break
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
    print("[%s] done jobs=%d" % (MODE, worked), flush=True)


if __name__ == "__main__":
    main()
