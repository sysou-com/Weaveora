#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LTX-2.5「配音能不能喂进去」A/B 标定（2026-09-23）。

问题（用户）：现在出的 LTX 视频没有语音、也没有口型 —— 因为我们的
`ltx25_i2v_api.json` 用的是 `LTXVEmptyLatentAudio`（**空**音频 latent），
LTX 只是"自己凭空生成"一条音轨（且 worker 默认 `_strip_audio` 把它剥掉）。

官方依据（本地就有）：`comfyui_workflow_templates_json/templates/video_ltx2_3_ia2v.json`
（图+音→视频）的接法正是：
    LoadAudio → TrimAudioDuration(0, duration) → LTXVAudioVAEEncode(audio, audio_vae)
        → LTXVConcatAVLatent(video_latent, audio_latent)
而且它的两段 sigma 排程与我们的 2.5 i2v **逐字相同**
（stage1 `1.0, 0.99375, …, 0.0`；stage2 `0.85, 0.7250, 0.4219, 0.0`）
⇒ 可以就地改造（`ltx25_i2v_audio_api.json`），不需要新权重。

本脚本做两件事：
  A 基线：`ltx25_i2v_api.json`（空音频，LTX 自生成音轨）
  B 条件：`ltx25_i2v_audio_api.json`（把**真实配音**编码成 audio latent 喂进去）
并客观量化三组数：耗时 / 峰值显存 / **输出音轨与输入配音的相似度**（RMS 包络相关
+ 时移搜索：最佳时移与相关系数——相关系数高说明音还是我们那条；最佳时移≈0 说明同步）。

用法（GPU 盒上，**用 ComfyUI 的 venv python 跑**——系统 python3 没有 numpy）：
  /opt/weaveora/ComfyUI/venv/bin/python diag_ltx25_audio_cond.py <关键帧.png> <正词文件> <配音.wav> [时长秒=4]

⚠ 2026-09-23 实测结论（负结果，别重复踩）：
  空音频基线 169.3s / 43.8GiB / 97 帧 → 输出音轨与输入配音相关 **0.228**（无关，模型自生成）
  配音 latent 131.1s / 43.8GiB / 97 帧 → 相关 **0.068**（同样无关）
  ⇒ 单纯把配音编码进 audio latent **不能**让 LTX 说我们的配音：该 latent 在 stage1 是**从 sigma=1.0
  一起去噪**的（与官方 video_ltx2_3_ia2v 同构），模型会把它重新生成。官方 ia2v 另外挂了
  distilled LoRA / 音频驱动 LoRA，且 A2Vid 是「音频冻结」的两段式 —— 那批权重我们没有。
  要真正音频驱动 → 走 A2Vid/DubIt（见 docs/方案-LTX2-A2Vid与DubIt评估-2026-09-23.md）。

"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

COMFY = os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8001").rstrip("/")
WF_BASE = "/opt/weaveora/workflows/ltx25_i2v_api.json"
WF_AUD = "/opt/weaveora/workflows/ltx25_i2v_audio_api.json"
IN_DIR = "/opt/weaveora/ComfyUI/input"
OUT_DIR = "/opt/weaveora/ComfyUI/output"
W, H, SEED = 1280, 704, 42
PAD_WAV = "p4_tts_padded.wav"
POLL, TIMEOUT = 2.0, 3600.0


def _post(path, obj, timeout=60):
    req = urllib.request.Request(COMFY + path, data=json.dumps(obj).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8") or "{}"
        try:
            return r.status, json.loads(body)
        except ValueError:
            return r.status, {"_raw": body[:400]}


def _get(path, timeout=60):
    with urllib.request.urlopen(COMFY + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8") or "{}")


def free_vram():
    try:
        _post("/free", {"unload_models": True, "free_memory": True})
    except Exception as e:
        print("[p6] /free 失败（继续）：%s" % e, flush=True)
    time.sleep(8)


def vram_used():
    try:
        return int(subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            stderr=subprocess.STDOUT).decode().strip().splitlines()[0])
    except Exception:
        return -1


def _watch(stop, box):
    while not stop.is_set():
        box["peak"] = max(box["peak"], vram_used())
        stop.wait(2.0)


def ff(*a):
    return subprocess.run(a, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def probe(path):
    try:
        out = subprocess.check_output(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
             "-show_entries", "stream=nb_read_frames,avg_frame_rate,width,height",
             "-show_entries", "format=duration", "-of", "json", path], stderr=subprocess.STDOUT)
        d = json.loads(out.decode())
        s = (d.get("streams") or [{}])[0]
        has_audio = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a",
                                    "-show_entries", "stream=codec_name", "-of", "csv=p=0", path],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode().strip()
        return {"frames": int(s.get("nb_read_frames") or 0), "fps": s.get("avg_frame_rate"),
                "size": "%sx%s" % (s.get("width"), s.get("height")),
                "dur": round(float((d.get("format") or {}).get("duration") or 0), 3),
                "audio_codec": has_audio or None}
    except Exception as e:
        return {"error": str(e)[:200]}


