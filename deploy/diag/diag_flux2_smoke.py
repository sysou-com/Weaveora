#!/usr/bin/env python3
"""出图工作流「试一枪」/ A-B 单次跑：用**生产注入器**构造 prompt 并真 POST `/prompt`。

为什么必须单独做这一步（2026-09-23 二段坑 1 的教训）：
  官方模板摊平出来的工作流可能有**节点类型或接线**问题，而 `/object_info` **看不出来** ——
  只有真正 POST `/prompt` 才会返回 `HTTP 400 prompt_outputs_failed_validation`。
  （当时 LTX 48fps 就是 `PrimitiveInt` 喂给了收 FLOAT 的入参，白跑一整轮才发现。）

本脚本的特点：**不复制粘贴注入逻辑**，直接 import 盒上的 `comfy_client`，用与生产完全相同的那套函数
（`_wf_inject_size/_text/_sampler/_set_image/_prune_*` + `_image_edit_prefix`）构造 graph ⇒
"试枪通过"就等价于"生产注入后的图能跑"。参数/尺寸/提示词前缀全走同一真源，A/B 才有效。

用法（在 GPU 盒上，纯本地、不经过 VPS worker）：
  # 只验 flux2 三档
  python3 /opt/weaveora/diag/diag_flux2_smoke.py --mode all --size 1024x1024
  # 与现役 Qwen 对照（A/B）——Qwen 工作流必须显式 --allow-nonflux2
  python3 /opt/weaveora/diag/diag_flux2_smoke.py --mode edit --wf /opt/weaveora/workflows/qwen_image_edit_api.json \
      --allow-nonflux2 --steps 40 --ref /path/baoyu.png --ref /path/keqing.png --json-out /tmp/ab_qwen_edit.json
  # 挂 Turbo LoRA 的 8 步档
  python3 /opt/weaveora/diag/diag_flux2_smoke.py --mode txt2img --steps 8 \
      --lora Flux_2-Turbo-LoRA_comfyui.safetensors
退出码：0 = 通过；非 0 = 失败（ComfyUI 原始报错打在 stdout）
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


def upload_refs(paths):
    names = []
    for p in paths:
        with open(p, "rb") as fh:
            data = fh.read()
        nm = c._upload_image(data, "flux2_probe_" + os.path.basename(p))
        if not nm:
            raise SystemExit("!! 参考图上传失败：%s" % p)
        names.append(nm)
        print("   参考图：%s -> %s" % (p, nm))
    return names


def build(args, mode, wf_path, width, height):
    graph = c._wf_load(wf_path)
    is_flux2 = c._wf_is_flux2(graph)
    if not is_flux2 and not args.allow_nonflux2:
        raise SystemExit("!! %s 不是 FLUX.2 结构（_wf_is_flux2=False）—— 确认工作流，或用 --allow-nonflux2"
                         % wf_path)
    refs = args.ref or []
    if mode in ("edit", "img2img") and not refs:
        cands = sorted(glob.glob("/opt/weaveora/ComfyUI/input/*.png")
                       + glob.glob("/opt/weaveora/ComfyUI/input/*.jpg"))
        if not cands:
            raise SystemExit("!! 没给 --ref，且 ComfyUI/input 下找不到可用图片")
        refs = [cands[0]]
    ref_names = upload_refs(refs) if refs else []

    positive, negative = args.prompt, args.negative
    if mode == "edit" and ref_names:
        # ★ 与生产同一真源（_image_edit_prefix）：Qwen 用 "Picture N"、FLUX.2 用 "Reference Image N"
        positive, negative = c._image_edit_prefix(positive, negative, ref_names, is_flux2)
    if is_flux2:
        if (negative or "").strip():
            print("   FLUX.2：负词不生效 → 折进正词（原文：%s）" % negative.strip()[:120])
        positive = c._flux2_fold_negative(positive, negative, c._looks_zh(positive))
        negative = ""

    c._wf_inject_size(graph, width, height)
    c._wf_inject_text(graph, positive, negative)
    if args.lora:
        c._wf_inject_lora(graph, args.lora, args.lora_strength)
    is_i2i = c._wf_latent_is_img2img(graph)
    use_denoise = args.denoise if (is_i2i and mode != "edit") else 1.0
    c._wf_inject_sampler(graph, args.seed, args.steps, args.guidance, use_denoise)
    if ref_names:
        c._wf_set_image(graph, ref_names)
        slots = len(c._wf_of_class(graph, "LoadImage"))
        pruned = (c._wf_prune_flux2_refs(graph, len(ref_names)) if is_flux2
                  else c._wf_prune_unused_images(graph, len(ref_names)))
        if is_flux2:
            print("   槽位 %d / 参考图 %d → 摘空槽 %d；ReferenceLatent 剩 %d"
                  % (slots, len(ref_names), pruned, len(c._wf_of_class(graph, "ReferenceLatent"))))
    return graph, is_flux2, is_i2i, use_denoise


def run_one(args, mode, width, height, summary):
    tag = args.tag or mode
    wf = args.wf or WF[mode]
    print("\n=== [%s] %s ===" % (tag, wf))
    # ★ 必须先指 COMFY 再 build：build 里要 /upload/image 上传参考图（上传走的也是模块级 COMFY）。
    #   在 VPS 上跑就得指到 GPU 网关（生产路径）；在盒上跑指 127.0.0.1:8001。
    c.COMFY = args.url.rstrip("/")
    row = {"tag": tag, "mode": mode, "workflow": os.path.basename(wf), "steps": args.steps,
           "guidance": args.guidance, "lora": args.lora or "", "size": "%dx%d" % (width, height),
           "ok": False, "seconds": None, "outputs": [], "error": ""}
    try:
        graph, is_flux2, is_i2i, use_denoise = build(args, mode, wf, width, height)
    except SystemExit as e:
        print("   ✗ %s" % e)
        row["error"] = str(e)
        summary.append(row)
        return False
    print("   注入后：size=%dx%d steps=%s guidance=%s seed=%s denoise=%s img2img=%s flux2=%s"
          % (width, height, args.steps, args.guidance, args.seed, use_denoise, is_i2i, is_flux2))
    c._ORPHAN_CLEAR_DONE["v"] = True      # ★ 试枪绝不去"清理"队列里的 prompt（万一是别人的任务）
    t0 = time.time()
    try:
        pid = c._post_prompt({"prompt": graph, "client_id": args.client_id}, args.client_id)
    except Exception as e:
        print("   ✗ POST /prompt 失败（这就是“只有真 POST 才暴露”的那类问题）：")
        print("   %s" % str(e)[:3000])
        row["error"] = str(e)[:2000]
        summary.append(row)
        return False
    print("   prompt_id=%s，等出图…" % pid)
    rec = c._poll_history(args.client_id, pid, poll=3.0, timeout=args.timeout)
    dt = time.time() - t0
    row["seconds"] = round(dt, 1)
    if not rec:
        print("   ✗ 超时未出结果（%.0fs）" % dt)
        row["error"] = "timeout"
        summary.append(row)
        return False
    st = ((rec.get("status") or {}).get("status_str")) or "?"
    for _nid, out in (rec.get("outputs") or {}).items():
        for im in (out.get("images") or []):
            row["outputs"].append(im.get("filename"))
    if not row["outputs"]:
        print("   ✗ 出图节点无输出（status=%s）" % st)
        print("   %s" % json.dumps(rec.get("status"), ensure_ascii=False)[:1500])
        row["error"] = "no_outputs status=%s" % st
        summary.append(row)
        return False
    row["ok"] = True
    summary.append(row)
    print("   ✓ 出图成功：status=%s  耗时=%.1fs  输出=%s" % (st, dt, ", ".join(row["outputs"])))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="txt2img", choices=["txt2img", "edit", "img2img", "all"])
    ap.add_argument("--tag", default="", help="结果表里的名字（A/B 用）")
    ap.add_argument("--wf", default=None, help="工作流路径（默认按 mode 取 flux2 那份）")
    ap.add_argument("--allow-nonflux2", action="store_true", help="允许非 FLUX.2 工作流（A/B 对照 Qwen 用）")
    ap.add_argument("--url", default=os.environ.get("WEAVEORA_COMFY_URL", "http://127.0.0.1:8001"))
    ap.add_argument("--size", default="1024x1024")
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--guidance", type=float, default=4.0, help="Qwen 侧是 cfg，FLUX.2 侧映射到 FluxGuidance")
    ap.add_argument("--denoise", type=float, default=0.65)
    ap.add_argument("--seed", type=int, default=20260923)
    ap.add_argument("--lora", default="")
    ap.add_argument("--lora-strength", type=float, default=1.0)
    ap.add_argument("--ref", action="append", default=[], help="参考图路径（可重复；顺序=槽位顺序）")
    ap.add_argument("--prompt", default=PROBE)
    ap.add_argument("--negative", default="")
    ap.add_argument("--json-out", default="")
    ap.add_argument("--timeout", type=float, default=1800)
    args = ap.parse_args()
    args.client_id = "flux2-probe-%d" % int(time.time())
    width, height = parse_size(args.size)

    modes = ["txt2img", "edit", "img2img"] if args.mode == "all" else [args.mode]
    summary, ok = [], True
    for m in modes:
        sub = argparse.Namespace(**vars(args))
        if args.mode == "all":
            sub.tag = m
        ok = run_one(sub, m, width, height, summary) and ok
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
        print("结果已写：%s" % args.json_out)
    print("\n结果：%s" % ("全部通过 ✅" if ok else "存在失败 ❌"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
