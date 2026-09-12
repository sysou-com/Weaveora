#!/usr/bin/env python3
"""P13：生成前预检 —— 若有未确认的改动，弹框让你选「保存并确认后生成 / 用已确认稿继续」。幂等。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "ensureApprovedForGenerate" in s:
    print("already patched")
    raise SystemExit(0)

HELPER = '''/**
 * P13：生成前预检（解决「忘记保存+确认，于是生成还读旧稿」的坑）。
 *
 * 生成永远以**已确认稿**为准；如果界面上还有未保存的改动、或当前版本尚未确认，
 * 就在这里把差异摊给你看并让你决定：保存并确认后再生成 / 就用已确认稿生成。
 *
 * @return true = 可以继续生成；false = 用户取消
 */
async function ensureApprovedForGenerate(action: string): Promise<boolean> {
  const pending = dirty.value || !detApproved.value
  if (!pending) return true
  const diffs = changeSummary.value.filter((x) => !x.startsWith('（仅有'))
  return await new Promise<boolean>((resolve) => {
    dialog.warning({
      title: `${action}：方案有未确认的改动`,
      content: () => h('div', { style: 'line-height:1.9;font-size:13px' }, [
        h('p', { style: 'margin:0 0 6px' }, [
          '生成/渲染只认**已确认稿**。当前改动：',
          h('b', diffs.length ? diffs.join('、') : '（元数据/未保存内容）'),
        ]),
        h('p', { style: 'margin:0;color:#8a94a6' },
          detApproved.value
            ? '点「保存并确认」会先保存当前草稿，再把它设为生成基准。'
            : '当前版本尚未确认；点「保存并确认」会把它设为生成基准。'),
      ]),
      positiveText: '保存并确认后生成',
      negativeText: '用已确认稿生成',
      onPositiveClick: async () => {
        if (dirty.value && !(await handleSave())) {
          resolve(false)
          return false
        }
        await handleApprove()
        resolve(true)
        return true
      },
      onNegativeClick: () => resolve(true),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
}

'''

anchor = "/** P13：是否需要「确认」（当前是未确认版本，或草稿有未保存的改动） */"
assert s.count(anchor) == 1
s = s.replace(anchor, HELPER + anchor, 1)

# h 需要引入
s = s.replace("import { computed, onErrorCaptured, ref, watch } from 'vue'",
              "import { computed, h, onErrorCaptured, ref, watch } from 'vue'", 1)

# ① 关键帧 / motion：在 withShotPicker 里统一拦
s = s.replace("""function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
): void {
  if (pickerShots.value.length <= 3) {
    void run(null)
    return
  }""",
              """async function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
): Promise<void> {
  // P13：生成前预检（未确认改动 → 先问）
  if (!(await ensureApprovedForGenerate(title))) return
  if (pickerShots.value.length <= 3) {
    void run(null)
    return
  }""" + """""".join([]))
s = s.replace("""  pickCtx.value = { title, run }
  pickOpen.value = true
}""",
              """  pickCtx.value = { title, run }
  pickOpen.value = true
}""", 1)

# ② 配音
s = s.replace("""async function startVoice(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  focusJobTab('voice')""",
              """async function startVoice(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (!(await ensureApprovedForGenerate('生成配音'))) return
  focusJobTab('voice')""", 1)

# ③ 配乐
s = s.replace("""async function startBgm(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  focusJobTab('bgm')""",
              """async function startBgm(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (!(await ensureApprovedForGenerate('生成配乐'))) return
  focusJobTab('bgm')""", 1)

# ④ 合成 / 导出
s = s.replace("""async function doRender(): Promise<void> {
  if (!selectedRevId.value) return""",
              """async function doRender(): Promise<void> {
  if (!selectedRevId.value) return
  if (!(await ensureApprovedForGenerate('渲染成片'))) return""", 1)
s = s.replace("""async function doExport(): Promise<void> {
  if (!selectedRevId.value) return""",
              """async function doExport(): Promise<void> {
  if (!selectedRevId.value) return
  if (!(await ensureApprovedForGenerate('导出成片包'))) return""", 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("guard wired")
