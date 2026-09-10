#!/usr/bin/env python3
"""ACE-Step 1.5 配乐直调冒烟测试（不经过 API/DB，只验 ComfyUI 图谱）。"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import comfy_client as c  # noqa: E402

DUR = float(os.environ.get("TEST_DUR", "30"))
payload = {
    "kind": "bgm",
    "prompt": os.environ.get("TEST_PROMPT",
                             "cinematic instrumental score, mood: warm healing, no vocals"),
    "duration_sec": DUR,
    "seed": int(os.environ.get("TEST_SEED", "42")),
}
print("[test] ckpt=%s save_node=%s steps=%d cfg=%s shift=%s dur=%ss"
      % (c.MUSIC_CKPT, c.MUSIC_SAVE_NODE, c.MUSIC_STEPS, c.MUSIC_CFG, c.MUSIC_SHIFT, DUR), flush=True)
t0 = time.time()
try:
    outs = c.generate_music("weaveora-music-test", payload,
                            progress_fn=lambda p, s: print("  progress %s%% %s" % (p, s), flush=True))
except Exception as e:
    print("[test] FAIL: %s" % e, flush=True)
    raise SystemExit(1)
for i, o in enumerate(outs):
    fn = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "model", "_dl",
                      "music_test_%d.bin" % i)
    fn = os.path.normpath(fn)
    with open(fn, "wb") as fh:
        fh.write(o["bytes"])
    print("[test] out#%d mime=%s bytes=%d -> %s" % (i, o["mime"], len(o["bytes"]), fn), flush=True)
print("[test] PASS elapsed=%.1fs" % (time.time() - t0), flush=True)
