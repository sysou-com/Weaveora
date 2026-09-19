#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定案实验：只改画布尺寸（其余全部与失败那次逐字节相同）。

对照组 = /opt/weaveora/ComfyUI/output/weaveora_wf_shot4_00038_.png 里内嵌的 API 图
（同 prompt / 同 3 张参考图 / 同 seed 1635589662957602876 / 40步 / cfg4 / fp8mixed / denoise 1.0）。

P0 : 2560×1408（3.60MP，现状，重放基线）
P1 : 1664×928 （1.54MP，Qwen 官方 16:9 训练桶）
P2 : 1392×752 （1.05MP，ComfyUI 官方 Kontext 桶，≈官方「缩到 1MP」口径）

判据：输出 PNG 的「整行纯 0 黑带」行数（上/下）与内容带位置。
"""
import json, os, struct, sys, time, urllib.request

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
SRC = os.path.join(OUT, "weaveora_wf_shot4_00038_.png")
SEED = 1635589662957602876
SIZES = {"P0": (2560, 1408), "P1": (1664, 928), "P2": (1392, 752)}


def png_prompt(path):
    raw = open(path, "rb").read()
    i, meta = 8, {}
    while i < len(raw):
        ln = struct.unpack(">I", raw[i:i + 4])[0]
        typ = raw[i + 4:i + 8]
        if typ == b"tEXt":
            k, _, v = raw[i + 8:i + 8 + ln].partition(b"\x00")
            meta[k.decode()] = v.decode("utf8", "ignore")
        if typ == b"IDAT":
            break
        i += 12 + ln
    return json.loads(meta["prompt"])


def build(tag):
    w, h = SIZES[tag]
    g = png_prompt(SRC)
    g.pop("_comment", None)
    n = 0
    for _nid, node in g.items():
        if node.get("class_type") == "ImageScale":      # 画布唯一来源：ref1 → 缩放 → VAEEncode
            node["inputs"]["width"] = w
            node["inputs"]["height"] = h
            n += 1
        if node.get("class_type") == "KSampler":
            node["inputs"]["seed"] = SEED
            node["inputs"]["steps"] = 40
            node["inputs"]["cfg"] = 4.0
            node["inputs"]["denoise"] = 1.0
        if node.get("class_type") == "SaveImage":
            node["inputs"]["filename_prefix"] = "wvdiag_%s" % tag
    assert n == 1, "ImageScale 节点数=%d（预期 1）" % n
    return g


def submit(g):
    body = json.dumps({"prompt": g, "client_id": "wvcanvas"}).encode()
    req = urllib.request.Request(COMFY + "/prompt", data=body,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())["prompt_id"]


def wait(pid, limit=1500):
    t0 = time.time()
    while time.time() - t0 < limit:
        time.sleep(5)
        try:
            h = json.loads(urllib.request.urlopen(COMFY + "/history/" + pid, timeout=30).read())
        except Exception:
            continue
        if pid in h:
            return h[pid], time.time() - t0
    return None, time.time() - t0


def stats(p):
    import numpy as np
    from PIL import Image
    im = Image.open(p)
    a = np.asarray(im.convert("L"), dtype=np.float32)
    rmax = a.max(axis=1)
    top = 0
    for v in rmax:
        if v <= 1: top += 1
        else: break
    bot = 0
    for v in rmax[::-1]:
        if v <= 1: bot += 1
        else: break
    h = a.shape[0]
    return "size=%s 上黑=%d(%.0f%%) 下黑=%d(%.0f%%) 内容带=%.0f%%~%.0f%% λ=%.1f" % (
        im.size, top, 100.0 * top / h, bot, 100.0 * bot / h,
        100.0 * top / h, 100.0 * (h - bot) / h, a.mean())


def main():
    tags = sys.argv[1:] or ["P0", "P1", "P2"]
    for tag in tags:
        t0 = time.time()
        g = build(tag)
        with open("/tmp/diag/submitted_%s.json" % tag, "w") as fh:
            json.dump(g, fh, ensure_ascii=False)
        try:
            pid = submit(g)
        except Exception as e:
            print("[%s] SUBMIT FAIL %s" % (tag, e), flush=True)
            continue
        print("[%s] %s submitted %s" % (tag, SIZES[tag], pid), flush=True)
        res, el = wait(pid)
        if res is None:
            print("[%s] TIMEOUT %.0fs" % (tag, el), flush=True)
            continue
        line = "[%s] %s done in %.0fs" % (tag, SIZES[tag], el)
        for _nid, o in (res.get("outputs") or {}).items():
            for im in o.get("images", []):
                p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
                if os.path.exists(p):
                    line += "\n    %s  %s" % (p, stats(p))
        print(line, flush=True)
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()
