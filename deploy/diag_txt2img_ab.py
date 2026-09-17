#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""txt2img 参数 A/B（等 GPU 回来后一条命令跑完）

背景：林黛玉 22:19 那张定妆照是**零参考**→ 回退纯文生图 → 出噪声图（人脸探针命中 0）。
零参考入口已封（后端拒 + 前端禁用），但**这台机器上纯文生图从未验证过能出好图**（历史成功出图全是 edit）。
本脚本用**同种子同提示词**跑两发，比较：
  A = 引擎配置当前值    ：steps 40 / cfg 4.0（`services.image.steps/cfg`，worker 会覆盖工作流自带值）
  B = 工作流电影档默认值：steps 24 / cfg 3.5（`qwen_image_txt2img_film_api.json` 自带）
判据：人脸探针 `hits`（噪声图=0；正常图≥1）+ PNG 体积（1280x704 噪声≈1.9MB，正常≈0.7MB）。
残留风险提示：若两发都“干净”，说明参数不是主因（要另查模型/工作流）；若 A 坏 B 好 → 给 txt2img 单独一套参数。

用法（在 VPS 上，先按纪律查队列 —— 有 queued/running 就别跑）：
    python3 diag_txt2img_ab.py --prompt "标准角色设定图：林黛玉 正面半身、纯色背景…" --seed 123456
    python3 diag_txt2img_ab.py --prompt "…" --save-dir /opt/weaveora/_xfer/ab
