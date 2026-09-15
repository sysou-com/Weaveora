# -*- coding: utf-8 -*-
"""把 --disable-smart-memory 加回 ComfyUI 启动参数。

为什么撤回（2026-09-15 实测）：
  · A14B I2V 是**双专家**（高/低噪声各 13.3G）+ umt5 6.7G。开启 smart memory 时两个专家会同时驻留，
    再加注意力/解码峰值就超过 47.4G → `KSamplerAdvanced: torch.OutOfMemoryError`
    （已排除"没卸载"：/free 后 46.5G 可用、并自动降帧 80→56 帧，仍然 OOM）。
  · 之前"首图 12 分钟"的真因是**内存只有 31G + swap 换页**，不是这个 flag；现在 64G 内存已解决。
所以：保留 `--disable-smart-memory`（用完即卸，双专家串行换入换出），同 kind 连跑靠 A3 亲和 + 62G 页缓存把重载压到 ~40s。
"""
import io
import shutil
import time

p = '/opt/weaveora/services_up.sh'
s = io.open(p, encoding='utf-8').read()
if '--disable-smart-memory' in s:
    print('SKIP: 已有该 flag')
else:
    anchor = '--port 8001 \\'
    assert anchor in s, '锚点未找到: ' + anchor
    new = '--port 8001 \\\n    --disable-smart-memory \\'
    shutil.copy2(p, p + '.bak.' + time.strftime('%Y%m%d-%H%M%S'))
    io.open(p, 'w', encoding='utf-8').write(s.replace(anchor, new, 1))
    print('OK: 已加回 --disable-smart-memory')
