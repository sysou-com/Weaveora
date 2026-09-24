#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 4 镜「身份绑定」A/B（2026-09-24，**在 VPS 上跑**：本脚本 import /opt/weaveora/comfy_client.py）。

现状（三人全『位置：未指定』） vs 对称分带（左/中/右）—— 只动这一个变量：
同 seed / 同 3 张定妆照 / 同 1664x928 / 同 20 步 guidance 4 / 同 edit 工作流。
产出落在**盒**的 ComfyUI/output/（SaveImage），跑完用 wv_faceid.py 出 cos。
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
OUT = "/opt/weaveora/diag_out/ab_bands"
UNSPEC = "（位置：未指定，按剧情与参考图自然安排，**但必须出现在画面中**）"
BANDS = ["（位置：x 0.02–0.32 的左带，纵向 y 0.18–0.63）",
         "（位置：x 0.35–0.65 的中间带，纵向 y 0.18–0.63）",
         "（位置：x 0.68–0.98 的右带，纵向 y 0.18–0.63）"]


def run(tag, prompt):
    print("=== 开跑 %s（prompt %d 字）seed=%d size=%s %s" % (tag, len(prompt), SEED, SIZE, time.strftime("%T")), flush=True)
    cmd = [PY, SMOKE, "--mode", "edit", "--tag", tag, "--size", SIZE, "--seed", str(SEED),
           "--steps", "20", "--guidance", "4.0", "--prompt", prompt, "--url", BOX,
           "--json-out", os.path.join(OUT, tag + ".json")]
    for r in REFS:
        cmd += ["--ref", r]
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "ignore")
    sys.stdout.write(out[-1500:])
    print("\n=== %s 结束 rc=%d 用时 %.0fs %s" % (tag, p.returncode, time.time() - t0, time.strftime("%T")), flush=True)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    pos = open(POS, encoding="utf-8").read().strip()
    n = pos.count(UNSPEC)
    if n != 3:
        print("!! 正词里『未指定』子句 %d 处（期望 3）" % n, flush=True)
        return 2
    b = pos
    for band in BANDS:
        b = b.replace(UNSPEC, band, 1)
    run("A_asis", pos)
    run("B_bands", b)
    print("AB_DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