只依赖 py3.6 标准库；不经过我们的 API（直连 ComfyUI 网关），不写业务库。
"""

import argparse
import base64
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

WORKFLOW_DEFAULT = "/opt/weaveora/workflows/qwen_image_txt2img_film_api.json"
# 工作流里各节点的注入口径（与 worker/comfy_client.py 的 _wf_* 同源）：
#   6=positive 7=negative 8=尺寸 9=sampler(seed/steps/cfg/denoise)
N_POS, N_NEG, N_LATENT, N_SAMPLER = "6", "7", "8", "9"


def post_json(url, obj, timeout=60):
    data = json.dumps(obj).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    ctx = ssl._create_unverified_context()
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode())


def get_json(url, timeout=60):
    ctx = ssl._create_unverified_context()
    with urllib.request.urlopen(url, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode())


def build(gateway, wf_path, prompt, negative, seed, width, height, steps, cfg):
    d = json.load(open(wf_path))
    d[N_POS]["inputs"]["text"] = prompt
    d[N_NEG]["inputs"]["text"] = negative
    d[N_LATENT]["inputs"]["width"] = width
    d[N_LATENT]["inputs"]["height"] = height
    s = d[N_SAMPLER]["inputs"]
    s["seed"] = seed
    s["steps"] = steps
    s["cfg"] = cfg
    s["denoise"] = 1.0
    return d


def run_one(gateway, wf_path, tag, prompt, negative, seed, width, height, steps, cfg, save_dir,
            wait_max=1800):
    graph = build(gateway, wf_path, prompt, negative, seed, width, height, steps, cfg)
    print("[%s] 提交：steps=%s cfg=%s %dx%d seed=%s" % (tag, steps, cfg, width, height, seed), flush=True)
    r = post_json(gateway.rstrip("/") + "/prompt", {"prompt": graph, "client_id": "wv-ab-%s" % tag})
    pid = r.get("prompt_id") or ""
    if not pid:
        print("[%s] ✗ 提交失败：%s" % (tag, json.dumps(r)[:200]))
        return None
    t0 = time.time()
    while time.time() - t0 < wait_max:
        time.sleep(5)
        try:
            hist = get_json(gateway.rstrip("/") + "/history/" + pid)
        except Exception:
            continue
        if pid not in hist:
            continue
        node_out = (hist[pid].get("outputs") or {}).get("11") or {}
        imgs = node_out.get("images") or []
        if not imgs:
            print("[%s] ✗ 无输出图（status=%s）" % (tag, json.dumps(hist[pid].get("status"))[:200]))
            return None
        info = imgs[0]
        q = urllib.parse.urlencode({"filename": info["filename"], "subfolder": info.get("subfolder", ""),
                                    "type": info.get("type", "output")})
        raw = urllib.request.urlopen(gateway.rstrip("/") + "/view?" + q, timeout=120).read()
        out = os.path.join(save_dir, "ab_%s_s%s_c%s.png" % (tag, steps, str(cfg).replace(".", "")))
        with open(out, "wb") as f:
            f.write(raw)
        face = {}
        try:
            face = post_json(gateway.rstrip("/") + "/face/probe",
                             {"media_b64": base64.b64encode(raw).decode(), "suffix": ".png"}, timeout=300)
        except Exception as e:
            face = {"error": str(e)[:80]}
        print("[%s] ✓ %s | %d bytes(%.2f MB) | 人脸: 命中 %s/%s 脸宽 %s 占比 %s"
              % (tag, os.path.basename(out), len(raw), len(raw) / 1048576.0,
                 face.get("hits"), face.get("total"),
                 round(face["face_px"], 1) if face.get("face_px") else "-",
                 round(face["face_ratio"], 3) if face.get("face_ratio") else "-"), flush=True)
        return {"tag": tag, "file": out, "bytes": len(raw), "face": face, "elapsed": round(time.time() - t0, 1)}
    print("[%s] ✗ 超时未出图（%ds）" % (tag, wait_max))
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://180.127.11.167:31058")
    ap.add_argument("--workflow", default=WORKFLOW_DEFAULT)
    ap.add_argument("--prompt", required=True)
    ap.add_argument("--negative", default="text, watermark, logo, subtitle, lowres, blurry, "
                                          "deformed face, extra limbs, 文字, 水印, 低分辨率")
    ap.add_argument("--seed", type=int, default=123456)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=704)
    ap.add_argument("--save-dir", default="/opt/weaveora/_xfer/ab")
    ap.add_argument("--skip-a", action="store_true", help="只跑 B（工作流默认 24/3.5）")
    ap.add_argument("--skip-b", action="store_true", help="只跑 A（引擎当前 40/4.0）")
    a = ap.parse_args()

    if not os.path.isdir(a.save_dir):
        os.makedirs(a.save_dir)
    if not os.path.isfile(a.workflow):
        print("✗ 找不到工作流：%s" % a.workflow)
        return 2
    # 前置：ComfyUI 队列必须空（有残留 prompt 会污染 A/B）
    try:
        q = get_json(a.gateway.rstrip("/") + "/queue")
        running, pending = len(q.get("queue_running") or []), len(q.get("queue_pending") or [])
        print("ComfyUI 队列：running=%d pending=%d" % (running, pending))
        if running or pending:
            print("✗ 队列非空 —— 先等它跑完再跑 A/B（否则结果不可比）")
            return 1
    except Exception as e:
        print("✗ 连不上网关 %s：%s" % (a.gateway, str(e)[:120]))
        return 1

    res = []
    if not a.skip_a:
        res.append(run_one(a.gateway, a.workflow, "A_engine", a.prompt, a.negative, a.seed,
                           a.width, a.height, 40, 4.0, a.save_dir))
    if not a.skip_b:
        res.append(run_one(a.gateway, a.workflow, "B_workflow", a.prompt, a.negative, a.seed,
                           a.width, a.height, 24, 3.5, a.save_dir))
    print("\n== 结论 ==")
    for r in res:
        if not r:
            continue
        hits = (r["face"] or {}).get("hits")
        verdict = "噪声/废图" if (hits == 0 or r["bytes"] > 1500000) else "正常"
        print("  %-11s %6.2f MB  人脸命中=%s  → %s  (%.0fs)"
              % (r["tag"], r["bytes"] / 1048576.0, hits, verdict, r["elapsed"]))
    print("图片已存 %s（可直接开链接看：https://sysou.com/weaveora/_share/… 需拷到 web/_share）" % a.save_dir)
    return 0


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  （只在需要 urlencode 时用）
    sys.exit(main())
