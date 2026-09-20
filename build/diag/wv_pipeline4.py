#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Weaveora · 4 步关键帧流水线（布局遍 → 细化遍 → 脸部重绘 → [SeedVR2 另跑]）
在 GPU 盒上跑：/root/venv/bin/python wv_pipeline4.py <源图 PNG> [--no-face]
只用 HTTP 与 ComfyUI 通信；脸检测/裁剪/贴回用 insightface + PIL。
"""
import base64, io, json, os, re, shutil, struct, sys, time, urllib.request

COMFY = "http://127.0.0.1:8001"
OUT = "/opt/weaveora/ComfyUI/output"
INP = "/opt/weaveora/ComfyUI/input"
LORA4 = "Qwen-Image-Edit-2511-Lightning-4steps-V1.0-bf16.safetensors"
W, H = 1664, 928
SEED = None            # None = 沿用源图 seed
FACE_SRC = ""       # placeholder

# ---------------------------------------------------------------- ComfyUI API
def api(path, body=None, timeout=60):
    url = COMFY + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def submit(graph):
    r = api("/prompt", {"prompt": graph, "client_id": "wv4"})
    return r["prompt_id"]


def wait(pid, limit=2400):
    t0 = time.time()
    while time.time() - t0 < limit:
        time.sleep(4)
        try:
            h = api("/history/" + pid, timeout=30)
        except Exception:
            continue
        if pid in h:
            return h[pid], time.time() - t0
    return None, time.time() - t0


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


def add_lora(g, name, strength=1.0):
    lid = nodes_of(g, "UNETLoader")[0][0]
    g["lora_main"] = {"class_type": "LoraLoaderModelOnly",
                      "inputs": {"model": [lid, 0], "lora_name": name, "strength_model": strength}}
    for nid, n in list(g.items()):
        if nid == "lora_main":
            continue
        for k, v in (n.get("inputs") or {}).items():
            if k == "model" and isinstance(v, list) and str(v[0]) == str(lid):
                n["inputs"][k] = ["lora_main", 0]


def set_sampler(g, steps, cfg, denoise, seed=None):
    for _nid, n in nodes_of(g, "KSampler"):
        n["inputs"]["steps"] = steps
        n["inputs"]["cfg"] = cfg
        n["inputs"]["denoise"] = denoise
        if seed is not None:
            n["inputs"]["seed"] = seed


def pos_neg_ids(g):
    """从 KSampler 反查 positive / negative 的 TextEncode 节点 id。"""
    _kid, ks = nodes_of(g, "KSampler")[0]
    return str(ks["inputs"]["positive"][0]), str(ks["inputs"]["negative"][0])


def set_prompts(g, positive, negative):
    """只给 KSampler 真正接的那两个节点写词（绝不把正词写进负词节点）。"""
    pid, nid = pos_neg_ids(g)
    g[pid]["inputs"]["prompt"] = positive
    g[nid]["inputs"]["prompt"] = negative


def trim_prompt(p):
    """去掉数字坐标/框 + 去掉方括号里的"体态/外貌/服饰"文字断言（保留性别/年龄）。"""
    p = re.sub(r"（[^）]*(x=|框|归一化)[^）]*）", "", p)

    def fix_bracket(m):
        inner = m.group(1)
        keep = [seg for seg in inner.split("；")
                if not re.match(r"^\s*(体态|外貌|服饰|外貌/服饰|性格|身高)\s*", seg)]
        return "[" + "；".join(keep) + "]"

    p = re.sub(r"\[([^\]]*)\]", fix_bracket, p)
    return p


def refine_prompt(pos_text):
    """细化遍：保留 Picture 映射与相对位置，但强调"继承构图/位置 + 身份以参考图为准"。"""
    head = ("镜头语言与氛围完全沿用上一张图：构图、人物位置、朝向、景别、光线、色调一律不变。"
            "只做“细化”：把每个人物的面部五官、皮肤质感、发丝、服饰纹样与材质画得更清晰锐利；"
            "每个角色的身份、脸型、发型、服饰必须严格以其参考图（Picture N）为准，禁止改动、禁止互换。"
            "禁止新增人物、禁止改变人数、禁止出现画框/黑边/分格。")
    return head + "\n" + pos_text


def face_prompt():
    return ("以 image2 的人物身份为准，重绘 image1 这块面部区域：保持原始姿态、朝向、光照与肤色，"
            "只提升面部清晰度与五官细节（眼睛、眉毛、鼻梁、嘴唇、发际线）；"
            "不要改变表情幅度、不要改变构图、不要添加配饰、不要出现文字。")


# ---------------------------------------------------------------- 主流程
def main():
    src_png = sys.argv[1]
    no_face = "--no-face" in sys.argv
    t_start = time.time()
    summary = {"source": os.path.basename(src_png), "stages": []}

    g0 = png_prompt(src_png)
    g0.pop("_comment", None)
    refs = [v["inputs"].get("image") for k, v in sorted(nodes_of(g0, "LoadImage"), key=lambda x: int(x[0]))]
    pos = [v["inputs"]["prompt"] for _k, v in nodes_of(g0, "TextEncodeQwenImageEditPlus")]
    print("源图 refs=%s  pos_len=%d" % (refs, len(pos[0]) if pos else 0), flush=True)

    # ---------- 阶段 1：布局遍（Lightning 4 步 / cfg1） ----------
    g = json.loads(json.dumps(g0))
    add_lora(g, LORA4, 1.0)
    set_sampler(g, 4, 1.0, 1.0)
    _pid0, _nid0 = pos_neg_ids(g)
    orig_pos = g[_pid0]["inputs"]["prompt"]
    orig_neg = g[_nid0]["inputs"]["prompt"]
    set_prompts(g, trim_prompt(orig_pos), orig_neg)
    for _nid, n in nodes_of(g, "SaveImage"):
        n["inputs"]["filename_prefix"] = "wv4_s1"
    t0 = time.time()
    pid = submit(g)
    res, el = wait(pid)
    s1 = collect(res)
    print("[S1 布局遍] %.0fs -> %s" % (el, s1), flush=True)
    summary["stages"].append({"stage": "S1-layout", "secs": round(el, 1), "files": s1})

    # ---------- 阶段 2：细化遍（denoise 0.5 / 20 步 / cfg4，构图由 s1 像素锁死） ----------
    base = os.path.join(INP, "wv4_s1_base.png")
    shutil.copyfile(s1[0], base)
    g = json.loads(json.dumps(g0))
    g["wv4_base_img"] = {"class_type": "LoadImage", "inputs": {"image": "wv4_s1_base.png"}}
    g["wv4_base_scale"] = {"class_type": "ImageScale",
                           "inputs": {"image": ["wv4_base_img", 0], "upscale_method": "lanczos",
                                      "width": W, "height": H, "crop": "disabled"}}
    g["wv4_base_latent"] = {"class_type": "VAEEncode",
                            "inputs": {"pixels": ["wv4_base_scale", 0], "vae": ["3", 0]}}
    for _nid, n in nodes_of(g, "KSampler"):
        n["inputs"]["latent_image"] = ["wv4_base_latent", 0]
    set_sampler(g, 20, 4.0, 0.5)
    set_prompts(g, refine_prompt(trim_prompt(orig_pos)), orig_neg)
    for _nid, n in nodes_of(g, "SaveImage"):
        n["inputs"]["filename_prefix"] = "wv4_s2"
    t0 = time.time()
    pid = submit(g)
    res, el = wait(pid)
    s2 = collect(res)
    print("[S2 细化遍] %.0fs -> %s" % (el, s2), flush=True)
    summary["stages"].append({"stage": "S2-refine", "secs": round(el, 1), "files": s2})

    final = s2[0]
    if not no_face:
        final, face_info = face_stage(final, refs, g0)
        summary["stages"].append(face_info)
    summary["final"] = final
    summary["total_secs"] = round(time.time() - t_start, 1)
    with open("/tmp/diag/wv4_summary.json", "w") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=1)
    print("[DONE] %.0fs  final=%s" % (summary["total_secs"], final), flush=True)


def collect(res):
    files = []
    if res:
        for _nid, o in (res.get("outputs") or {}).items():
            for im in o.get("images", []):
                p = os.path.join(OUT, im.get("subfolder", ""), im["filename"])
                if os.path.exists(p):
                    files.append(p)
    return files


# ---------------------------------------------------------------- 阶段 3：脸部重绘
def face_stage(img_path, ref_names, g0):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFilter
    from insightface.app import FaceAnalysis
    app = FaceAnalysis(name="buffalo_l", root="/tmp/diag/fa", providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))

    def faces_of(path):
        im = Image.open(path).convert("RGB")
        arr = np.asarray(im)[:, :, ::-1].copy()
        out = []
        for f in app.get(arr):
            x1, y1, x2, y2 = [int(v) for v in f.bbox]
            out.append({"box": (x1, y1, x2, y2), "e": f.normed_embedding})
        return im, out

    refs_e = {}
    for rn in ref_names:
        p = os.path.join(INP, rn or "")
        if os.path.exists(p):
            _im, fs = faces_of(p)
            if fs:
                refs_e[rn] = fs[0]["e"]

    base_im, fl = faces_of(img_path)
    fl = sorted(fl, key=lambda f: -((f["box"][2] - f["box"][0]) * (f["box"][3] - f["box"][1])))[:3]
    t0 = time.time()
    out_im = base_im.copy()
    made = []
    for i, f in enumerate(fl):
        x1, y1, x2, y2 = f["box"]
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        s = max(x2 - x1, y2 - y1) * 1.3
        bx1, by1 = int(max(0, cx - s / 2)), int(max(0, cy - s / 2))
        bx2, by2 = int(min(base_im.width, cx + s / 2)), int(min(base_im.height, cy + s / 2))
        crop = base_im.crop((bx1, by1, bx2, by2)).resize((640, 640), Image.LANCZOS)
        cp = os.path.join(INP, "wv4_face%d.png" % i)
        crop.save(cp)
        # 该脸最像哪张定妆照
        best, bs = None, -2
        for rn, e in refs_e.items():
            v = f["e"]
            if v is None:
                continue
            c = float(np.dot(v, e))
            if c > bs:
                best, bs = rn, c
        # 该角色的定妆照作 image2
        g = json.loads(json.dumps(g0))
        g["wv4_crop"] = {"class_type": "LoadImage", "inputs": {"image": "wv4_face%d.png" % i}}
        g["wv4_crop_latent"] = {"class_type": "VAEEncode",
                                "inputs": {"pixels": ["wv4_crop", 0], "vae": ["3", 0]}}
        for nid, n in nodes_of(g, "TextEncodeQwenImageEditPlus"):
            n["inputs"]["image1"] = ["wv4_crop", 0]
            n["inputs"]["image2"] = ["wv4_refimg", 0]
            n["inputs"]["image3"] = None
        set_prompts(g, face_prompt(),
                    "模糊, 低质量, 变形, 扭曲, 多余五官, 文字, 水印, 双人, 多张脸, blurry, deformed, watermark, text")
        g["wv4_refimg"] = {"class_type": "LoadImage", "inputs": {"image": best}}
        for _nid, n in nodes_of(g, "KSampler"):
            n["inputs"]["latent_image"] = ["wv4_crop_latent", 0]
        set_sampler(g, 20, 4.0, 0.4)
        for _nid, n in nodes_of(g, "SaveImage"):
            n["inputs"]["filename_prefix"] = "wv4_f%d" % i
        pid = submit(g)
        res, el = wait(pid)
        fs = collect(res)
        if not fs:
            print("  [S3 脸%d] 失败（无输出）" % i, flush=True)
            continue
        red = Image.open(fs[0]).convert("RGB").resize((bx2 - bx1, by2 - by1), Image.LANCZOS)
        # 羽化椭圆 mask 贴回
        mask = Image.new("L", red.size, 0)
        ImageDraw.Draw(mask).ellipse([0, 0, red.size[0], red.size[1]], fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(max(4, red.size[0] // 12)))
        out_im.paste(red, (bx1, by1), mask)
        made.append({"i": i, "face_px": int(min(x2 - x1, y2 - y1)), "ref": best, "cos": round(bs, 3),
                     "redraw": os.path.basename(fs[0]), "secs": round(el, 1)})
        print("  [S3 脸%d] %.0fs 原脸 %dpx 最像=%s cos=%.3f -> %s" %
              (i, el, int(min(x2 - x1, y2 - y1)), best, bs, os.path.basename(fs[0])), flush=True)
    fp = os.path.join(OUT, "wv4_s3_face_%d.png" % int(time.time()))
    out_im.save(fp)
    print("[S3 脸部重绘] %.0fs 共 %d 张脸 -> %s" % (time.time() - t0, len(made), fp), flush=True)
    return fp, {"stage": "S3-faces", "secs": round(time.time() - t0, 1), "faces": made, "files": [fp]}


if __name__ == "__main__":
    main()
