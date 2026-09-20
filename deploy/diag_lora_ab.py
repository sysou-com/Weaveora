#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Qwen-Image-Edit-2511 **LoRA 提速 A/B**（同镜、同 seed、同参考图，只改「步数 + LoRA」）。

背景（2026-09-19，用户点名"渲染速度"是主要问题）：
  2K(2560x1408) / 40 步 / cfg 4.0 一张关键帧实测 **435~476s（7~8 分钟）**。
  官方蒸馏 LoRA（lightx2v/Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16，810MB，Apache-2.0）
  把采样步数降到 4 —— 官方 ComfyUI 模板就当标配用，且 **CFG 仍是 4.0、LoRA 强度 1.0**。

跑法（在 VPS 上；跑前必须查 generation_jobs 有没有 queued/running —— 有就别跑）：
    python3 diag_lora_ab.py \
        --payload shot4_ab.json --wf qwen_image_edit_api.json \
        --storage /opt/weaveora/data/storage \
        --gateway http://180.127.11.167:15276 \
        --out /opt/weaveora/_xfer/lora_ab \
        --arms "40:lora0,8:lora,4:lora"

判据（脚本只出**时间/尺寸/亮度**这些客观量；脸宽+身份相似度另外在盒子用 insightface 量）：
  · 时间：4 步应该把采样阶段压到 1/10 左右（VAE/文本编码是固定开销，不会等比例降）
  · 亮度：历史上"黑图"是显存压力导致的，不是步数；若某档亮度暴跌 → 该档不可用
  · 体积：噪声图/糊图 PNG 体积异常（偏大或偏小）都要看

