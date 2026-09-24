#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 4 镜「身份绑定」A/B（2026-09-24）：现状（全员『位置：未指定』） vs 对称分带（左/中/右）。

为什么做：同一批输入（同 3 张定妆照、同正词结构）两次跑出 0.478/0.404/0.361 与 0.34/0.204/0.199 ——
FLUX.2 edit 在「三人同框」下绑定既弱又不稳；而「有框的宝玉」两次都是最高分 ⇒ 猜『缺位置槽』是主因。
本脚本只动**一个变量**（是否给三人各一个横向带），同 seed / 同参考图 / 同尺寸 / 同 20 步 guidance 4。

产出：/opt/weaveora/diag_out/ab_bands/{A_asis,B_bands}[_00001_].png（文件落在 ComfyUI/output/）
      + 各自的 json 结果；跑完用 wv_faceid.py 对同一批参考图出 cos。
"""
import json
import os
import subprocess
import sys
import time

PY = "/opt/weaveora/ComfyUI/venv/bin/python"
SMOKE = "/opt/weaveora/diag/diag_flux2_smoke.py"
POS = "/tmp/wv_shot4_pos.txt"
REFS = ["/opt/weaveora/ComfyUI/input/1c66d349-0665-449d-a6c1-ccb2da0f1e5e.png",   # 宝玉
        "/opt/weaveora/ComfyUI/input/2799c6de-8f69-40bb-b490-b714241fba50.png",   # 可卿
        "/opt/weaveora/ComfyUI/input/202989bd-4155-4faf-a91b-29c472bba2d1.png"]   # 警幻
SEED = 20260924
SIZE = "1664x928"
OUT = "/opt/weaveora/diag_out/ab_bands"
UNSPEC = "（位置：未指定，按剧情与参考图自然安排，**但必须出现在画面中**）"
BANDS = ["（位置：x 0.02–0.32 的左带，纵向 y 0.18–0.63）",
         "（位置：x 0.35–0.65 的中间带，纵向 y 0.18–0.63）",
         "（位置：x 0.68–0.98 的右带，纵向 y 0.18–0.63）"]


def run(tag, prompt):
    print("=== 开跑 %s（prompt %d 字）seed=%d size=%s" % (tag, len(prompt), SEED, SIZE), flush=True)
    cmd = [PY, SMOKE, "--mode", "edit", "--tag", tag, "--size", SIZE, "--seed", str(SEED),
           "--steps", "20", "--guidance", "4.0", "--prompt", prompt,
           "--json-out", os.path.join(OUT, tag + ".json"), "--url", "http://127.0.0.1:8001"]
    for r in REFS:
        cmd += ["--ref", r]
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "ignore")
    sys.stdout.write(out[-1200:])
    print("\n=== %s 结束 rc=%d 用时 %.0fs" % (tag, p.returncode, time.time() - t0), flush=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    pos = open(POS, encoding="utf-8").read().strip()
    n = pos.count(UNSPEC)
    if n != 3:
        print("!! 正词里『未指定』子句有 %d 处（期望 3）—— 先核对 /tmp/wv_shot4_pos.txt" % n, flush=True)
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
