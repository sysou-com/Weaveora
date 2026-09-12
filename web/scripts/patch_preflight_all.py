#!/usr/bin/env python3
"""P13：把「先保存再生成」的入口统一走生成前预检 —— 一次确认后即设为基准，不再反复问。

原来是 `if (dirty) await handleSave()`：在已确认稿上保存会**另存 vN+1**，
于是又变成「未确认」→ 下一次单条生成/试听又被拦 → 用户被迫一直点确认。
改成走 ensureApprovedForGenerate(动作)：弹一次框，选「保存并确认后生成」即把新版本设为生成基准。
"""
import io
import re

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "GEN_PREFLIGHT_DONE" in s:
    print("already patched")
    raise SystemExit(0)

# 在预检函数定义后打个标记，便于二次运行幂等
s = s.replace("async function ensureApprovedForGenerate(action: string): Promise<boolean> {",
              "/* GEN_PREFLIGHT_DONE */\nasync function ensureApprovedForGenerate(action: string): Promise<boolean> {", 1)

# 逐个入口按函数名替换（保留原注释语义）
REPLACEMENTS = {
    "async function genVoiceLine(": "重新生成配音",
    "async function previewVoice(": "试听配音",
    "async function previewVoiceLine(": "试听该条配音",
    "async function previewBgm(": "试听配乐",
    "async function genPortrait(": "生成定妆照",
    "async function onAiLines(": "AI 台词",
    "async function onAiMusic(": "AI 配乐",
    "async function onCloneUsedForLine(": "音色样本当配音",
}

def patch_fn(src: str, fn_sig: str, action: str) -> str:
    i = src.find(fn_sig)
    if i < 0:
        print("  ! 找不到", fn_sig)
        return src
    j = src.find("if (dirty.value && !(await handleSave())) return", i)
    if j < 0:
        print("  ! 该函数内没有 save 行", fn_sig)
        return src
    end = j + len("if (dirty.value && !(await handleSave())) return")
    line = src[j:end]
    # 保留行尾注释
    k = src.find("\n", end)
    tail = src[end:k]
    new = "if (!(await ensureApprovedForGenerate('%s'))) return" % action
    print("  改：%s → %s%s" % (fn_sig.split('(')[0].replace('async function ', ''), new, tail))
    return src[:j] + new + tail + src[k:]

for sig, action in REPLACEMENTS.items():
    s = patch_fn(s, sig, action)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("done")