只依赖 py3 标准库；直连 ComfyUI 网关，不经过业务 API、不写业务库。
"""

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

CTX = ssl._create_unverified_context()
CFG = 4.0


def _req(method, url, data=None, headers=None, timeout=1800):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read()


def get_json(url, timeout=60):
    return json.loads(_req("GET", url, timeout=timeout).decode())


def post_json(url, obj, timeout=1800):
    body = json.dumps(obj).encode()
    return json.loads(_req("POST", url, data=body,
                           headers={"Content-Type": "application/json"}, timeout=timeout).decode())


def upload_image(gateway, path, timeout=600):
    """把本地图上传到 ComfyUI input/，返回它给的 filename。

    ★ 用 curl 子进程（不是手写 multipart）：2026-09-19 实测手写的 multipart 被网关注回
    400 Bad Request，而 `curl -F image=@f` 直传同一条路径 200 正常 —— 不值得为这点再调编码。
    另外：worker 跑同一条任务时已经把参考图传到 input/ 了（同名），所以失败时回退用 basename，
    大多数情况下它已经存在、照样能用。
    """
    name = os.path.basename(path)
    try:
        import subprocess
        out = subprocess.check_output(["curl", "-s", "-m", str(int(timeout)),
                                       "-F", "image=@%s" % path,
                                       gateway.rstrip("/") + "/upload/image"],
                                      stderr=subprocess.DEVNULL).decode()
        return json.loads(out).get("name") or name
    except Exception as e:
        print("  （上传失败 %s，改用 basename %s）" % (e.__class__.__name__, name))
        return name


def inject_lora(graph, name, strength=1.0):
    """与 worker/comfy_client.py 的 _wf_inject_lora 同口径：插 LoraLoaderModelOnly 并改写 model 引用。"""
    graph["lora_main"] = {"class_type": "LoraLoaderModelOnly",
                          "inputs": {"model": ["1", 0], "lora_name": name,
                                     "strength_model": float(strength)}}
    for nid, n in list(graph.items()):
        if nid == "lora_main":
            continue
        for k, v in (n.get("inputs") or {}).items():
            if k == "model" and isinstance(v, list) and len(v) >= 2 and str(v[0]) == "1":
                n["inputs"][k] = ["lora_main", 0]
    return True


def mean_brightness(png_bytes):
    """不解码整图：PNG 压缩后均值不准，所以只用 PIL（有就量，没有就跳过）。"""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(png_bytes)).convert("L")
        im.thumbnail((256, 256))
        px = list(im.getdata())
        return sum(px) / max(1, len(px))
    except Exception:
        return None


def run_arm(gateway, wf, payload, ref_names, size, arm, out_dir):
    tag, steps, use_lora = arm
    graph = json.loads(json.dumps(wf))          # 深拷贝
    graph.pop("_comment", None)
    graph["6"]["inputs"]["prompt"] = payload["positive"]
    graph["7"]["inputs"]["prompt"] = payload["negative"]
    for i, (nid, nm) in enumerate(zip(("12", "14", "16"), list(ref_names) + [None] * 3)):
        if nm:
            graph[nid]["inputs"]["image"] = nm
        else:
            # ★ 参考图比槽位少时，除了删 LoadImage 节点，**还必须删掉文本编码器里对它的引用**
            #   （否则 ComfyUI 收到悬空引用 → HTTP 400 Bad Request，2026-09-19 实测）
            graph.pop(nid, None)
            for enc in ("6", "7"):
                (graph.get(enc, {}).get("inputs") or {}).pop("image%d" % (i + 1), None)
    graph["13"]["inputs"]["width"] = int(size["width"])
    graph["13"]["inputs"]["height"] = int(size["height"])
    ks = graph["9"]["inputs"]
    ks["seed"] = int(payload["seed"])
    ks["steps"] = int(steps)
    ks["cfg"] = float(CFG)
    ks["denoise"] = 1.0
    if use_lora:
        inject_lora(graph, payload["lora_name"], 1.0)
    graph["11"]["inputs"]["filename_prefix"] = "wvab_%s" % tag

    t0 = time.time()
    r = post_json(gateway.rstrip("/") + "/prompt", {"prompt": graph, "client_id": "wv-lora-%s" % tag})
    pid = r.get("prompt_id")
    if not pid:
        print("  [%s] ✗ 提交失败：%s" % (tag, str(r)[:200]))
        return None
    # 轮询 history
    while True:
        if time.time() - t0 > 2400:
            print("  [%s] ✗ 超时 40 分钟" % tag)
            return None
        h = get_json(gateway.rstrip("/") + "/history/" + pid)
        rec = h.get(pid) if isinstance(h, dict) else None
        if rec and rec.get("outputs"):
            break
        time.sleep(3)
    el = time.time() - t0
    imgs = []
    for node in (rec.get("outputs") or {}).values():
        for it in (node.get("images") or []):
            imgs.append(it)
    if not imgs:
        print("  [%s] ✗ 无输出图" % tag)
        return None
    it = imgs[0]
    q = urllib.parse.urlencode({"filename": it.get("filename", ""),
                                "subfolder": it.get("subfolder", ""),
                                "type": it.get("type", "output")})
    blob = _req("GET", gateway.rstrip("/") + "/view?" + q, timeout=300)
    fn = os.path.join(out_dir, "%s_steps%d%s.png" % (tag, steps, "_lora" if use_lora else ""))
    with open(fn, "wb") as fh:
        fh.write(blob)
    br = mean_brightness(blob)
    print("  [%-7s] steps=%-3s LoRA=%-4s  用时 %6.1fs  输出 %.2fMB  亮度 %s  → %s"
          % (tag, steps, "on" if use_lora else "off", el, len(blob) / 1048576.0,
             ("%.1f" % br) if br else "-", fn))
    return {"tag": tag, "steps": steps, "lora": use_lora, "sec": el, "bytes": len(blob),
            "brightness": br, "path": fn}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload", required=True, help="从 generation_jobs 导出的 payload JSON")
    ap.add_argument("--wf", required=True, help="qwen_image_edit_api.json 路径")
    ap.add_argument("--storage", default="/opt/weaveora/data/storage")
    ap.add_argument("--gateway", default="http://180.127.11.167:15276")
    ap.add_argument("--out", default="/opt/weaveora/_xfer/lora_ab")
    ap.add_argument("--arms", default="40:lora0,8:lora,4:lora",
                    help="tag:lora|lora0 的逗号列表；steps 取 tag 里的数字")
    ap.add_argument("--cfg", type=float, default=4.0)
    ap.add_argument("--lora-name",
                    default="Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16.safetensors")
    a = ap.parse_args()

    payload = json.load(open(a.payload, encoding="utf-8"))
    payload["lora_name"] = a.lora_name
    global CFG
    CFG = float(a.cfg)
    wf = json.load(open(a.wf, encoding="utf-8"))
    size = payload.get("size") or {}
    size = {"width": size.get("width", 2560), "height": size.get("height", 1408)}

    os.makedirs(a.out, exist_ok=True)
    # 参考图：上传到 ComfyUI input/
    ref_names = []
    for i, key in enumerate((payload.get("refs") or [])[:3]):
        p = os.path.join(a.storage, key)
        if not os.path.exists(p):
            print("  ⚠ 参考图不存在：%s" % p)
            ref_names.append(None)
            continue
        nm = upload_image(a.gateway, p)
        print("  ref#%d 上传 → %s" % (i + 1, nm))
        ref_names.append(nm)
    ref_names = [x for x in ref_names if x]

    print("\n=== 第4镜 A/B：seed=%s size=%dx%d refs=%d ===" % (payload["seed"], size["width"], size["height"], len(ref_names)))
    print("prompt(前120字)：%s…\n" % payload["positive"][:120])
    rows = []
    for spec in a.arms.split(","):
        spec = spec.strip()
        if not spec:
            continue
        tag, _, how = spec.partition(":")
        try:
            steps = int("".join(ch for ch in tag if ch.isdigit()) or 0)
        except ValueError:
            steps = 0
        r = run_arm(a.gateway, wf, payload, ref_names, size, (tag, steps, how.strip() == "lora"), a.out)
        if r:
            rows.append(r)
    if rows:
        base = next((x for x in rows if not x["lora"]), None)
        print("\n=== 汇总 ===")
        for x in rows:
            sp = ("%.2f× 提速" % (base["sec"] / x["sec"])) if (base and x["sec"] > 0) else "-"
            print("  %-8s steps=%-3s LoRA=%-4s %6.1fs  %s" % (x["tag"], x["steps"],
                                                             "on" if x["lora"] else "off", x["sec"], sp))
    return 0


if __name__ == "__main__":
    sys.exit(main())
