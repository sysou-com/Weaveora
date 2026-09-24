#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""③ 参考图 token 预算 A/B（2026-09-24，**在 VPS 上跑**）：1MP(4096/张) vs 1.545MP(6032/张)。

只动这一个变量：同一份生产正词（/tmp/shot4_pos.txt）、同 seed、同 3 张定妆照、同 1664x928、
同 20 步 / guidance 4、同 edit 工作流结构（只差 3 个参考槽的 megapixels）。
为什么不是"缩到目标尺寸"：定妆照是 1:1，缩到 1664x928 会拉伸变形 —— 所以用**保长宽比**的
ImageScaleToTotalPixels 提高预算（1.545MP × 1:1 = 1331×1331，token 与旧"目标尺寸"口径相同）。
"""
import os
import subprocess
import sys
import time

PY = "/usr/bin/python3"
SMOKE = "/opt/weaveora/diag/diag_flux2_smoke.py"
POS = "/tmp/shot4_pos.txt"
BOX = "http://180.127.11.167:20682"
BASE = "/opt/weaveora/data/storage/0a000003-a070-182f-81a0-70e047de000f/0a000003-a095-1b93-81a0-95b699dd0000/"
REFS = [BASE + "0a000003-a0d0-1c25-81a0-d10a35c2000a/1c66d349-0665-449d-a6c1-ccb2da0f1e5e.png",   # 宝玉
        BASE + "0a000003-a0d0-1c25-81a0-d10ff7ac000c/2799c6de-8f69-40bb-b490-b714241fba50.png",   # 可卿
        BASE + "0a000003-a0d0-1c25-81a0-d11f756a000e/202989bd-4155-4faf-a91b-29c472bba2d1.png"]   # 警幻
SEED = 20260924
SIZE = "1664x928"
OUT = "/opt/weaveora/diag_out/ab_refmp"
ARMS = [("A_1mp", "/opt/weaveora/workflows/flux2_dev_edit_api.json"),
        ("B_1p545mp", "/opt/weaveora/workflows/flux2_dev_edit_api_1p5mp.json")]


def run(tag, wf, prompt):
    print("=== 开跑 %s（wf=%s, prompt %d 字）seed=%d size=%s %s"
          % (tag, os.path.basename(wf), len(prompt), SEED, SIZE, time.strftime("%T")), flush=True)
    cmd = [PY, SMOKE, "--mode", "edit", "--tag", tag, "--wf", wf, "--size", SIZE, "--seed", str(SEED),
           "--steps", "20", "--guidance", "4.0", "--prompt", prompt, "--url", BOX,
           "--json-out", os.path.join(OUT, tag + ".json")]
    for r in REFS:
        cmd += ["--ref", r]
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    sys.stdout.write(p.stdout.decode("utf-8", "ignore")[-1200:])
    print("\n=== %s 结束 rc=%d 用时 %.0fs %s" % (tag, p.returncode, time.time() - t0, time.strftime("%T")), flush=True)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    prompt = open(POS, encoding="utf-8").read().strip()
    print("正词 %d 字" % len(prompt), flush=True)
    for tag, wf in ARMS:
        run(tag, wf, prompt)
    print("AB_DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
