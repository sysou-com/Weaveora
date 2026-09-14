#!/usr/bin/env python3
"""Wan2.2 I2V-A14B 双专家 motion 验证 / 强度扫描（在 GPU 机器上执行）。

用法（GPU 机上，用 ComfyUI 的 venv python）：
  P=/home/dataset-local/weaveora/ComfyUI/venv/bin/python
  $P verify_wan_i2v_motion.py --preset draft --size 832x480 --frames 33
  $P verify_wan_i2v_motion.py --preset balanced --lora-high 0.0 --cfg-high 3.5
  $P verify_wan_i2v_motion.py --sweep draft,balanced,hero     # 一次扫多档
  $P verify_wan_i2v_motion.py --list                          # 只列档位，不跑

它做三件事：
  1) 用 worker/comfy_client.py 的**真实建图函数**（_motion_graph）出图 —— 验证的是
     真正会跑在生产上的代码路径，而不是另写一份 JSON。
  2) 采样期间每 0.5s 采一次**真实设备显存**（torch.cuda.mem_get_info），给出峰值；
     同时打印 ComfyUI 自述显存（/system_stats）。
  3) 每档打印一行 `WANMOTION {...}` JSON（便于批量扫描汇总）+ 一张人类可读表。

注意：脚本用的是合成测试图（渐变+圆），只能验证**链路/性能/显存**，
        运动幅度必须用真实关键帧肉眼或取帧比对来判（见 --keep 输出目录）。
"""
import argparse
import io
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.environ.get("WEAVEORA_WORKER_DIR", os.path.join(HERE, "worker"))
if WORKER not in sys.path:
    sys.path.insert(0, WORKER)

POS = ("cinematic medium shot, a woman in a red dress walks forward along a "
       "narrow stone alley, camera tracks with her, wind moves her hair, "
       "dramatic side light, film grain")
NEG = "blurry, low quality, static, frozen frame, watermark, text"


