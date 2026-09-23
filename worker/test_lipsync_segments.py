#!/usr/bin/env python3
"""回归（P8）：对口型「按段驱动」的帧窗口必须**首尾相接、不重叠**，同时保留推理余量。

背景（用户 2026-09-23 确认「收掉段间 4 帧重叠」）：
  旧写法 `a = round(t_cum*fps)` / `b = a + round(dur*fps) + 4` 让上一段的尾巴（含 4 帧余量）
  与下一段的头**重叠**。线上实测日志（第1镜，fps=32）：
      `宝玉 0-50帧(1.44s)` / `可卿 46-108帧(1.80s)`  ⇒ 46–50 共 4 帧被两段各推理一次，
  写回时（`_splice`）后一段再淡入盖在前一段上 —— 接缝处二次贴回。
  新写法把「**归属窗口**（own_b，不重叠）」与「**推理窗口**（b，多切 4 帧喂 LatentSync）」
  分开：4 帧余量只保证「视频帧 ≥ 音频需求」（否则节点 loop_video 正放+倒放凑帧），
  **不再写回**（`_splice` 只写 [a, own_b)）。

本测试锁三件事：
  1) 归属窗口首尾相接且不重叠（own_b[i] == a[i+1]）；
  2) 每段推理窗口 ≥ 该段音频需求帧数（余量在，别为了"好看"把余量删掉）；
  3) 用线上真实数字复现「旧公式确实会重叠」，防止有人改回旧写法。

用法：`python worker/test_lipsync_segments.py`（纯本地，不需要 ComfyUI / 网络）
"""
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import comfy_client as c  # noqa: E402

FAIL = []
OK = [0]


def check(cond, msg):
    if cond:
        OK[0] += 1
        print("  ok  %s" % msg)
    else:
        FAIL.append(msg)
        print("  FAIL %s" % msg)


def old_windows(durs, fps, total):
    """旧公式（仅用于复现「会重叠」这件事，不要在生产里用）。"""
    out, t = [], 0.0
    for d in durs:
        a = max(0, int(round(t * fps)))
        b = min(a + max(1, int(round(d * fps)) + 4), total)
        out.append((a, b))
        t += d
    return out


def main():
    fps, total = 32, 160
    durs = [1.44, 1.80]                      # 线上第1镜：宝玉 1.44s / 可卿 1.80s
    print("[1] 线上第1镜复现（fps=%d total=%d）" % (fps, total))
    old = old_windows(durs, fps, total)
    print("   旧公式: %s" % old)
    check(old[1][0] < old[0][1], "旧公式确实重叠（%d < %d）—— 这就是本次要收掉的东西"
          % (old[1][0], old[0][1]))

    w = c._lipsync_seg_windows(durs, fps, total)
    print("   新公式: %s" % w)
    check(len(w) == len(durs), "段数一致")
    for i in range(len(w) - 1):
        check(w[i][2] == w[i + 1][0], "第%d/%d段归属窗口相接：own_b=%s == 下一段a=%s"
              % (i + 1, i + 2, w[i][2], w[i + 1][0]))
    for i, (a, b, own_b) in enumerate(w):
        need = max(1, int(round(durs[i] * fps)))
        check(a < own_b <= b <= total, "第%d段 0<a<own_b<=b<=total（a=%d own_b=%d b=%d total=%d）"
              % (i + 1, a, own_b, b, total))
        check(b - a >= need, "第%d段推理窗口 %d 帧 ≥ 音频需求 %d 帧（余量 %d）"
              % (i + 1, b - a, need, (b - a) - need))
    check(sum(own_b - a for a, b, own_b in w) == w[-1][2], "归属窗口累计 = 末段终点 %d" % w[-1][2])

    print("[2] 边界情形")
    w2 = c._lipsync_seg_windows([0.0, 0.05, 1.0], 32, 40)
    print("   %s" % w2)
    check(all(a < own_b <= b <= 40 for a, b, own_b in w2), "零时长/极短段也满足 0<a<own_b<=b<=total")
    check(all(w2[i][2] == w2[i + 1][0] for i in range(len(w2) - 1)), "零时长段不破坏相接性")
    w3 = c._lipsync_seg_windows([9.0], 24, 121)
    print("   长段（9s@24fps, total=121）: %s" % w3)
    check(w3[0][1] == 121 and w3[0][2] == 121, "超长段被 total 截断（不越界）")
    w4 = c._lipsync_seg_windows([1.0, 1.0], 32, 0)
    check(w4 == [(0, 0, 0), (0, 0, 0)], "total=0 时退化为空窗口（不抛异常）")

    print("\n结果：%d 项通过，%d 项失败" % (OK[0], len(FAIL)))
    for m in FAIL:
        print("  - %s" % m)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
