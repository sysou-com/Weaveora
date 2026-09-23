#!/usr/bin/env python3
"""FLUX.2 通路「试一枪」：用**生产注入器**构造 prompt 并真 POST `/prompt`，验证节点类型/接线/字段。

为什么必须单独做这一步（2026-09-23 二段坑 1 的教训）：
  官方模板摊平出来的工作流可能有**节点类型或接线**问题，而 `/object_info` **看不出来** ——
  只有真正 POST `/prompt` 才会返回 `HTTP 400 prompt_outputs_failed_validation`。
  （当时 LTX 48fps 就是 `PrimitiveInt` 喂给了收 FLOAT 的入参，白跑一整轮才发现。）

本脚本的特点：**不复制粘贴注入逻辑**，直接 import 盒上的 `comfy_client`，
用与生产完全相同的那套函数（`_wf_inject_size/_text/_sampler/_set_image/_prune_*`）构造 graph ⇒
"试枪通过"就等价于"生产注入后的图能跑"。

用法（在 GPU 盒上，纯本地、不经过 VPS worker）：
  python3 /opt/weaveora/diag_flux2_smoke.py --mode txt2img
  python3 /opt/weaveora/diag_flux2_smoke.py --mode edit --ref /opt/weaveora/ComfyUI/input/xxx.png
  python3 /opt/weaveora/diag_flux2_smoke.py --mode txt2img --steps 8 --lora Flux_2-Turbo-LoRA_comfyui.safetensors
  python3 /opt/weaveora/diag_flux2_smoke.py --mode all --size 1024x1024      # 三个档依次试
退出码：0 = 全部通过；非 0 = 有失败（详细报错打在 stdout）
"""
import argparse
import glob
import json
import os
import sys
import time

sys.path.insert(0, "/opt/weaveora")
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import comfy_client as c  # noqa: E402

WF = {
    "txt2img": "/opt/weaveora/workflows/flux2_dev_txt2img_api.json",
    "edit": "/opt/weaveora/workflows/flux2_dev_edit_api.json",
    "img2img": "/opt/weaveora/workflows/flux2_dev_img2img_api.json",
}
PROBE = ("A young woman in a dark red silk hanfu stands in a candle-lit classical Chinese hall, "
         "three-quarter view, cinematic composition")


def parse_size(s):
    w, _, h = s.lower().partition("x")
    return int(w), int(h)


def build(mode, wf_path, width, height, steps, guidance, seed, lora, ref, denoise):
    graph = c._wf_load(wf_path)
    if not c._wf_is_flux2(graph):
        raise SystemExit("!! %s 不是 FLUX.2 结构（_wf_is_flux2=False）—— 先确认工作流对不对" % wf_path)
    ref_names = []
    if mode in ("edit", "img2img"):
        if not ref:
            cands = sorted(glob.glob("/opt/weaveora/ComfyUI/input/*.png")
                           + glob.glob("/opt/weaveora/ComfyUI/input/*.jpg"))
            if not cands:
                raise SystemExit("!! 没给 --ref，且 ComfyUI/input 下找不到可用图片")
            ref = cands[0]
        with open(ref, "rb") as fh:
            data = fh.read()
        nm = c._upload_image(data, "flux2_smoke_" + os.path.basename(ref))
        if not nm:
            raise SystemExit("!! 参考图上传失败：%s" % ref)
        ref_names = [nm, nm] if mode == "edit" else [nm]   # edit 用两张（顺便验"摘空槽 + 双槽"）
        print("   参考图：%s -> %s" % (ref, nm))
    c._wf_inject_size(graph, width, height)
    c._wf_inject_text(graph, PROBE, "white background, 3d render")
    if lora:
        c._wf_inject_lora(graph, lora, 1.0)
    is_i2i = c._wf_latent_is_img2img(graph)
    use_denoise = denoise if (is_i2i and mode != "edit") else 1.0
    c._wf_inject_sampler(graph, seed, steps, guidance, use_denoise)
    if ref_names:
        c._wf_set_image(graph, ref_names)
        slots = len(c._wf_of_class(graph, "LoadImage"))
        if c._wf_is_flux2(graph):
            pruned = c._wf_prune_flux2_refs(graph, len(ref_names))
            left = [nid for nid, _n in c._wf_of_class(graph, "ReferenceLatent")]
            print("   槽位 %d / 参考图 %d → 摘空槽 %d 个；ReferenceLatent 剩 %d（链不能断）"
                  % (slots, len(ref_names), pruned, len(left)))
    else:
        pruned = 0
    return graph, is_i2i, use_denoise


def run_one(mode, args, width, height):
    wf = args.wf or WF[mode]
    print("\n=== [%s] %s ===" % (mode, wf))
    graph, is_i2i, use_denoise = build(mode, wf, width, height, args.steps, args.guidance,
                                       args.seed, args.lora, args.ref, args.denoise)
    print("   注入后：size=%dx%d steps=%s guidance=%s seed=%s denoise=%s img2img=%s"
          % (width, height, args.steps, args.guidance, args.seed, use_denoise, is_i2i))
    c.COMFY = args.url.rstrip("/")
    c._ORPHAN_CLEAR_DONE["v"] = True      # ★ 试枪绝不去"清理"队列里的 prompt（万一是别人的任务）
    t0 = time.time()
    try:
        pid = c._post_prompt({"prompt": graph, "client_id": args.client_id}, args.client_id)
    except Exception as e:
        print("   ✗ POST /prompt 失败（这就是“只有真 POST 才暴露”的那类问题）：")
        print("   %s" % str(e)[:3000])
        return False
    print("   prompt_id=%s，等出图…" % pid)
    rec = c._poll_history(args.client_id, pid, poll=3.0, timeout=args.timeout)
    dt = time.time() - t0
    if not rec:
        print("   ✗ 超时未出结果（%.0fs）" % dt)
        return False
    st = ((rec.get("status") or {}).get("status_str")) or "?"
    outs = []
    for _nid, out in (rec.get("outputs") or {}).items():
        for im in (out.get("images") or []):
            outs.append(im.get("filename"))
    if not outs:
        print("   ✗ 出图节点无输出（status=%s）—— 看 ComfyUI 日志的 traceback" % st)
        print("   %s" % json.dumps(rec.get("status"), ensure_ascii=False)[:1500])
        return False
    print("   ✓ 出图成功：status=%s  耗时=%.1fs  输出=%s" % (st, dt, ", ".join(outs)))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="txt2img", choices=["txt2img", "edit", "img2img", "all"])
    ap.add_argument("--wf", default=None, help="自定义工作流路径（默认按 mode 取）")
    ap.add_argument("--url", default=os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8001"))
    ap.add_argument("--size", default="1024x1024")
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--guidance", type=float, default=4.0)
    ap.add_argument("--denoise", type=float, default=0.65)
    ap.add_argument("--seed", type=int, default=20260923)
    ap.add_argument("--lora", default="")
    ap.add_argument("--ref", default="")
    ap.add_argument("--timeout", type=float, default=1800)
    args = ap.parse_args()
    args.client_id = "flux2-smoke-%d" % int(time.time())
    width, height = parse_size(args.size)

    modes = ["txt2img", "edit", "img2img"] if args.mode == "all" else [args.mode]
    ok = True
    for m in modes:
        ok = run_one(m, args, width, height) and ok
    print("\n结果：%s" % ("全部通过 ✅" if ok else "存在失败 ❌"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
