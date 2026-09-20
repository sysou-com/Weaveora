#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Weaveora 诊断：复现「警幻用了可卿的脸 + 上下黑边」并做消融（只读 + 新增输出，不改任何生产文件）。

用法（在 GPU 盒上）:
    /opt/weaveora/ComfyUI/venv/bin/python run_diag.py            # 跑全部变体
    /opt/weaveora/ComfyUI/venv/bin/python run_diag.py V0 V1      # 只跑指定变体

变体（全部固定同一 seed = 失败那次，唯一变量）:
    V0 : 失败图原样 + Lightning LoRA 4步/cfg1.5   ← 快速台架：能否复现黑边
    V1 : V0 且提示词去掉坐标/框（保留 Picture N 映射）
    V2 : V0 且参考图先缩到画布尺寸再进 TextEncode（修 latent 网格不一致）
    V3 : V0 且只用 2 张参考图（去掉警幻）
    B0 : 失败图原样（40步/cfg4，无 LoRA）= 40 步基准对照，~9.5min
    B1 : 40步/cfg4 + V1 的提示词改动
    B2 : 40步/cfg4 + V2 的网格对齐改动
"""
import json, os, re, struct, subprocess, sys, time, urllib.request

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
SRC_PNG = os.path.join(OUT, "weaveora_wf_shot4_00038_.png")
LORA = "Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16.safetensors"
SEED = 1635589662957602876
W, H = 2560, 1408


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


def nodes_of(g, ct):
    return [(k, v) for k, v in g.items() if v.get("class_type") == ct]


def add_lora(g):
    lid, node = nodes_of(g, "UNETLoader")[0]
    g["lora_main"] = {"class_type": "LoraLoaderModelOnly",
                      "inputs": {"model": [lid, 0], "lora_name": LORA,
                                 "strength_model": 1.0}}
    for nid, n in list(g.items()):
        if nid == "lora_main":
            continue
        for k, v in (n.get("inputs") or {}).items():
            if k == "model" and isinstance(v, list) and str(v[0]) == str(lid):
                n["inputs"][k] = ["lora_main", 0]


def strip_coords(text):
    """去掉全角括号里带 x=/框 的片段，以及含坐标说明的句子。"""
    text = re.sub(r"（[^）]*(x=|框|归一化)[^）]*）", "", text)
    keep = []
    for seg in text.split("。"):
        if ("x=" in seg) or ("框 " in seg) or ("位置以本清单为准" in seg) or ("保持明显分开" in seg):
            continue
        keep.append(seg)
    return "。".join(keep)


def align_refs(g):
    """image2/image3 也先缩到画布尺寸；image1 直接用已缩放的 13 号节点。"""
    for nid, n in nodes_of(g, "ImageScale"):
        if n["inputs"].get("image") == ["12", 0]:
            scaled1 = nid
    for idx, src in (("2", "14"), ("3", "16")):
        nid = "scale_%s" % src
        g[nid] = {"class_type": "ImageScale",
                  "inputs": {"image": [src, 0], "upscale_method": "lanczos",
                             "width": W, "height": H, "crop": "disabled"}}
        for te, _ in nodes_of(g, "TextEncodeQwenImageEditPlus"):
            if g[te]["inputs"].get("image%s" % idx) == [src, 0]:
                g[te]["inputs"]["image%s" % idx] = [nid, 0]
    for te, _ in nodes_of(g, "TextEncodeQwenImageEditPlus"):
        if g[te]["inputs"].get("image1") == ["12", 0]:
            g[te]["inputs"]["image1"] = [scaled1, 0]


def drop_ref3(g):
    for nid, n in list(g.items()):
        if n.get("class_type") == "LoadImage" and n["inputs"].get("image", "").startswith("a1ff5778"):
            g.pop(nid)
        if n.get("class_type") == "ImageScale" and n["inputs"].get("image") == ["16", 0]:
            g.pop(nid)
    for te, _ in nodes_of(g, "TextEncodeQwenImageEditPlus"):
        g[te]["inputs"]["image3"] = None  # 悬空 = 该槽不用
    for nid, n in list(g.items()):
        for k, v in (n.get("inputs") or {}).items():
            if isinstance(v, list) and v and str(v[0]) == "16":
                n["inputs"][k] = None


VARIANTS = {
    "V0": dict(lora=True, steps=4, cfg=1.5, prompt=None, align=False, refs3=True),
    "V1": dict(lora=True, steps=4, cfg=1.5, prompt="nocoord", align=False, refs3=True),
    "V2": dict(lora=True, steps=4, cfg=1.5, prompt=None, align=True, refs3=True),
    "V3": dict(lora=True, steps=4, cfg=1.5, prompt=None, align=False, refs3=False),
    "B0": dict(lora=False, steps=40, cfg=4.0, prompt=None, align=False, refs3=True),
    "B1": dict(lora=False, steps=40, cfg=4.0, prompt="nocoord", align=False, refs3=True),
    "B2": dict(lora=False, steps=40, cfg=4.0, prompt=None, align=True, refs3=True),
}


def build(tag):
    cfg = VARIANTS[tag]
    g = png_prompt(SRC_PNG)
    g.pop("_comment", None)
    if cfg["lora"]:
        add_lora(g)
    if cfg["prompt"] == "nocoord":
        for nid, n in nodes_of(g, "TextEncodeQwenImageEditPlus"):
            n["inputs"]["prompt"] = strip_coords(n["inputs"]["prompt"])
    if cfg["align"]:
        align_refs(g)
    if not cfg["refs3"]:
        drop_ref3(g)
    for nid, n in nodes_of(g, "KSampler"):
        n["inputs"]["seed"] = SEED
        n["inputs"]["steps"] = cfg["steps"]
        n["inputs"]["cfg"] = cfg["cfg"]
    for nid, n in nodes_of(g, "SaveImage"):
        n["inputs"]["filename_prefix"] = "wvdiag_%s" % tag
    return g


def submit(g):
    body = json.dumps({"prompt": g, "client_id": "wvdiag"}).encode()
    req = urllib.request.Request(COMFY + "/prompt", data=body,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())["prompt_id"]


def wait(pid, limit=1800):
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


def bars(p):
    import numpy as np
    from PIL import Image
    a = np.asarray(Image.open(p).convert("L"), dtype=np.float32)
    rmax = a.max(axis=1)
    top = 0
    for v in rmax:
        if v <= 1: top += 1
        else: break
    bot = 0
    for v in rmax[::-1]:
        if v <= 1: bot += 1
        else: break
    return top, bot, round(float(a.mean()), 1)


def main():
    tags = sys.argv[1:] or list(VARIANTS)
    for tag in tags:
        t00 = time.time()
        try:
            g = build(tag)
        except Exception as e:
            print("[%s] BUILD FAIL %s" % (tag, e), flush=True)
            continue
        # 存一份实际提交的图，便于事后逐字节对照
        with open("/tmp/diag/submitted_%s.json" % tag, "w") as fh:
            json.dump(g, fh, ensure_ascii=False)
        try:
            pid = submit(g)
        except Exception as e:
            print("[%s] SUBMIT FAIL %s" % (tag, e), flush=True)
            continue
        print("[%s] submitted %s" % (tag, pid), flush=True)
        res, el = wait(pid)
        if res is None:
            print("[%s] TIMEOUT after %.0fs" % (tag, el), flush=True)
            continue
        files = []
        for _nid, o in (res.get("outputs") or {}).items():
            for im in o.get("images", []):
                files.append(os.path.join(OUT, im.get("subfolder", ""), im["filename"]))
        line = "[%s] done in %.0fs  files=%s" % (tag, el, files)
        for f in files:
            if os.path.exists(f) and f.endswith(".png"):
                try:
                    line += "  (上黑=%d 下黑=%d λ=%.1f)" % bars(f)
                except Exception as e:
                    line += "  (stat fail %s)" % e
        print(line, flush=True)
    print("ALL DONE total %.0fs" % (time.time() - t00), flush=True)


if __name__ == "__main__":
    main()
