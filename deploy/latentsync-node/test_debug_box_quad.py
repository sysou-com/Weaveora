#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试绿框坐标系修复的自检（不需要 GPU、不加载 diffusers/kornia）。

背景（2026-09-22 勘误）：节点 `_debug_mark()` 一直把 `affine_transform()` 返回的
`box = [0, 0, 裁剪宽, 裁剪高]` 当**原帧坐标**画框 ⇒ 绿框恒贴在画面左上角，
「框住谁 / 框在不在嘴上」的目视判断全部无效（上段「贴回半幅 ⇒ 嘴部乱码」结论作废）。
修法：用 `affine_matrix`（原帧→裁剪）的**逆矩阵**把裁剪矩形映射回原帧再画。

为什么单测可以这么写：新函数 `_crop_box_to_frame_quad()` 只依赖 numpy（torch 分支可选），
所以用 ast 从 `files/latentsync/pipelines/lipsync_pipeline.py` 里把它的源码抠出来 exec，
不必 import 整个 pipeline（那需要 diffusers/torch/kornia 一整套）。

用法（任何有 numpy 的机器；GPU 盒上推荐用节点自己的 venv）：
    /opt/weaveora/ComfyUI/venv/bin/python deploy/latentsync-node/test_debug_box_quad.py
"""

import ast
import io
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "files", "latentsync", "pipelines", "lipsync_pipeline.py")
FUNC = "_crop_box_to_frame_quad"

FAILURES = []


def load_func():
    """从 pipeline 源码里抠出 _crop_box_to_frame_quad 并 exec（保持与线上完全同源）。"""
    text = io.open(SRC, "r", encoding="utf-8").read()
    tree = ast.parse(text)
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == FUNC:
            mod = ast.Module(body=[node], type_ignores=[])
            g = {"np": np, "print": print}
            try:
                import torch  # noqa: F401  （torch 分支用得到；没有也能跑）
                g["torch"] = torch
            except Exception:
                pass
            exec(compile(mod, SRC, "exec"), g)
            return g[FUNC]
    raise AssertionError("在 %s 里找不到 %s()" % (SRC, FUNC))


def check(name, cond, detail=""):
    print("%-58s %s%s" % (name, "✅" if cond else "❌", ("  " + detail) if detail else ""))
    if not cond:
        FAILURES.append(name)


def main():
    fn = load_func()

    # 1) 纯平移：M 把原帧 (x,y) 映射到裁剪 (x-50, y-30) ⇒ 裁剪矩形 (0,0,210,280)
    #    在原帧里应是 (50,30)-(260,310)。
    quad = fn([0, 0, 210, 280], np.array([[1.0, 0.0, -50.0], [0.0, 1.0, -30.0]]))
    check("纯平移：裁剪(0,0) → 原帧(50,30)", np.allclose(quad[0], [50.0, 30.0]), str(quad[0]))
    check("纯平移：裁剪(210,280) → 原帧(260,310)", np.allclose(quad[2], [260.0, 310.0]), str(quad[2]))
    check("纯平移：仍是轴对齐矩形", np.allclose(quad.min(axis=0), [50.0, 30.0]) and
          np.allclose(quad.max(axis=0), [260.0, 310.0]))

    # 2) 缩放 + 旋转：往返一致性（裁剪四角 → 原帧 → 正向映射回裁剪）
    theta = 0.37
    M = np.array([[1.7 * np.cos(theta), -1.7 * np.sin(theta), 123.0],
                  [1.7 * np.sin(theta), 1.7 * np.cos(theta), 88.0]])
    quad = fn([0, 0, 210, 280], M)
    back = (np.hstack([quad, np.ones((4, 1))]) @ M.T)[:, :2]
    expect = np.array([[0.0, 0.0], [210.0, 0.0], [210.0, 280.0], [0.0, 280.0]])
    check("缩放+旋转：逆变换往返回到裁剪四角", np.allclose(back, expect, atol=1e-9),
          "max_err=%.2e" % np.abs(back - expect).max())
    check("缩放+旋转：结果不再是左上角锚定的矩形",
          not (np.allclose(quad.min(axis=0), [0.0, 0.0])), str(np.round(quad, 1).tolist()))

    # 3) torch 张量（CUDA/half、(1,2,3) 形状）——线上传进来的就是这种
    try:
        import torch
        t = torch.tensor([[1.0, 0.0, -50.0], [0.0, 1.0, -30.0]]).half().unsqueeze(0)
        quad_t = fn([0, 0, 210, 280], t)
        expect4 = np.array([[50, 30], [260, 30], [260, 310], [50, 310]], dtype=np.float64)
        check("torch (1,2,3) half 张量：与 numpy 结果一致",
              np.allclose(quad_t, expect4, atol=1.0), str(np.round(quad_t, 1).tolist()))
    except Exception as e:
        print("%-58s ⏭  跳过（%s）" % ("torch 张量分支", e))

    # 4) 异常输入不许炸业务：失败返回 None
    check("box 长度非法 → 返回 None（不抛）",
          fn([0, 0, 210], np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]])) is None)
    check("奇异矩阵 → 返回 None（不抛）", fn([0, 0, 210, 280], np.zeros((2, 3))) is None)

    print()
    if FAILURES:
        print("❌ 失败 %d 项：%s" % (len(FAILURES), "、".join(FAILURES)))
        return 1
    print("✅ 全部通过 —— 调试绿框已画在「原帧坐标系」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