def _png(w, h, seed=7):
    """合成测试关键帧：渐变底 + 若干实心圆 + 一条斜线（有结构感，便于看出是否在动）。"""
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / float(max(1, h - 1))
        d.line([(0, y), (w, y)], fill=(int(30 + 120 * t), int(40 + 60 * t), int(90 + 40 * (1 - t))))
    import random
    rnd = random.Random(seed)
    for i in range(6):
        cx, cy = rnd.randint(0, w), rnd.randint(0, h)
        r = rnd.randint(w // 20, w // 7)
        d.ellipse([cx - r, cy - r, cx + r, cy + r],
                  fill=(rnd.randint(80, 255), rnd.randint(80, 255), rnd.randint(80, 255)))
    d.line([(0, 0), (w, h)], fill=(255, 255, 255), width=max(2, w // 200))
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return buf.getvalue()


def _multipart(name, data, filename):
    b = "----weaveora" + uuid.uuid4().hex
    head = ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
            "Content-Type: image/png\r\n\r\n" % (b, name, filename)).encode()
    tail = ("\r\n--%s--\r\n" % b).encode()
    return head + data + tail, "multipart/form-data; boundary=%s" % b


def _http(url, data=None, ctype=None, timeout=120):
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": ctype} if ctype else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def _vram_mb():
    try:
        import torch
        free, total = torch.cuda.mem_get_info()
        return free // 1048576, total // 1048576
    except Exception:
        return None, None


class _VramSampler(threading.Thread):
    """后台每 0.5s 记一次真实显存占用峰值（MiB used）。"""

    def __init__(self):
        threading.Thread.__init__(self)
        self.daemon = True
        self.stop = threading.Event()
        self.peak_used = 0
        self.first_used = None

    def run(self):
        while not self.stop.is_set():
            free, total = _vram_mb()
            if free is not None:
                used = (total - free)
                if self.first_used is None:
                    self.first_used = used
                if used > self.peak_used:
                    self.peak_used = used
            self.stop.wait(0.5)


def run_once(comfy, cc, args, preset, plan_overrides):
    params = {"preset": preset}
    params.update(plan_overrides)
    if args.size:
        params["width"], params["height"] = [int(x) for x in args.size.lower().split("x")]
    if args.shift is not None:
        params["shift"] = args.shift
    if args.cfg is not None:
        params["cfg_high"] = args.cfg
        params["cfg_low"] = args.cfg
    if args.lora_high is not None:
        params["lora_high"] = args.lora_high
    if args.lora_low is not None:
        params["lora_low"] = args.lora_low
    if args.steps is not None:
        params["steps"] = args.steps
    if args.switch is not None:
        params["switch_step"] = args.switch

    payload = {"fps": args.fps, "duration_sec": args.duration, "frames": args.frames,
               "seed": args.seed, "params": params,
               "positive_prompt": POS, "negative_prompt": NEG}

    w = int(params.get("width", 768)); h = int(params.get("height", 768))
    png = _png(w, h)
    body, ctype = _multipart("image", png, "verify_%dx%d.png" % (w, h))
    up = json.loads(_http(comfy + "/upload/image", body, ctype).decode())
    name = up.get("name")

    free0, total0 = _vram_mb()
    prompt = cc._motion_graph("verify-motion", payload, POS, NEG, name,
                              "vfy_" + uuid.uuid4().hex[:6])
    nodes = prompt["prompt"]

    t0 = time.time()
    sampler = _VramSampler()
    sampler.start()
    try:
        resp = json.loads(_http(comfy + "/prompt",
                                json.dumps(prompt).encode(),
                                "application/json").decode())
        pid = resp.get("prompt_id")
        if not pid:
            raise RuntimeError("no prompt_id: %s" % str(resp)[:300])
        while True:
            hist = json.loads(_http(comfy + "/history/" + pid).decode())
            if pid in hist:
                rec = hist[pid]
                break
            if time.time() - t0 > args.timeout:
                raise RuntimeError("超时 %ds 未完成" % args.timeout)
            time.sleep(1.5)
    finally:
        sampler.stop.set()
        sampler.join(timeout=3)
    dt = time.time() - t0

    status = (rec.get("status") or {})
    msgs = [m for m in (status.get("messages") or []) if m and m[0] == "execution_error"]
    if msgs:
        raise RuntimeError("ComfyUI 执行报错: %s" % json.dumps(msgs)[:600])

    frames = []
    for node in (rec.get("outputs") or {}).values():
        if isinstance(node, dict):
            for it in (node.get("images") or []):
                frames.append(it.get("filename"))
    if args.keep and frames:
        os.makedirs(args.keep, exist_ok=True)
        q = urllib.parse.urlencode({"filename": frames[0], "type": "output"})
        data = _http(comfy + "/view?" + q)
        with open(os.path.join(args.keep, "vfy_%s_%s.png" % (preset, args.tag)), "wb") as fh:
            fh.write(data)
    free1, _ = _vram_mb()

    plan = cc._motion_plan(params)
    out = {
        "preset": preset, "steps": plan["steps"], "switch": plan["switch"],
        "cfg_high": plan["cfg_high"], "cfg_low": plan["cfg_low"],
        "lora_high": plan["lora_high"], "lora_low": plan["lora_low"],
        "shift": plan["shift"], "size": "%dx%d" % (w, h), "frames": args.frames,
        "nodes": len(nodes), "seconds": round(dt, 1),
        "vram_used_before_mb": (None if free0 is None else total0 - free0),
        "vram_peak_mb": sampler.peak_used or None,
        "vram_after_mb": (None if free1 is None else _vram_mb()[1] - free1),
        "frames_out": len(frames), "first_frame": frames[0] if frames else None,
    }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--comfy", default=os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8001"))
    ap.add_argument("--preset", default="balanced")
    ap.add_argument("--sweep", default="")
    ap.add_argument("--steps", type=int)
    ap.add_argument("--switch", type=int)
    ap.add_argument("--cfg", type=float)
    ap.add_argument("--lora-high", dest="lora_high", type=float)
    ap.add_argument("--lora-low", dest="lora_low", type=float)
    ap.add_argument("--shift", type=float)
    ap.add_argument("--size", default="")
    ap.add_argument("--frames", type=int, default=33)
    ap.add_argument("--fps", type=int, default=16)
    ap.add_argument("--duration", type=float, default=2.1)
    ap.add_argument("--seed", type=int, default=20260914)
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--tag", default="t0")
    ap.add_argument("--keep", default="")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    os.environ["WEAVEORA_COMFY_URL"] = args.comfy
    import comfy_client as cc

    if args.list:
        for k in sorted(cc.MOTION_PRESETS):
            p = cc._motion_plan({"preset": k})
            print("WANMOTION_PRESET " + json.dumps(p, sort_keys=True))
        return 0

    presets = [x.strip() for x in args.sweep.split(",") if x.strip()] or [args.preset]
    free, total = _vram_mb()
    print("== 环境 ==")
    print("  comfy=%s  设备显存 %s/%s MiB" %
          (args.comfy, free, total))
    print("  模型高/低=%s | %s" % (cc.MOTION_MODEL_HIGH, cc.MOTION_MODEL_LOW))
    print("  LoRA高/低=%s | %s" % (cc.MOTION_LORA_HIGH, cc.MOTION_LORA_LOW))

    rows = []
    for p in presets:
        try:
            out = run_once(args.comfy, cc, args, p, {})
        except Exception as e:
            out = {"preset": p, "error": str(e)[:400]}
        print("WANMOTION " + json.dumps(out, sort_keys=True))
        rows.append(out)

    print("\n== 汇总 ==")
    print("%-9s %5s %7s %9s %9s %8s %8s %6s" %
          ("preset", "步数", "switch", "cfg高/低", "lora高/低", "分辨率", "显存峰值", "秒"))
    for r in rows:
        if r.get("error"):
            print("%-9s FAIL: %s" % (r["preset"], r["error"][:90]))
            continue
        print("%-9s %5d %7d %9s %9s %8s %8s %6.1f" %
              (r["preset"], r["steps"], r["switch"],
               "%s/%s" % (r["cfg_high"], r["cfg_low"]),
               "%s/%s" % (r["lora_high"], r["lora_low"]),
               r["size"], "%sMiB" % r["vram_peak_mb"], r["seconds"]))
    return 0 if all("error" not in r for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
