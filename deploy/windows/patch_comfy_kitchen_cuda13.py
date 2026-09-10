#!/usr/bin/env python3
"""给 comfy_kitchen 的 flash-attention 探针补上「驱动能力」判定（幂等）。

背景
----
`comfy_kitchen/backends/cuda/_C.abi3.pyd` 链接的是 `cublasLt64_13`，即按 **CUDA 13**
构建；而本机 NVIDIA 驱动 566.36 只支持到 CUDA 12.7。扩展能被 import（`_EXT_AVAILABLE=True`），
但第一次真正启动 kernel 就抛：

    CUDA error: CUDA driver version is insufficient for CUDA runtime version

而 `flash_attention.is_available()` 只检查了「扩展是否导入成功 + 算力 >= 8.0」，
**没有检查驱动是否跑得动**，于是它返回 True，骗过了
`comfy/text_encoders/llama.py::init_kv_cache()`：

    fixed_kv = self.fixed_kv and comfy_kitchen.flash_attention_decode_is_available(device)

后果：ACE-Step 1.5 的文本编码器（Qwen3 AR 循环，`ace15.py` 里把 `fixed_kv` 置 True）
在 `generate_audio_codes=True`（官方蓝图默认值）时必然崩掉；只有把它关成 0 才能出曲子
（有损音质）。

修复
----
让 `is_available()` 如实回答：用 `cuDriverGetVersion` 查驱动支持的 CUDA 版本，
低于扩展所需的 13.0 就返回 False。这样 `init_kv_cache()` 自动退回普通 KV 缓存
（`(key, value, 0)` 元组），完全不碰 CUDA kernel，**codes 保持开启、音质无损**。
未来升级驱动/torch 后本判定自动放行，无需回滚。

用法（ComfyUI venv 的 python）：
    python patch_comfy_kitchen_cuda13.py            # 应用（已应用则跳过）
    python patch_comfy_kitchen_cuda13.py --check     # 只看状态
    python patch_comfy_kitchen_cuda13.py --revert    # 回滚
注：`pip install -U comfy_kitchen` 会覆盖 site-packages，需重跑本脚本。
"""
import argparse
import os
import re
import sys

MARK = "_MINIMUM_DRIVER_CUDA_VERSION"

GUARD_BLOCK = '''import ctypes
import functools
import math

import torch

from .backends import cuda as _cuda_backend

if getattr(torch.version, "hip", None):
    from .backends import hip as _hip_backend
else:
    _hip_backend = None

_MINIMUM_CAPABILITY = (8, 0)

# The bundled CUDA extension links against cublasLt64_13, so it needs a CUDA 13
# capable driver at run time (cuDriverGetVersion encodes 13.0 as 13000).
# Importing the extension succeeds even on an older driver, so _EXT_AVAILABLE
# alone is not enough: the first real kernel launch then dies with
# "CUDA driver version is insufficient for CUDA runtime version".
_MINIMUM_DRIVER_CUDA_VERSION = 13000


def _driver_cuda_version():
    """Return the CUDA version the installed driver supports, or None."""
    for name in ("nvcuda.dll", "libcuda.so.1", "libcuda.so"):
        try:
            lib = ctypes.CDLL(name)
        except OSError:
            continue
        try:
            ver = ctypes.c_int(0)
            if lib.cuDriverGetVersion(ctypes.byref(ver)) == 0 and ver.value:
                return ver.value
        except Exception:
            pass
        return None
    return None


@functools.lru_cache(maxsize=1)
def _driver_supports_kernel() -> bool:
    """False when the driver is too old to launch the CUDA kernels.

    Fails open (True) when the version cannot be determined, so behaviour on
    platforms without CUDA or without nvcuda is unchanged.
    """
    version = _driver_cuda_version()
    return True if version is None else version >= _MINIMUM_DRIVER_CUDA_VERSION

'''

ORIGINAL_HEAD = '''import math

import torch

from .backends import cuda as _cuda_backend

if getattr(torch.version, "hip", None):
    from .backends import hip as _hip_backend
else:
    _hip_backend = None

_MINIMUM_CAPABILITY = (8, 0)
'''

AVAIL_ANCHOR = '''        return False
    return torch.cuda.get_device_capability(device) >= _MINIMUM_CAPABILITY'''

AVAIL_PATCHED = '''        return False
    if not _driver_supports_kernel():
        return False
    return torch.cuda.get_device_capability(device) >= _MINIMUM_CAPABILITY'''


def find_target():
    try:
        import comfy_kitchen  # noqa: F401
    except ImportError:
        return None
    base = os.path.dirname(os.path.abspath(comfy_kitchen.__file__))
    p = os.path.join(base, "flash_attention.py")
    return p if os.path.exists(p) else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--revert", action="store_true")
    args = ap.parse_args()

    path = find_target()
    if not path:
        print("[patch] 找不到 comfy_kitchen/flash_attention.py（请用 ComfyUI venv 的 python 运行）")
        return 2
    src = open(path, encoding="utf-8").read()
    applied = MARK in src
    print("[patch] target=%s" % path)
    print("[patch] applied=%s" % applied)

    if args.check:
        try:
            import comfy_kitchen.flash_attention as fa
            print("[patch] driver_cuda_version=%s" % fa._driver_cuda_version())
            print("[patch] is_available=%s" % fa.is_available())
        except Exception as e:
            print("[patch] probe failed: %s" % e)
        return 0

    if args.revert:
        if not applied:
            print("[patch] 未应用，无需回滚")
            return 0
        out = src.replace(GUARD_BLOCK, ORIGINAL_HEAD).replace(AVAIL_PATCHED, AVAIL_ANCHOR)
        open(path, "w", encoding="utf-8", newline="\n").write(out)
        print("[patch] 已回滚")
        return 0

    if applied:
        print("[patch] 已应用，跳过（幂等）")
        return 0

    if ORIGINAL_HEAD not in src or AVAIL_ANCHOR not in src:
        print("[patch] 源码形态不匹配（comfy_kitchen 版本变了？），请人工处理")
        return 3
    out = src.replace(ORIGINAL_HEAD, GUARD_BLOCK, 1).replace(AVAIL_ANCHOR, AVAIL_PATCHED, 1)
    if out == src or MARK not in out:
        print("[patch] 替换未生效")
        return 3
    open(path, "w", encoding="utf-8", newline="\n").write(out)
    print("[patch] 已应用。重启 ComfyUI 生效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