def to_pcm(path):
    """解码成 24k 单声道 s16le PCM（音频轨；没有音轨返回 None）。"""
    p = ff("ffmpeg", "-y", "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", "24000",
           "-f", "s16le", "-")
    if p.returncode != 0 or not p.stdout:
        return None
    return p.stdout


def envelope(pcm, hop=1200):   # 50ms @24k
    import numpy as np
    if pcm is None:
        return None
    a = np.frombuffer(pcm, dtype="<i2").astype("float32")
    n = len(a) // hop
    if n < 4:
        return None
    a = a[:n * hop].reshape(n, hop)
    return np.sqrt((a ** 2).mean(axis=1) + 1e-9)


def corr(x, y):
    import numpy as np
    m = min(len(x), len(y))
    x, y = x[:m], y[:m]
    x = x - x.mean()
    y = y - y.mean()
    d = float((x.std() * y.std()))
    return 0.0 if d < 1e-9 else float((x * y).mean() / d)


def compare(ref_wav, out_mp4):
    """输出音轨 vs 参考配音：最佳时移 + 该处的包络相关系数。"""
    try:
        import numpy  # noqa: F401
    except ImportError:
        return {"note": "缺 numpy：请用 /opt/weaveora/ComfyUI/venv/bin/python 跑本脚本"}
    import numpy as np
    r = envelope(to_pcm(ref_wav))
    o = envelope(to_pcm(out_mp4))
    if r is None or o is None:
        return {"note": "任一侧没有音频可解（或太短）"}
    res = {"ref_sec": round(len(r) * 0.05, 2), "out_sec": round(len(o) * 0.05, 2),
           "zero_lag_corr": round(corr(r, o), 3)}
    best, best_lag = corr(r, o), 0
    for lag in range(-8, 9):            # ±0.4s，步进 50ms
        if lag == 0:
            continue
        if lag > 0:
            c = corr(r[lag:], o)
        else:
            c = corr(r, o[-lag:])
        if c > best:
            best, best_lag = c, lag
    res["best_corr"] = round(best, 3)
    res["best_lag_ms"] = best_lag * 50
    res["verdict"] = ("音轨基本就是我们的配音" if best >= 0.6 else
                      ("部分保留/被重写" if best >= 0.3 else "与配音无关（模型自己生成）"))
    return res


def build(wf_path, prefix, img, pos, dur, wav=None, trim_node=None, load_node=None):
    g = json.load(open(wf_path, encoding="utf-8"))
    g.pop("_comment", None)
    g["395"]["inputs"]["image"] = img
    g["376"]["inputs"]["value"] = pos
    g["339"]["inputs"]["noise_seed"] = SEED
    g["338"]["inputs"]["noise_seed"] = (SEED + 1) % (2 ** 63 - 1)
    g["362"]["inputs"]["value"] = int(dur)
    g["361"]["inputs"]["value"] = 24
    g["372"]["inputs"]["value"] = W
    g["360"]["inputs"]["value"] = H
    g["75"]["inputs"]["filename_prefix"] = prefix
    if wav:                      # 音频条件变体：注入配音文件名 + 裁剪时长
        g[load_node]["inputs"]["audio"] = wav
        g[trim_node]["inputs"]["duration"] = float(dur)
    return g


