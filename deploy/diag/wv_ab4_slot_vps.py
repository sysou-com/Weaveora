#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wv_ab4_vps.py —— D 臂：**槽位位置**实验（等 A/B/C 三臂跑完后自动接力）。

假设：3 参考图时 **中间那个槽** 绑定最弱。
  基线（08:22–19:04 共 8 次生产 3 图出图）里 可卿 是 Picture 2（中间），均值最低（0.212，
  8 次里 4 次是全批最低；宝玉 0.404 / 警幻 0.279）；而她在 2 参考图的镜次（09-24 10:40 第 3 镜）
  是 Picture 2 = **链尾**，cos 0.594（达标）→ 疑似「中间槽弱」而不是「她的定妆照差」。

D 臂：同 seed/同正词结构/同 3 张定妆照/同 1664x928/同 20 步 guidance 4，
     只把 **可卿 ↔ 警幻 的槽位对调**（正词映射行同步对调，见 /tmp/shot4_pos_swap.txt）：
     slot1=宝玉 slot2=警幻 slot3=可卿
  预测：若位置假设成立 → 最低 cos 变成**警幻**；若仍是可卿/宝玉 → 假设不成立，转查定妆照。

★ 2026-09-25 实测结果（seed 20260925，与 A 臂同 seed 同正词其余部分）：**假设不成立**。
  A 臂（宝玉/可卿/警幻）：0.061 / 0.216 / 0.259 → 排序 警幻 > 可卿 > 宝玉
  D 臂（宝玉/警幻/可卿）：0.133 / 0.275 / 0.358 → 排序 警幻 > 可卿 > 宝玉（**一模一样**）
  ⇒ 强弱跟**角色/定妆照**走，不跟槽位走；「把谁放中间」不是抓手。
  可疑点转到定妆照本身的可分性：宝玉↔警幻 两张定妆照的 face cos = **0.324**（逼近 0.35「难分」线）。
"""
import os
import subprocess
import sys
import time

PY = "/usr/bin/python3"
SMOKE = "/opt/weaveora/diag/diag_flux2_smoke.py"
POSF = "/tmp/shot4_pos_swap.txt"
NEGF = "/tmp/shot4_neg_db.txt"
BOX = os.environ.get("WV_BOX", "http://180.127.11.167:21270")
BASE = "/opt/weaveora/data/storage/0a000003-a070-182f-81a0-70e047de000f/0a000003-a095-1b93-81a0-95b699dd0000/"
REFS = [BASE + "0a000003-a0d0-1c25-81a0-d10a35c2000a/1c66d349-0665-449d-a6c1-ccb2da0f1e5e.png",   # 宝玉 slot1
        BASE + "0a000003-a0d0-1c25-81a0-d11f756a000e/202989bd-4155-4faf-a91b-29c472bba2d1.png",   # 警幻 slot2  ← 对调
        BASE + "0a000003-a0d0-1c25-81a0-d10ff7ac000c/2799c6de-8f69-40bb-b490-b714241fba50.png"]   # 可卿 slot3  ← 对调
SEED = 20260925
SIZE = "1664x928"
OUT = "/opt/weaveora/diag_out/ab_refmp2"
PREV_LOG = OUT + "/run.log"
PREV_TIMEOUT = 3600


def wait_prev():
    t0 = time.time()
    while time.time() - t0 < PREV_TIMEOUT:
        try:
            log = open(PREV_LOG, encoding="utf-8", errors="replace").read()
        except OSError:
            log = ""
        if "AB_DONE" in log:
            print("[D] 前三臂已 AB_DONE（等待 %.0fs）" % (time.time() - t0), flush=True)
            return
        if subprocess.call(["pgrep", "-f", "wv_ab3_vps"]) != 0:
            print("[D] 前三臂进程已退出但没看到 AB_DONE —— 仍继续跑 D", flush=True)
            return
        time.sleep(20)
    print("[D] 等超时（%ds），仍继续跑 D" % PREV_TIMEOUT, flush=True)


def main():
    wait_prev()
    prompt = open(POSF, encoding="utf-8").read().strip()
    neg = open(NEGF, encoding="utf-8").read().strip() if os.path.isfile(NEGF) else ""
    tag = "D_slot_keqing_last"
    print("=== 开跑 %s（正词 %d 字）seed=%d size=%s %s" % (tag, len(prompt), SEED, SIZE, time.strftime("%T")), flush=True)
    cmd = [PY, SMOKE, "--mode", "edit", "--tag", tag, "--wf",
           "/opt/weaveora/workflows/flux2_dev_edit_api.json", "--size", SIZE, "--seed", str(SEED),
           "--steps", "20", "--guidance", "4.0", "--prompt", prompt, "--negative", neg, "--url", BOX,
           "--json-out", os.path.join(OUT, tag + ".json")]
    for r in REFS:
        cmd += ["--ref", r]
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    sys.stdout.write(p.stdout.decode("utf-8", "ignore")[-2500:])
    print("\n=== %s 结束 rc=%d 用时 %.0fs %s" % (tag, p.returncode, time.time() - t0, time.strftime("%T")), flush=True)
    print("AB4_DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
