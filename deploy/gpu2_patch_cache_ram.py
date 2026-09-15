# -*- coding: utf-8 -*-
"""把 --cache-none 换成 --cache-ram 32。

为什么（2026-09-15 实测）：`--cache-none` 让 ComfyUI 每次出图都重新实例化模型 →
完整重载 20.4G 权重 + 在 CPU 上做 fp8→bf16 手工转换（日志 `manual cast: torch.bfloat16`）。
实测结果：**640×352、8 步这种小任务单张都要 2.5 分钟以上**（瓶颈根本不是采样步数）。
换成 `--cache-ram 32`：保留**内存**里的模型对象（不再重载/重转换），
同时保留 `--disable-smart-memory`（出完图卸显存，给 A14B 双专家腾地方）；
32 = 空闲内存低于 32G 时不再往内存塞模型 → 给 talk（需 ~29G）留余量。
"""
import io
import shutil
import time

p = '/opt/weaveora/services_up.sh'
s = io.open(p, encoding='utf-8').read()
if '--cache-ram' in s and '--cache-none' not in s:
    print('SKIP: 已是 --cache-ram')
else:
    shutil.copy2(p, p + '.bak.' + time.strftime('%Y%m%d-%H%M%S'))
    io.open(p, 'w', encoding='utf-8').write(s.replace('--cache-none', '--cache-ram 32'))
    print('OK: --cache-none -> --cache-ram 32')