def run(tag, wf_path, img, pos, dur, wav=None):
    print("\n[p6] ===== %s：%s =====" % (tag, os.path.basename(wf_path)), flush=True)
    g = build(wf_path, "p6_" + tag, img, pos, dur, wav, trim_node="401", load_node="400")
    free_vram()
    box = {"peak": vram_used()}
    stop = threading.Event()
    th = threading.Thread(target=_watch, args=(stop, box))
    th.daemon = True
    th.start()
    t0 = time.time()
    try:
        st, js = _post("/prompt", {"prompt": g, "client_id": "p6_" + tag})
    except Exception as e:
        import urllib.error
        body = ""
        if isinstance(e, urllib.error.HTTPError):
            body = e.read().decode("utf-8", "replace")[:600]
        stop.set()
        return {"tag": tag, "ok": False, "error": "POST /prompt: %s" % e, "detail": body}
    pid = js.get("prompt_id")
    if not pid:
        stop.set()
        return {"tag": tag, "ok": False, "error": json.dumps(js)[:300]}
    rec, last = None, 0.0
    while True:
        time.sleep(POLL)
        try:
            h = _get("/history/%s" % pid)
        except Exception:
            continue
        if pid in h:
            rec = h[pid]
            if (rec.get("status") or {}).get("status_str") in ("success", "error") or rec.get("outputs"):
                break
        el = time.time() - t0
        if el > TIMEOUT:
            stop.set()
            return {"tag": tag, "ok": False, "error": "超时"}
        if el - last >= 30:
            last = el
            print("[p6]   …%.0fs 峰值 %d MiB" % (el, box["peak"]), flush=True)
    wall = time.time() - t0
    stop.set()
    time.sleep(0.2)
    stt = ((rec or {}).get("status") or {}).get("status_str")
    res = {"tag": tag, "ok": stt == "success", "wall_sec": round(wall, 1), "peak_vram_mib": box["peak"]}
    for node in (rec or {}).get("outputs", {}).values():
        for key in ("images", "gifs", "videos"):
            for it in (node.get(key) or []):
                p = os.path.join(OUT_DIR, it.get("subfolder") or "", it.get("filename") or "")
                res["output"] = p
                if os.path.exists(p):
                    res["bytes"] = os.path.getsize(p)
                    if p.endswith(".mp4"):
                        res["probe"] = probe(p)
                break
            if res.get("output"):
                break
    print("[p6] %s -> %s" % (tag, json.dumps(res, ensure_ascii=False)), flush=True)
    return res


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    img, prompt_file, wav = (os.path.basename(sys.argv[1]), sys.argv[2], os.path.basename(sys.argv[3]))
    dur = float(sys.argv[4]) if len(sys.argv) > 4 else 4.0
    pos = open(prompt_file, encoding="utf-8").read().strip()

    # 把配音 pad/裁到**精确 dur 秒**，长度与视频（length = dur*24+1 帧 ≈ dur 秒）对齐
    src = os.path.join(IN_DIR, wav)
    dst = os.path.join(IN_DIR, PAD_WAV)
    p = ff("ffmpeg", "-y", "-v", "error", "-i", src, "-af", "apad", "-t", "%.3f" % dur,
           "-ar", "24000", "-ac", "1", dst)
    print("[p6] 配音 %s → %s（%.1fs，rc=%d）" % (wav, PAD_WAV, dur, p.returncode), flush=True)

    out = []
    out.append(run("empty_audio", WF_BASE, img, pos, dur))
    out.append(run("tts_audio", WF_AUD, img, pos, dur, wav=PAD_WAV))
    for r in out:
        if r.get("output") and os.path.exists(r["output"]):
            r["vs_tts"] = compare(dst, r["output"])

    print("\n[p6] ===== SUMMARY =====", flush=True)
    print(json.dumps(out, ensure_ascii=False, indent=1), flush=True)
    print("[p6] %s" % json.dumps({r["tag"]: {"sec": r.get("wall_sec"),
                                            "peak_gib": round((r.get("peak_vram_mib") or 0) / 1024.0, 1),
                                            "frames": (r.get("probe") or {}).get("frames"),
                                            "has_audio": (r.get("probe") or {}).get("audio_codec"),
                                            "corr_vs_tts": (r.get("vs_tts") or {}).get("best_corr"),
                                            "lag_ms": (r.get("vs_tts") or {}).get("best_lag_ms"),
                                            "verdict": (r.get("vs_tts") or {}).get("verdict")}
                                        for r in out}, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
