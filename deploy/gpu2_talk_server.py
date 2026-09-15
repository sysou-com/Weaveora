#!/usr/bin/env python3
"""Weaveora jaw-lip 服务（GPU#2 上常驻，默认 :8094）—— EchoMimicV3 整脸音频驱动 + 下颌曲线层

为什么单独一个服务（对齐 tts_server/face_server 的形态）：
  · EchoMimicV3 需要独立 venv（/opt/weaveora/envs/talk，含 diffusers/transformers/insightface），
    与 ComfyUI 的 venv 隔离，避免污染 LatentSync 生产环境；
  · GPU#2 与 worker 不在同一台机（worker 在 VPS），所以用 HTTP 传字节，不共享文件系统。

接口（POST + JSON，UTF-8）：
  GET  /health  → {"ok":true,"device":"cuda","vram_free_gb":…,"want":…}
  POST /talk    → 入参：
      { "image_b64": "<静帧字节 base64>", "image_suffix": ".jpg",
        "audio_b64": "<配音字节 base64>", "audio_suffix": ".wav",
        "jaw_gain": 1.0,          # 1.0 = 完全不动（原生 EchoMimic）；>1 下颌更开，<1 更闭
        "jaw_strength": 0.35,     # 曲线层强度系数 α（gain≠1 时才生效）
        "jaw_attack_ms": 50, "jaw_decay_ms": 130,
        "steps": 25, "guidance": 4.0, "audio_guidance": 2.9, "seed": 43, "fps": 25,
        "max_seconds": 12.0 }     # 安全阀：超长音频直接拒绝（避免一次跑太久）
    → { "video_b64": "<mp4 base64>", "meta": {…耗时/帧数/下颌增益...} }

下颌曲线层（我们的增量，先做「后置形变」，不动生成模型）：
  音频响度包络 → jaw_open(t) 曲线（attack/decay 平滑）→ 对**下半脸**做向下位移的 remap 形变，
  位移量 = α·(jaw_gain−1)·env(t)·嘴宽；gain=1.0 时**零改动**（identity），保证第一版产物就是原生效果。
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

ROOT = os.environ.get("WEAVEORA_ROOT", "/opt/weaveora")
PY = os.path.join(ROOT, "envs/talk/bin/python")
REPO = os.path.join(ROOT, "echo_mimic_v3")
WORK = os.path.join(ROOT, "talk_work")
FACE_AUX = os.environ.get("WEAVEORA_FACE_AUX", os.path.join(ROOT, "models/face_aux"))
PORT = int(os.environ.get("WEAVEORA_TALK_PORT", "8094"))

_LOCK = threading.Lock()          # 单卡串行：一次只跑一个推理
_FACE_APP = None


# ---------------------------------------------------------------- 下颌曲线层
def _loudness_env(audio_path, fps, attack_ms=50.0, decay_ms=130.0):
    """音频 → 每帧响度包络（0~1，快起慢落）。

    为什么用响度而不是音素：喊叫是"整脸运动"（下颌受力），音量包络比音素更贴合下颌开合（JALI 的 prosody 通道）。
    """
    import librosa
    y, sr = librosa.load(audio_path, sr=16000, mono=True)
    hop = max(1, int(sr / fps))
    rms = librosa.feature.rms(y=y, frame_length=max(hop * 2, 512), hop_length=hop)[0]
    if rms.size == 0:
        return np.zeros(1, dtype=np.float32)
    rms = rms / (float(rms.max()) or 1.0)
    a = float(np.exp(-1.0 / max(1.0, (attack_ms / 1000.0) * fps)))
    d = float(np.exp(-1.0 / max(1.0, (decay_ms / 1000.0) * fps)))
    out = np.zeros_like(rms)
    prev = 0.0
    for i, v in enumerate(rms):
        c = a if v > prev else d
        prev = c * prev + (1.0 - c) * v
        out[i] = prev
    return out.astype(np.float32)


def _face_app():
    global _FACE_APP
    if _FACE_APP is None:
        from insightface.app import FaceAnalysis
        app = FaceAnalysis(allowed_modules=["detection", "landmark_2d_106"],
                           root=FACE_AUX, providers=["CPUExecutionProvider"])
        app.prepare(ctx_id=-1, det_size=(640, 640))
        _FACE_APP = app
    return _FACE_APP


def _mouth_from_landmarks(face):
    """(cx, cy, mouth_width) —— 嘴部点位 52..71 是 InsightFace 2d106 的实测口径（见 worker 注释）。"""
    lm = getattr(face, "landmark_2d_106", None)
    if lm is None:
        return None
    pts = np.asarray(lm, dtype=np.float32)[52:72]
    cx, cy = float(pts[:, 0].mean()), float(pts[:, 1].mean())
    w = float(pts[:, 0].max() - pts[:, 0].min())
    if w <= 1:
        return None
    return cx, cy, w


def jaw_boost(in_mp4, out_mp4, audio_path, gain=1.0, strength=0.35,
              attack_ms=50.0, decay_ms=130.0):
    """按音频包络给"下半脸"做向下位移（下颌更开）。gain=1.0 → 原样拷贝（零改动）。

    实现取舍：v1 用"羽化高斯位移场 + remap"，不做 TPS/网格与口腔内层合成——
    ① 不引入新的网络/权重；② gain=1.0 时完全不碰像素；③ 位移只作用在嘴线以下并向外羽化，
       不会把眼睛/鼻子拉走。口腔内部（牙齿/舌）的合成属于 S2b，先留着看效果再决定要不要做。
    """
    import cv2
    if abs(gain - 1.0) < 0.02:
        shutil.copyfile(in_mp4, out_mp4)
        return {"applied": False, "gain": gain}

    cap = cv2.VideoCapture(in_mp4)
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    env = _loudness_env(audio_path, fps, attack_ms, decay_ms)
    app = _face_app()

    tmpdir = tempfile.mkdtemp(prefix="talk_boost_")
    vpath = os.path.join(tmpdir, "v.mp4")
    writer = cv2.VideoWriter(vpath, cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    idx = 0
    applied = 0
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            faces = app.get(frame)
            m = None
            if faces:
                f = max(faces, key=lambda x: (x.bbox[2] - x.bbox[0]) * (x.bbox[3] - x.bbox[1]))
                m = _mouth_from_landmarks(f)
            if m is None:
                writer.write(frame)
                idx += 1
                continue
            cx, cy, mw = m
            e = float(env[min(idx, len(env) - 1)]) if len(env) else 0.0
            dy_max = strength * (gain - 1.0) * e * mw        # 像素：正=更开，负=更闭
            if abs(dy_max) < 0.5:
                writer.write(frame)
                idx += 1
                continue
            sx, sy = 0.95 * mw, 0.75 * mw
            ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
            gauss = np.exp(-(((xs - cx) ** 2) / (2 * sx * sx) + ((ys - cy) ** 2) / (2 * sy * sy)))
            below = np.clip((ys - cy) / max(1.0, 0.35 * mw), 0.0, 1.0)   # 嘴线以上不动
            dy = dy_max * gauss * below
            map_x = xs
            map_y = np.clip(ys - dy, 0, h - 1).astype(np.float32)
            out = cv2.remap(frame, map_x, map_y, interpolation=cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_REFLECT101)
            writer.write(out)
            applied += 1
            idx += 1
    finally:
        cap.release()
        writer.release()

    # 用 ffmpeg 重新封装并接回原音轨（笔者的 mp4v 轨道无音频）
    subprocess.run([os.path.join(ROOT, "ffmpeg") if os.path.exists(os.path.join(ROOT, "ffmpeg")) else "ffmpeg",
                    "-y", "-loglevel", "error", "-i", vpath, "-i", audio_path,
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-shortest", out_mp4], check=True)
    shutil.rmtree(tmpdir, ignore_errors=True)
    return {"applied": True, "gain": gain, "frames_boosted": applied, "frames_total": idx,
            "alpha": strength, "attack_ms": attack_ms, "decay_ms": decay_ms}


# ---------------------------------------------------------------- 推理
def run_echo(image_bytes, image_suffix, audio_bytes, audio_suffix, params):
    """调用官方 EchoMimicV3 推理（每请求一个临时 base_dir + 打补丁的 infer 脚本）。"""
    jid = uuid.uuid4().hex[:8]
    d = os.path.join(WORK, jid)
    os.makedirs(os.path.join(d, "imgs"), exist_ok=True)
    os.makedirs(os.path.join(d, "audios"), exist_ok=True)
    os.makedirs(os.path.join(d, "masks"), exist_ok=True)
    img = os.path.join(d, "imgs", "shot" + (image_suffix or ".jpg"))
    aud = os.path.join(d, "audios", "shot" + (audio_suffix or ".wav"))
    with open(img, "wb") as fh:
        fh.write(image_bytes)
    with open(aud, "wb") as fh:
        fh.write(audio_bytes)

    src = io_read(os.path.join(REPO, "infer_preview.py"))
    src = src.replace('self.base_dir = "datasets/echomimicv3_demos/"', 'self.base_dir = "%s/"' % d)
    src = src.replace("self.test_name_list = ['shot5']", "self.test_name_list = ['shot']")
    if "self.test_name_list = ['shot']" not in src:      # 兜底：替换掉原始长列表
        import re
        src = re.sub(r"self\.test_name_list\s*=\s*\[.*?\]", "self.test_name_list = ['shot']", src, flags=re.S)
    for k, attr in (("steps", "num_inference_steps"), ("guidance", "guidance_scale"),
                    ("audio_guidance", "audio_guidance_scale"), ("seed", "seed"), ("fps", "fps")):
        if params.get(k) is not None:
            src = sub_src(src, attr, params[k])
    runner = os.path.join(d, "infer_req.py")
    io_write(runner, src)

    env = dict(os.environ)
    env["PYTHONUNBUFFERED"] = "1"
    env["PYTHONPATH"] = REPO
    log = open(os.path.join(d, "run.log"), "wb")
    t0 = time.time()
    p = subprocess.Popen([PY, runner], cwd=REPO, stdout=log, stderr=subprocess.STDOUT, env=env)
    rc = p.wait()
    log.close()
    if rc != 0:
        raise RuntimeError("EchoMimic 推理失败(rc=%d)：%s" % (rc, io_read(os.path.join(d, "run.log"))[-1500:]))

    # 产物：outputs/<ts>_gs*/shot_audio.mp4（带音轨的那份）
    outdir = os.path.join(REPO, "outputs")
    cands = []
    for root, _dirs, files in os.walk(outdir):
        for n in files:
            if n.endswith(".mp4"):
                cands.append(os.path.join(root, n))
    cands = [c for c in cands if os.path.getmtime(c) >= t0 - 5]
    if not cands:
        raise RuntimeError("找不到推理产物（outputs/ 下无新 mp4）")
    with_audio = [c for c in cands if c.endswith("_audio.mp4")] or cands
    raw = max(with_audio, key=os.path.getmtime)
    return raw, d, aud, time.time() - t0


def io_read(p):
    with open(p, "r", encoding="utf-8") as fh:
        return fh.read()


def io_write(p, s):
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(s)


def sub_src(src, attr, value):
    import re
    return re.sub(r"self\.%s\s*=\s*[^\n]+" % attr, "self.%s = %r" % (attr, value), src, count=1)


# ---------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "weaveora-talk/1.0"

    def log_message(self, fmt, *args):     # 静默（避免刷屏；进度看 run.log）
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            free = None
            try:
                import torch
                f, t = torch.cuda.mem_get_info()
                free = round(f / 2 ** 30, 1)
            except Exception:
                pass
            return self._json(200, {"ok": True, "device": "cuda", "vram_free_gb": free,
                                    "venv": PY, "repo": REPO, "face_aux": FACE_AUX})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/talk"):
            return self._json(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except Exception as e:
            return self._json(400, {"error": "bad json: %s" % e})
        try:
            ib = base64.b64decode(body.get("image_b64") or "")
            ab = base64.b64decode(body.get("audio_b64") or "")
            if not ib or not ab:
                return self._json(400, {"error": "image_b64 / audio_b64 必填"})
            gain = float(body.get("jaw_gain", 1.0))
            strength = float(body.get("jaw_strength", 0.35))
            att = float(body.get("jaw_attack_ms", 50))
            dec = float(body.get("jaw_decay_ms", 130))
            if not _LOCK.acquire(blocking=False):
                return self._json(429, {"error": "本机正在跑另一个 talk 任务（显存互斥），请稍后重试"})
            try:
                raw, workdir, audpath, secs = run_echo(ib, body.get("image_suffix") or ".jpg",
                                                       ab, body.get("audio_suffix") or ".wav", body)
                if abs(gain - 1.0) < 0.02:
                    out = raw
                    boost = {"applied": False, "gain": gain}
                else:
                    out = os.path.join(workdir, "out_boost.mp4")
                    boost = jaw_boost(raw, out, audpath, gain, strength, att, dec)
            finally:
                _LOCK.release()
            with open(out, "rb") as fh:
                vb = fh.read()
            return self._json(200, {"video_b64": base64.b64encode(vb).decode(),
                                    "meta": {"infer_seconds": round(secs, 1), "vram_free_gb": None,
                                             "raw": os.path.basename(raw), "boost": boost}})
        except Exception as e:
            return self._json(500, {"error": str(e)[:600]})


def main():
    os.makedirs(WORK, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("[talk] listening :%d  venv=%s  repo=%s" % (PORT, PY, REPO), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
