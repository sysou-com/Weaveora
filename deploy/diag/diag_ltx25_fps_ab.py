#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P4 标定：LTX-2.5 出片 24fps vs 48fps（官方 latent 时间轴 ×2）裸跑 A/B。

只做一件事：**同一张关键帧 / 同一段正词 / 同一尺寸 / 同一时长 / 同一随机种子**，
分别跑 `ltx25_i2v_api.json`（24fps）与 `ltx25_i2v_48fps_api.json`（48fps），
量出「耗时 / 峰值显存 / 输出帧数与帧率」。

纪律（照 worker 的做法，不是自己拼图）：
  * 注入点与 `comfy_client._motion_ltx25()` 逐行相同（否则量出来的数字不能代表生产）；
  * 顶层 `_comment` 必须 pop（ComfyUI 会 aiohttp 500 —— 五段坑 6）；
  * 跑前 `POST /free` 卸常驻缓存（LTX 峰值贴边，五段坑 8）。

用法（在 GPU 盒上）：
  python3 diag_ltx25_fps_ab.py <input_image.png> <prompt_file>
输出：stdout + JSON 汇总（可直接贴进交接文档）。
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

COMFY = os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8001").rstrip("/")
WF24 = "/opt/weaveora/workflows/ltx25_i2v_api.json"
WF48 = "/opt/weaveora/workflows/ltx25_i2v_48fps_api.json"
W, H, DUR, SEED = 1280, 704, 5, 42
POLL = 2.0
TIMEOUT = 3600.0


def _post(path, obj, timeout=60):
    req = urllib.request.Request(COMFY + path,
                                 data=json.dumps(obj).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8") or "{}"
        try:
            return r.status, json.loads(body)
        except ValueError:
            return r.status, {"_raw": body[:300]}


def _get(path, timeout=60):
    with urllib.request.urlopen(COMFY + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8") or "{}")


def free_vram():
    try:
        st, js = _post("/free", {"unload_models": True, "free_memory": True})
        print("[p4] /free -> %s %s" % (st, js), flush=True)
    except Exception as e:
        print("[p4] /free 失败（继续）：%s" % e, flush=True)
    time.sleep(8)


def vram_used_mib():
    try:
        out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            stderr=subprocess.STDOUT)
        return int(out.decode().strip().splitlines()[0])
    except Exception:
        return -1


def _vram_watch(stop, box):
    while not stop.is_set():
        box["peak"] = max(box["peak"], vram_used_mib())
        stop.wait(2.0)


def build(wf_path, prefix, pos, neg, img):
    with open(wf_path, encoding="utf-8") as fh:
        g = json.load(fh)
    g.pop("_comment", None)
    g["395"]["inputs"]["image"] = img
    g["376"]["inputs"]["value"] = pos
    if neg:
        g["373"]["inputs"]["text"] = neg
    g["339"]["inputs"]["noise_seed"] = SEED
    g["338"]["inputs"]["noise_seed"] = (SEED + 1) % (2 ** 63 - 1)
    g["362"]["inputs"]["value"] = DUR
    g["361"]["inputs"]["value"] = 24
    g["372"]["inputs"]["value"] = W
    g["360"]["inputs"]["value"] = H
    g["75"]["inputs"]["filename_prefix"] = prefix
    return g


def outputs_of(rec):
    files = []
    for node in (rec.get("outputs") or {}).values():
        for key in ("images", "gifs", "videos"):
            for it in (node.get(key) or []):
                files.append(it)
    return files


def ffprobe(path):
    try:
        out = subprocess.check_output(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
             "-show_entries", "stream=nb_read_frames,avg_frame_rate,r_frame_rate,width,height",
             "-show_entries", "format=duration", "-of", "json", path],
            stderr=subprocess.STDOUT)
        d = json.loads(out.decode())
        s = (d.get("streams") or [{}])[0]
        return {"frames": int(s.get("nb_read_frames") or 0),
                "avg_fps": s.get("avg_frame_rate"), "r_fps": s.get("r_frame_rate"),
                "size": "%sx%s" % (s.get("width"), s.get("height")),
                "dur": round(float((d.get("format") or {}).get("duration") or 0), 3)}
    except Exception as e:
        return {"error": str(e)[:200]}


