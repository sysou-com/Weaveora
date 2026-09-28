#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""D 臂（槽位位置实验）用：把正词里 可卿/警幻 的槽位映射对调，
   与参考图入参顺序 [宝玉, 警幻, 可卿] 保持一致。

目的：检验「3 参考图时，**中间那个槽** 绑定最弱」这一假设。
  基线 A 臂：(宝玉, 可卿, 警幻) → 可卿在中间 → 历史 8 次里 7 次她 cos 最低。
  D 臂：     (宝玉, 警幻, 可卿) → 警幻在中间。
  预测：若位置假设成立 → 这次的最低 cos 变成警幻，而可卿升上来。
        若仍是可卿最低 → 是「可卿这份定妆照本身难绑」，与槽位无关。
"""
import io
import re
import sys

SRC = "/tmp/shot4_pos.txt"
DST = "/tmp/shot4_pos_swap.txt"

s = io.open(SRC, encoding="utf-8").read()
p2 = re.compile(r"(Picture 2 \(image2\) = )([^\[\n]+)\[([^\]]*)\]")
p3 = re.compile(r"(Picture 3 \(image3\) = )([^\[\n]+)\[([^\]]*)\]")
m2, m3 = p2.search(s), p3.search(s)
if not m2 or not m3:
    sys.exit("!! 找不到 Picture 2 / Picture 3 映射行（正词格式变了？）")
n2, a2 = m2.group(2), m2.group(3)
n3, a3 = m3.group(2), m3.group(3)
s = p2.sub(lambda m: m.group(1) + n3 + "[" + a3 + "]", s, count=1)
s = p3.sub(lambda m: m.group(1) + n2 + "[" + a2 + "]", s, count=1)
before = s
s = s.replace("Left to right: 宝玉 (image1) -> 可卿 (image2) -> 警幻 (image3)",
              "Left to right: 宝玉 (image1) -> 警幻 (image2) -> 可卿 (image3)")
io.open(DST, "w", encoding="utf-8").write(s)

print("原：Picture 2 = %s%s / Picture 3 = %s%s" % (n2, a2, n3, a3))
for ln in s.split("\n"):
    if "Picture 2 (image2)" in ln or "Picture 3 (image3)" in ln or "Left to right:" in ln:
        print("新：" + ln[:200])
print("左到右行已替换：%s" % ("是" if s != before else "**否（没找到原文，需人工核对）**"))
print("长度 %d → %d" % (len(io.open(SRC, encoding="utf-8").read()), len(s)))
