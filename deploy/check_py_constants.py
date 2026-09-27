#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态自查：模块里**用到了但没定义**的大写常量（部署前跑，挡住了两次线上事故）。

为什么需要（2026-09-22 / 09-28 各一次，都是我）：
  `py_compile` 与「import 自检」都**查不出**「引用了未定义的模块级常量」——
  常量在函数体内被引用时，只有**那条代码路径真的跑到**才会 NameError ⇒
  表现是「部署显示 IMPORTS_OK，任务跑到一半失败」：
    · `name 'LIPSYNC_POST_UPSCALE' is not defined`（对口型任务最后一步失败）
    · `name '_COSTUME_CROP_ON' is not defined`（第4镜关键帧在参考图上传阶段失败）

判据（保守，只报高置信度的）：
  取模块顶层 `NAME = ...` 的定义集合；取函数体内**以 Load 方式引用**的
  「全大写、长度≥5、且不是已知外部符号」的名字；差集非空 = 有问题。

用法：
  python3 deploy/check_py_constants.py worker/comfy_client.py [更多文件...]
  退出码：0 = 干净；1 = 有缺失（部署脚本据此中止）
"""

import ast
import sys

# 允许的例外：内置/三方/局部惯用大写名（不在本模块定义，但引用是合法的）
ALLOW = {
    "ASCII", "BASE64", "BOM", "CB", "CPU", "CSV", "CUDA", "GB", "GPU", "HTTP", "HTTPS",
    "ID", "IMAGE", "JSON", "JPG", "MB", "MIME", "MP3", "MP4", "MASK", "NONE", "OK",
    "PNG", "QUEUE", "RAM", "RGB", "RGBA", "SQL", "URL", "UTC", "UUID", "WAV", "WEBP",
    "XML", "YUV", "ZIP", "TRUE", "FALSE", "MULTIPART", "OSERROR", "EXCEPTION",
}
MIN_LEN = 5


def defined_constants(tree):
    out = set()
    for node in tree.body:                      # 只认模块顶层赋值（函数内局部变量不算）
        targets = []
        if isinstance(node, ast.Assign):
            targets = node.targets
        elif isinstance(node, ast.AnnAssign):
            targets = [node.target]
        for t in targets:
            if isinstance(t, ast.Name):
                out.add(t.id)
            elif isinstance(t, (ast.Tuple, ast.List)):   # a, b = ...
                out.update(e.id for e in t.elts if isinstance(e, ast.Name))
    return out


def loaded_names(tree):
    """函数体/类体里以 Load 方式引用的大写名（含嵌套函数）。"""
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) or isinstance(node, ast.AsyncFunctionDef):
            for sub in ast.walk(node):
                if isinstance(sub, ast.Name) and isinstance(sub.ctx, ast.Load):
                    n = sub.id
                    if n.isupper() and len(n) >= MIN_LEN and n not in ALLOW:
                        out.add(n)
    return out


def check(path):
    src = open(path, encoding="utf-8").read()
    tree = ast.parse(src)
    missing = sorted(loaded_names(tree) - defined_constants(tree))
    if missing:
        print("MISSING=%s" % ",".join(missing))
        return 1
    print("OK（无缺失常量）")
    return 0


def main(argv):
    bad = 0
    for p in argv[1:]:
        print("-- %s" % p)
        bad |= check(p)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