def run(tag, wf_path, pos, neg, img):
    prefix = "p4_%s" % tag
    print("\n[p4] ===== %s：%s =====" % (tag, os.path.basename(wf_path)), flush=True)
    g = build(wf_path, prefix, pos, neg, img)
    free_vram()
    box = {"peak": vram_used_mib()}
    stop = threading.Event()
    th = threading.Thread(target=_vram_watch, args=(stop, box))
    th.daemon = True
    th.start()
    t0 = time.time()
    try:
        st, js = _post("/prompt", {"prompt": g, "client_id": "p4_" + tag})
    except Exception as e:
        stop.set()
        return {"tag": tag, "ok": False, "error": "POST /prompt: %s" % e}
    pid = js.get("prompt_id")
    if not pid:
        stop.set()
        return {"tag": tag, "ok": False, "error": "无 prompt_id: %s" % json.dumps(js)[:300]}
    last = 0.0
    rec = None
    while True:
        time.sleep(POLL)
        try:
            h = _get("/history/%s" % pid)
        except Exception:
            continue
        if pid in h:
            rec = h[pid]
            stt = (rec.get("status") or {}).get("status_str")
            if stt in ("success", "error") or rec.get("outputs"):
                break
        el = time.time() - t0
        if el > TIMEOUT:
            stop.set()
            return {"tag": tag, "ok": False, "error": "超时 %.0fs" % el}
        if el - last >= 30:
            last = el
            print("[p4]   …%.0fs 峰值显存 %d MiB" % (el, box["peak"]), flush=True)
    wall = time.time() - t0
    stop.set()
    time.sleep(0.2)
    stt = ((rec or {}).get("status") or {}).get("status_str")
    prog = ((rec or {}).get("status") or {}).get("messages") or []
    res = {"tag": tag, "ok": stt == "success", "status": stt, "wall_sec": round(wall, 1),
           "peak_vram_mib": box["peak"]}
    files = outputs_of(rec or {})
    for it in files:
        p = os.path.join("/opt/weaveora/ComfyUI/output", it.get("subfolder") or "", it.get("filename") or "")
        res["output"] = p
        res["output_bytes"] = os.path.getsize(p) if os.path.exists(p) else 0
        if os.path.exists(p) and p.lower().endswith(".mp4"):
            res["probe"] = ffprobe(p)
        break
    if not res["ok"]:
        res["messages"] = [str(m)[:200] for m in prog[-4:]]
    print("[p4] %s -> %s" % (tag, json.dumps(res, ensure_ascii=False)), flush=True)
    return res


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    img = os.path.basename(sys.argv[1])
    pos = open(sys.argv[2], encoding="utf-8").read().strip()
    print("[p4] comfy=%s image=%s %dx%d dur=%ds seed=%d" % (COMFY, img, W, H, DUR, SEED), flush=True)
    print("[p4] 正词长度 %d 字" % len(pos), flush=True)
    stats = _get("/system_stats")
    for d in stats.get("devices", []):
        print("[p4] %s total=%d MiB" % (d.get("name"), d.get("vram_total", 0) // 1024 // 1024), flush=True)
    out = []
    only = (sys.argv[3] if len(sys.argv) > 3 else "").strip().lower()
    for tag, wf in (("24fps", WF24), ("48fps", WF48)):
        if only and only not in tag:
            continue
        out.append(run(tag, wf, pos, "", img))
    print("\n[p4] ===== SUMMARY =====", flush=True)
    print(json.dumps(out, ensure_ascii=False, indent=1), flush=True)
    print("[p4] %s" % json.dumps(
        {r["tag"]: {"sec": r.get("wall_sec"), "peak_gib": round((r.get("peak_vram_mib") or 0) / 1024.0, 1),
                    "frames": (r.get("probe") or {}).get("frames"),
                    "fps": (r.get("probe") or {}).get("avg_fps")} for r in out},
        ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
