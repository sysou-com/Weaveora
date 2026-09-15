# -*- coding: utf-8 -*-
"""a) ComfyUI 启动参数加 `--cache-none`；c) talk 服务加"资源预检"守卫。

a. 为什么：ComfyUI 默认的 RAM 缓存会把 20–28G 权重留在**内存**里（实测 `--disable-smart-memory`
   只管显存，不管内存）。talk 的推理子进程要 ~29–34G → 两者相加超过 62G → 内核 oom_kill
   （2026-09-15 21:27 实测：talk 进程 anon-rss 29.4G 被杀，连带 weaveora-stack 整体 failed）。
   `--cache-none` 让 ComfyUI 不在 RAM 里缓存模型（每次从**页缓存/磁盘**重读，700MB/s，内核可随时回收页缓存）
   → **从根上消灭 RAM OOM**。若以后想换回速度，可改成 `--cache-ram 32`（保留 32G 空闲的软阈值）。

c. 为什么：talk 每次请求会 fork 一个子进程加载整套 EchoMimic 管线（~24G 权重 / RSS 29.4G）。
   在开工前先看"可用内存/显存够不够"，不够就返回 503 + 明确原因（让 worker 先让 ComfyUI 交还资源再重试），
   而不是硬上被内核杀掉（那样连 stack 服务一起挂）。
"""
import io
import re
import shutil
import sys
import time

TS = time.strftime('%Y%m%d-%H%M%S')


def patch(path, fn, label):
    s = io.open(path, encoding='utf-8').read()
    s2 = fn(s)
    if s2 == s:
        print('SKIP %s（已是目标状态）' % label)
        return
    shutil.copy2(path, '%s.bak.%s' % (path, TS))
    io.open(path, 'w', encoding='utf-8').write(s2)
    print('OK   %s（备份 .bak.%s）' % (label, TS))


def patch_services(path):
    def fn(s):
        if '--cache-none' in s:
            return s
        # 两种排布都要覆盖：`--disable-smart-memory --reserve-vram 0.5`（同行）或分行
        if '--disable-smart-memory --reserve-vram 0.5' in s:
            return s.replace('--disable-smart-memory --reserve-vram 0.5',
                             '--disable-smart-memory \\\n    --cache-none \\\n    --reserve-vram 0.5', 1)
        if re.search(r'(?m)^\s*--reserve-vram 0\.5\s*$', s):
            return re.sub(r'(?m)^(\s*)--reserve-vram 0\.5\s*$',
                          r'\1--cache-none \\\n\1--reserve-vram 0.5', s, count=1)
        return s
    patch(path, fn, 'services_up.sh（ComfyUI --cache-none）')


def patch_talk(path):
    helper = '''

def _resources():
    """(vram_free_gb, ram_available_gb) —— 给"换能力/并发"当预算依据。

    为什么需要：talk 每次请求 fork 子进程加载整套 EchoMimic（~24G 权重 / RSS 29.4G）。
    与 ComfyUI 的内存缓存并存会超过 62G → 内核 oom_kill（还连带 weaveora-stack 整体 failed）。
    开工前先自检，不够就返回 503 让调用方先让 ComfyUI 交还资源后重试。
    """
    vram = None
    ram = None
    try:
        import torch
        f, _t = torch.cuda.mem_get_info()
        vram = round(f / 2 ** 30, 1)
    except Exception:
        pass
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    ram = round(float(line.split()[1]) / 1048576.0, 1)
                    break
    except Exception:
        pass
    return vram, ram

'''

    def fn(s):
        if 'def _resources(' in s:
            return s
        # 1) 插到 do_POST 之前（Handler 类定义前）
        m = re.search(r'(?m)^class Handler\(BaseHTTPRequestHandler\):', s)
        if not m:
            return s
        s = s[:m.start()] + helper.lstrip('\n') + '\n' + s[m.start():]
        # 2) 在 do_POST 里、拿锁之前做预检
        anchor = '        if not _LOCK.acquire(blocking=False):'
        if anchor not in s:
            return s
        guard = ('        _vram, _ram = _resources()\n'
                 '        _need_ram = float(body.get("need_ram_gb") or 34.0)\n'
                 '        _need_vram = float(body.get("need_vram_gb") or 20.0)\n'
                 '        if _ram is not None and _ram < _need_ram:\n'
                 '            return self._json(503, {"error": "内存不足：talk 需要约 %.0fG 可用，当前仅 %.1fG；'
                 '请先让 ComfyUI 交还缓存（POST /free）后重试" % (_need_ram, _ram),\n'
                 '                                "ram_available_gb": _ram, "vram_free_gb": _vram,\n'
                 '                                "need_ram_gb": _need_ram})\n'
                 '        if _vram is not None and _vram < _need_vram:\n'
                 '            return self._json(503, {"error": "显存不足：talk 需要约 %.0fG 可用，当前仅 %.1fG"'
                 ' % (_need_vram, _vram), "vram_free_gb": _vram, "ram_available_gb": _ram,\n'
                 '                                "need_vram_gb": _need_vram})\n')
        return s.replace(anchor, guard + anchor, 1)
    patch(path, fn, 'talk_server.py（资源预检 + 503）')


if __name__ == '__main__':
    root = sys.argv[1] if len(sys.argv) > 1 else '/opt/weaveora'
    patch_services(root + '/services_up.sh')
    patch_talk(root + '/talk/talk_server.py')
