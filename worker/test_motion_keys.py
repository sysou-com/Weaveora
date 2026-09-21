#!/usr/bin/env python3
"""回归：引擎配置下发的 motion 键必须**真的到达** worker（白名单漏键 = 静默失效）。

背景（2026-09-22 用户报「视频分辨率 720p 不可用」）：
  API 侧把「GPU 服务器最大支持分辨率」写进 `services.motion.resolution`，但 worker 的
  `MOTION_SERVICE_KEYS` 名单里**没有 `resolution`** → `apply_services()` 里被 `continue` 掉；
  而 `_motion_resolution()` 只读得到 `params.resolution`（clip 的 `payload.params` 里没有这个键）
  ⇒ **永远回落 480p 桶（长边 832）**，且不报错、日志无异常 —— 用户侧表现就是"改成 720p 没反应"。

本测试锁三件事：
  1) `services.motion.resolution` 能过白名单 → 进 `MOTION_OVERRIDES` → 被 `_motion_params()` 读到；
  2) `resolution=720p` ⇒ 长边上限 1280（1280×704 原样保留）；未设 ⇒ 压到 480p 桶（长边 ≤832）；
  3) 白名单之外的键仍然被丢弃（防止"顺手什么都收"导致参数错配）。

用法：`python worker/test_motion_keys.py`（纯本地，不需要 ComfyUI / 网络）
"""
import sys
import os

# Windows 控制台默认 GBK：中文/符号直接 print 会 UnicodeEncodeError（本测试踩过）
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import comfy_client as c  # noqa: E402


def check(cond, msg):
    print(("  [OK]   " if cond else "  [FAIL] ") + msg)
    if not cond:
        raise SystemExit(1)


def main():
    print("[1] 引擎配置下发 resolution=720p（+ 混入一个白名单外的键）")
    c.apply_services({"motion": {"resolution": "720p", "steps": 20, "bogus_key": 1}})
    mp = c._motion_params({})
    check(mp.get("resolution") == "720p", "resolution 进入 MOTION_OVERRIDES 并被 _motion_params 读到")
    check(mp.get("steps") == 20, "steps 仍然正常传入")
    check("bogus_key" not in mp, "白名单之外的键仍被丢弃")
    check(c._motion_resolution(mp, 1280, 704) == (1280, 704), "720p ⇒ 1280×704 原样保留（不再压到 832）")

    print("[2] 未配置 resolution ⇒ 仍按 480p 桶收敛（默认行为不变）")
    c.MOTION_OVERRIDES = {}          # 直接清全局：apply_services({}) 是 no-op，不会重置
    mp2 = c._motion_params({})
    check("resolution" not in mp2, "无配置时不带 resolution")
    w, h = c._motion_resolution(mp2, 1280, 704)
    check(max(w, h) <= 832, "默认压到 480p 桶（长边 ≤832），实际得到 %dx%d" % (w, h))

    print("[3] payload.params 的显式值优先于引擎配置（每镜可覆盖）")
    c.apply_services({"motion": {"resolution": "720p"}})
    mp3 = c._motion_params({"params": {"resolution": "480p"}})
    check(mp3.get("resolution") == "480p", "payload.params 覆盖 services.motion")
    check(c._motion_resolution(mp3, 1280, 704)[0] <= 832, "覆盖后按 480p 收敛")

    print("\nALL PASS")


if __name__ == "__main__":
    main()
