#!/usr/bin/env python3
"""P13-lipsync 前端：Tab / 标签 / 对口型按钮。幂等。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()

if "'lipsync'" not in s:
    reps = [
        # Tab 定义
        ("""  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'all', label: '全部', hint: '全部任务（项多，缩略图按需懒加载）' },""",
         """  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'lipsync', label: '对口型', kind: 'lipsync', hint: '音频驱动嘴型的片段（lipsync）' },
  { key: 'all', label: '全部', hint: '全部任务（项多，缩略图按需懒加载）' },"""),
        ("""  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'all', label: '全部', hint: '全部产物（项多，缩略图按需懒加载）' },""",
         """  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'lipsync', label: '对口型', kind: 'lipsync', hint: '音频驱动嘴型的片段（lipsync）' },
  { key: 'all', label: '全部', hint: '全部产物（项多，缩略图按需懒加载）' },"""),
        # kind → Tab
        ("""function kindTab(kind: string): AudioTab {
  if (kind === 'portrait') return 'portrait'""",
         """function kindTab(kind: string): AudioTab {
  if (kind === 'portrait') return 'portrait'
  if (kind === 'lipsync') return 'lipsync'"""),
        # 名称
        ("const KIND_LABEL: Record<string, string> = { still: '关键帧', clip: '运动', voice: '配音', bgm: '配乐' }",
         "const KIND_LABEL: Record<string, string> = { still: '关键帧', clip: '运动', voice: '配音', bgm: '配乐', lipsync: '对口型' }"),
    ]
    for a, b in reps:
        if a in s:
            s = s.replace(a, b, 1)
            print("  ok: %s" % a.splitlines()[0][:52])
        else:
            print("  MISS: %s" % a.splitlines()[0][:52])

    # AudioTab 类型加 lipsync
    tp = "web/src/views/ProjectDetailView.vue"
    s = s.replace("type AudioTab =", "type AudioTab =", 1)

    # 对口型按钮：放在「运动(motion)」按钮之后（同属视频类）
    anchor = """            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-voice-jobs\""""
    add = """            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              :disabled="!detApproved"
              data-testid="btn-lipsync-jobs"
              :title="detApproved
                ? '对口型：用该镜配音驱动嘴型（需本机已装口型工作流，见 docs/lipsync-setup.md）'
                : '需先确认方案'"
              @click="startLipsync()"
            >
              对口型
            </NButton>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-voice-jobs\""""
    if anchor in s:
        s = s.replace(anchor, add, 1)
        print("  ok: lipsync button")
    else:
        print("  MISS: lipsync button anchor")

    # startLipsync 方法（放在 startMotion 附近）
    a2 = "async function startMotion(frames: number, shotNos: number[] | null): Promise<void> {"
    add2 = """/** P13 对口型：该镜的 motion/关键帧 + 该镜配音 → 音频驱动嘴型（本机 ComfyUI 工作流） */
async function startLipsync(): Promise<void> {
  focusJobTab('lipsync')
  if (dirty.value && !(await savePlanInPlace())) return
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: selectedRevId.value as string,
      kind: 'lipsync',
    })
    if (!created.length) {
      message.warning('没有可对口型的镜头（需该镜已有 motion/关键帧且已生成配音）')
      return
    }
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已排入 ${created.length} 个对口型任务`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '对口型任务创建失败')
  } finally {
    genBusy.value = false
  }
}

async function startMotion(frames: number, shotNos: number[] | null): Promise<void> {"""
    if a2 in s:
        s = s.replace(a2, add2, 1)
        print("  ok: startLipsync()")
    else:
        print("  MISS: startMotion anchor")

    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("frontend patched")
else:
    print("already patched")
