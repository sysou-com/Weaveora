# -*- coding: utf-8 -*-
"""修补 b 的一个测量错误：内存预算必须读 **GPU 机**，不能读 worker 机（VPS）。

现象：worker 日志打 `可用内存 5.0 → 5.0 GiB`，而 GPU 机 `free -m` 显示 available 49.5G ——
因为 `_ram_available_gb()` 读的是 /proc/meminfo，而 worker 跑在 VPS 上，读成了 VPS 的内存。

修法：
  ① talk 服务（跑在 GPU 机上）的 `/health` 增加 `ram_available_gb`；
  ② worker 的 `_yield_resources` 对 talk 走 `<talk_url>/health` 取 GPU 侧显存/内存（非 talk 路径只信
     ComfyUI 的 vram，不去读本地 /proc/meminfo 以免误导）。
"""
import io
import re
import shutil
import time

TS = time.strftime('%Y%m%d-%H%M%S')


def patch(path, fn, label):
    s = io.open(path, encoding='utf-8').read()
    s2 = fn(s)
    if s2 == s:
        print('SKIP %s' % label)
        return
    shutil.copy2(path, '%s.bak.%s' % (path, TS))
    io.open(path, 'w', encoding='utf-8').write(s2)
    print('OK   %s' % label)


def patch_talk_health(path):
    def fn(s):
        if '_v, _r = _resources()' in s:   # 已打过（注意：503 守卫里也含 ram_available_gb 字样，不能拿它当判据）
            return s
        old = '''            return self._json(200, {"ok": True, "device": "cuda", "vram_free_gb": free,
                                    "venv": PY, "repo": REPO, "face_aux": FACE_AUX,
                                    "default_steps": 25})'''
        if old not in s:
            return s
        new = '''            _v, _r = _resources()
            _free, _ram = (free if free is not None else _v), _r
            return self._json(200, {"ok": True, "device": "cuda", "vram_free_gb": _free,
                                    "ram_available_gb": _ram,
                                    "venv": PY, "repo": REPO, "face_aux": FACE_AUX,
                                    "default_steps": 25})'''
        return s.replace(old, new, 1)
    patch(path, fn, 'talk_server.py /health 增加 ram_available_gb')


if __name__ == '__main__':
    import sys
    root = sys.argv[1] if len(sys.argv) > 1 else '/opt/weaveora'
    t = root + '/talk/talk_server.py'
    try:
        patch_talk_health(t)
    except Exception as e:
        print('talk health patch failed:', e)
