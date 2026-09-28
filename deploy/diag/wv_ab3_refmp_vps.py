#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_ab3_vps.py —— 第 4 镜「脸型错乱」身份绑定实验（2026-09-25，在 VPS 上后台跑）

来历：09-24 那一轮 ③（1MP vs 1.545MP）**没有留下有效数据**——
  A 臂虽说 status=success 482.8s，但盒上 `weaveora_flux2_edit_00004_.png` 是 **0 字节**
  （19:20 落盘那一刻盒子掉线）⇒ A 臂图不存在；B 臂 17s 就 Connection refused。
本脚本把三臂重跑一遍，**同 seed / 同 3 张定妆照 / 同正词 / 同负词 / 同 1664x928 / 同 20 步 guidance 4**，
只差参考图的缩放方式：

  A_1mp       生产件 flux2_dev_edit_api.json          → ImageScaleToTotalPixels(1.0)   保长宽比，4096 token/张
  B_1p545mp   flux2_dev_edit_api_1p5mp.json           → ImageScaleToTotalPixels(1.545) 保长宽比，6032 token/张
  C_imgscale  flux2_dev_edit_api_imgscale_diag.json   → ImageScale(1664x928, crop=disabled)  **盒上那份过期副本**
                                                        1:1 定妆照会被横向拉 1.78x（腿脚：脸型变形）

用法：nohup python3 wv_ab3_vps.py > /opt/weaveora/diag_out/ab_refmp2/run.log 2>&1 &
"""
import os
import subprocess
import sys
import time

PY = "/usr/bin/python3"
SMOKE = "/opt/weaveora/diag/diag_flux2_smoke.py"
POSF = "/tmp/shot4_pos.txt"
NEGF = "/tmp/shot4_neg_db.txt"
BOX = os.environ.get("WV_BOX", "http://180.127.11.167:21270")
BASE = "/opt/weaveora/data/storage/0a000003-a070-182f-81a0-70e047de000f/0a000003-a095-1b93-81a0-95b699dd0000/"
REFS = [BASE + "0a000003-a0d0-1c25-81a0-d10a35c2000a/1c66d349-0665-449d-a6c1-ccb2da0f1e5e.png",   # 宝玉 slot1
        BASE + "0a000003-a0d0-1c25-81a0-d10ff7ac000c/2799c6de-8f69-40bb-b490-b714241fba50.png",   # 可卿 slot2
        BASE + "0a000003-a0d0-1c25-81a0-d11f756a000e/202989bd-4155-4faf-a91b-29c472bba2d1.png"]   # 警幻 slot3
SEED = 20260925           # 与 09-24 那次 ③ 的 20260924 不同：那次的 A 臂图丢了，重开一组
SIZE = "1664x928"
OUT = "/opt/weaveora/diag_out/ab_refmp2"
ARMS = [("A_1mp", "/opt/weaveora/workflows/flux2_dev_edit_api.json"),
        ("B_1p545mp", "/opt/weaveora/workflows/flux2_dev_edit_api_1p5mp.json"),
        ("C_imgscale_stale", "/opt/weaveora/workflows/flux2_dev_edit_api_imgscale_diag.json")]


def run(tag, wf, prompt, neg):
    if not os.path.isfile(wf):
        print("=== 跳过 %s：缺工作流 %s" % (tag, wf), flush=True)
        return
    print("=== 开跑 %s（wf=%s, 正词 %d 字, 负词 %d 字）seed=%d size=%s %s"
          % (tag, os.path.basename(wf), len(prompt), len(neg), SEED, SIZE, time.strftime("%T")), flush=True)
    cmd = [PY, SMOKE, "--mode", "edit", "--tag", tag, "--wf", wf, "--size", SIZE, "--seed", str(SEED),
           "--steps", "20", "--guidance", "4.0", "--prompt", prompt, "--negative", neg, "--url", BOX,
           "--json-out", os.path.join(OUT, tag + ".json")]
    for r in REFS:
        cmd += ["--ref", r]
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    sys.stdout.write(p.stdout.decode("utf-8", "ignore")[-2500:])
    print("\n=== %s 结束 rc=%d 用时 %.0fs %s" % (tag, p.returncode, time.time() - t0, time.strftime("%T")), flush=True)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    prompt = open(POSF, encoding="utf-8").read().strip()
    neg = open(NEGF, encoding="utf-8").read().strip() if os.path.isfile(NEGF) else ""
    print("正词 %d 字 / 负词 %d 字 / BOX=%s / 三臂 seed=%d" % (len(prompt), len(neg), BOX, SEED), flush=True)
    for tag, wf in ARMS:
        run(tag, wf, prompt, neg)
    print("AB_DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
