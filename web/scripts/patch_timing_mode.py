#!/usr/bin/env python3
"""P13：新增 timing_mode（shot_fixed 默认 / audio_first）+ 校准按模式走。幂等。"""
import io

p = "web/src/utils/plan.ts"
s = io.open(p, encoding="utf-8").read()

# 1) 模式读取
if "export function timingMode" not in s:
    anchor = "/**\n * 「配音比模型单次上限长」时的处理策略 —— 直接决定**云端调用次数（= 钱）**："
    add = '''/**
 * 镜头时长的**决定权**（timing_mode）：
 *
 * - `shot_fixed`（**默认**）：镜长 = min(模型单次上限, 目标镜长)。**配音顺排、允许溢出到下一镜**。
 *   每镜只调用云端 **1 次**、画面原速完整（不用本地拉伸/补帧），最适合旁白/叙事类。
 * - `audio_first`：镜长跟着配音走（配音多长，镜头就多长）；超过模型上限时按 oversize_policy
 *   处理（本地拉伸 / 配音溢出 / 切段）。适合对话、口型、以及"画面必须配合台词"的镜头。
 */
export type TimingMode = 'shot_fixed' | 'audio_first'

export function timingMode(plan: { edit_plan?: Record<string, unknown> }): TimingMode {
  const v = plan?.edit_plan?.timing_mode as string | undefined
  return v === 'audio_first' ? 'audio_first' : 'shot_fixed'
}

''' + anchor
    assert s.count(anchor) == 1
    s = s.replace(anchor, add, 1)

# 2) calibrateAllShots：按模式计算
old = """  const out: CalibrateDiff[] = []
  for (const s of plan.shots ?? []) {
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)
    const from = Number(s.duration_sec ?? 0)
    const changed = Math.abs(from - t.need) > 0.05 || !shotTimingAligned(s, t)
    if (changed) {
      out.push({ shotNo: s.shot_no, from, to: t.need, segCount: t.segs.length, warn: t.warn })
      if (apply) {
        // overflow 策略：画面按模型上限（配音溢出到下一镜），其余策略画面覆盖整段
        s.duration_sec = policy === 'overflow' ? t.segs[0].duration_sec : t.need
        s.segments = t.segs
        ;(s as { stretch?: boolean | null }).stretch = t.stretch
      }
    }
  }
  return out"""
new = """  const mode = timingMode(plan)
  const out: CalibrateDiff[] = []
  for (const s of plan.shots ?? []) {
    const from = Number(s.duration_sec ?? 0)
    if (mode === 'shot_fixed') {
      // 配音顺排：镜长 = min(模型上限, 目标镜长)；配音位置一律不动（允许溢出到下一镜）
      const shot = s as { target_sec?: number | null }
      const target = Number(shot.target_sec ?? from ?? 0) || cap
      const dur = Math.max(minSeg, Math.min(cap, Math.round(target * 10) / 10))
      if (Math.abs(dur - from) > 0.05 || !shot.target_sec) {
        const note =
          target > cap + 0.05
            ? `目标 ${target}s 超过模型上限 ${cap}s → 镜长取 ${dur}s，配音顺排溢出到下一镜（仍是 1 次调用）`
            : ''
        out.push({ shotNo: s.shot_no, from, to: dur, segCount: 1, warn: note })
        if (apply) {
          shot.target_sec = Math.round(target * 10) / 10
          s.duration_sec = dur
          s.segments = [{ index: 0, start_sec: 0, duration_sec: dur }]
          ;(s as { stretch?: boolean | null }).stretch = false
        }
      }
      continue
    }
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)
    const changed = Math.abs(from - t.need) > 0.05 || !shotTimingAligned(s, t)
    if (changed) {
      out.push({ shotNo: s.shot_no, from, to: t.need, segCount: t.segs.length, warn: t.warn })
      if (apply) {
        // overflow 策略：画面按模型上限（配音溢出到下一镜），其余策略画面覆盖整段
        s.duration_sec = policy === 'overflow' ? t.segs[0].duration_sec : t.need
        s.segments = t.segs
        ;(s as { stretch?: boolean | null }).stretch = t.stretch
      }
    }
  }
  return out"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# 3) 体检：shot_fixed 模式下换一套检查（溢出信息 + 片尾）
old2 = """  const minSeg = Math.max(0.5, 32 / fps)
  const policy = oversizePolicy(plan)
  const issues: string[] = []
  for (const s of plan.shots ?? []) {
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)"""
new2 = """  const minSeg = Math.max(0.5, 32 / fps)
  const policy = oversizePolicy(plan)
  const mode = timingMode(plan)
  const issues: string[] = []
  if (mode === 'shot_fixed') {
    // 配音顺排模式：配音超出镜长是**预期行为**（会延续到下一镜），只检查真正的风险：
    //   ① 单镜配音远短于镜长（画面空等）② 全片音频总长超出画面总长（片尾会被切）
    let videoTotal = 0
    let audioEnd = 0
    for (const s of plan.shots ?? []) {
      const d = Number(s.duration_sec ?? 0)
      const lines = s.narrations ?? []
      let last = 0
      lines.forEach((l, i) => {
        const at = Math.max(0, Number(l.at_sec ?? 0))
        const ms = durations[`${s.shot_no}:${i}`]
        const actual = ms && ms > 0 ? ms / 1000 : null
        const end = Number(l.end_sec ?? 0)
        const len = actual ?? (end > at ? end - at : Math.max(0, (l.text ?? '').trim().length / 4.5))
        last = Math.max(last, at + len)
      })
      if (lines.length && last > 0 && last < d - 1.5) {
        issues.push(`第 ${s.shot_no} 镜：镜头 ${d}s，配音只到 ${last.toFixed(1)}s（画面会空等）`)
      }
      videoTotal += d
      audioEnd = Math.max(audioEnd, videoTotal - d + last)
    }
    if (audioEnd > videoTotal + 0.05) {
      issues.push(
        `全片配音到 ${audioEnd.toFixed(1)}s，画面只到 ${videoTotal.toFixed(1)}s`
        + `（片尾会多出 ${(audioEnd - videoTotal).toFixed(1)}s；渲染会自动补尾画面）`,
      )
    }
    return issues
  }
  for (const s of plan.shots ?? []) {
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)"""
assert s.count(old2) == 1
s = s.replace(old2, new2, 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("plan.ts: timing_mode (shot_fixed default)")

# 4) 类型
p2 = "web/src/api/types.ts"
t = io.open(p2, encoding="utf-8").read()
o = """    oversize_policy?: 'stretch' | 'overflow' | 'segment'"""
n = """    oversize_policy?: 'stretch' | 'overflow' | 'segment'
    /**
     * P13：镜头时长由谁决定。
     * shot_fixed（默认）= 镜长取 min(模型上限, 目标镜长)，配音顺排、允许溢出到下一镜（每镜 1 次调用）；
     * audio_first = 镜长跟着配音走（超上限时按 oversize_policy 处理）。
     */
    timing_mode?: 'shot_fixed' | 'audio_first'"""
assert t.count(o) == 1
t = t.replace(o, n, 1)
o2 = """  stretch?: boolean | null"""
n2 = """  stretch?: boolean | null
  /** P13：作者设定的目标镜长（shot_fixed 模式下用它取 min(模型上限, 目标)） */
  target_sec?: number | null"""
assert t.count(o2) == 1
t = t.replace(o2, n2, 1)
io.open(p2, "w", encoding="utf-8", newline="\n").write(t)
print("types.ts: timing_mode + target_sec")
