#!/usr/bin/env python3
"""修复 calibrateAllDurations / healthCheckBeforeRender 里被写成真实换行的字符串。幂等。"""
import io
import re

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()

start = s.find("async function calibrateAllDurations")
end = s.find("/** 渲染前音画体检：", start)
if start < 0 or end < 0:
    raise SystemExit("anchors not found")

NL = chr(92) + "n"  # literal backslash-n for TS source

block = """async function calibrateAllDurations(): Promise<void> {
  const p0 = draft.value
  if (!p0 || !isVideoPlan(p0)) {
    message.info('仅视频项目支持按配音校准')
    return
  }
  const p = p0
  const cap = modelClipCap(p)
  const tail = planTailSec(p)
  const diffs = calibrateAllShots(p as never, durations.value, false)
  if (!diffs.length) {
    message.success('全部镜头已与配音一致（模型上限 ' + cap + 's、余量 ' + tail + 's）')
    return
  }
  const lines = diffs
    .slice(0, 12)
    .map((d) => {
      const seg = d.segCount > 1 ? '（切 ' + d.segCount + ' 段）' : ''
      return '第 ' + d.shotNo + ' 镜：' + d.from + 's → ' + d.to + 's' + seg
    })
  const more = diffs.length > 12 ? '__NL__…另有 ' + (diffs.length - 12) + ' 个镜头' : ''
  const ok = window.confirm(
    '按配音校准（模型单次上限 ' + cap + 's、尾部余量 ' + tail + 's）：__NL____NL__' +
      lines.join('__NL__') +
      more +
      '__NL____NL__确认写入方案？',
  )
  if (!ok) return
  calibrateAllShots(p as never, durations.value, true)
  if (!(await savePlanInPlace())) {
    message.warning('已改时长，但方案保存失败，请稍后重试')
    return
  }
  message.success('已按配音校准 ' + diffs.length + ' 个镜头')
}

"""
block = block.replace("__NL__", NL)

block2 = """/** 渲染前音画体检：配音超镜/镜头空等/超上限未分段 等 */
function healthCheckBeforeRender(): boolean {
  const p = draft.value
  if (!p || !isVideoPlan(p)) return true
  const issues = audioVideoHealthCheck(p as never, durations.value)
  if (!issues.length) return true
  const head = issues.slice(0, 8).join('__NL__')
  const more = issues.length > 8 ? '__NL__…另有 ' + (issues.length - 8) + ' 条' : ''
  return window.confirm(
    '渲染前检查发现问题：__NL____NL__' + head + more + '__NL____NL__仍要渲染吗？（建议先点「按配音校准时长」）',
  )
}

""".replace("__NL__", NL)

s = s[:start] + block + block2 + s[end:]
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# 自检：不应再出现跨行的单引号字符串（join(' 后紧跟换行）
bad = re.findall(r"join\('\r?\n", s)
print("broken-join left:", len(bad))
print("patched")
