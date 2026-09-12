#!/usr/bin/env python3
"""P13：任务↔资产库联动。①点按钮切 Tab ②完成后刷新并定位最新资产 ③只显示本类型版本/数量。幂等。"""
import io

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "galFollowTab" in s:
    print("already patched")
    raise SystemExit(0)

# ---------- ① 点按钮 → 资产库切到对应 Tab ----------
s = s.replace("""/** 生成任务时自动切到对应 Tab，否则用户看不到刚发起任务的进度 */
function focusJobTab(t: AudioTab): void {
  pickJobTab(t)
}""",
              """/**
 * 生成任务时自动切到对应 Tab（任务区 + **资产库**），否则用户看不到刚发起任务的进度与产物。
 * 例：点「生成关键帧」→ 任务区切「关键帧」、资产库也切「关键帧」。
 */
function focusJobTab(t: AudioTab): void {
  pickJobTab(t)
  galFollowTab(t)
}
/** 资产库跟随操作切换（master/still/... 与任务 Tab 同名同义） */
function galFollowTab(t: AudioTab): void {
  if (!GAL_TABS.some((x) => x.key === t)) return
  galTab.value = t
  galTabPinned.value = true
  rememberTab(GAL_TAB_KEY, t)
  galNewestHint.value = ''
}

/** 刚完成一批任务时高亮的最新资产 id（自动定位用） */
const galNewestHint = ref('')""", 1)

# ---------- ② 任务完成后刷新资产库 + 定位最新资产 ----------
old_watch = """    } else if (n === 0 && jobsTimer) {
      clearInterval(jobsTimer)
      jobsTimer = undefined
      // 任务全部结束后资产已落库 → 自动刷新资产库预览
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    }"""
new_watch = """    } else if (n === 0 && jobsTimer) {
      clearInterval(jobsTimer)
      jobsTimer = undefined
      // 任务全部结束后资产已落库 → 刷新资产库，并**定位到当前 Tab 最新的一条**方便查看
      void (async () => {
        await queryClient.invalidateQueries({ queryKey: ['assets'] })
        await nextTick()
        await refreshGallery()
        focusNewestAsset()
      })()
    }"""
assert s.count(old_watch) == 1
s = s.replace(old_watch, new_watch, 1)

s = s.replace("""async function refreshGallery(): Promise<void> {""",
              """/**
 * 定位到当前 Tab 里最新的一条资产：滚到可视区并短暂高亮。
 * （生成完成后自动调用，省得用户自己在资产库里翻）
 */
function focusNewestAsset(): void {
  const list = [...galleryForTab.value].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  )
  const newest = list[0]
  if (!newest) return
  galNewestHint.value = newest.id
  requestAnimationFrame(() => {
    const el = document.querySelector(`[data-testid="asset-${newest.id}"]`) as HTMLElement | null
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  window.setTimeout(() => { galNewestHint.value = '' }, 3000)
  message.info('资产库已刷新，并定位到最新产物')
}

async function refreshGallery(): Promise<void> {""", 1)

# 资产格子：data-testid + 高亮类
s = s.replace("""          <div v-for="a in galleryForTab" :key="a.id" :class="['g-item', { manage: galManage, sel: galSel.includes(a.id) }]">""",
              """          <div v-for="a in galleryForTab" :key="a.id"
               :class="['g-item', { manage: galManage, sel: galSel.includes(a.id), newest: galNewestHint === a.id }]"
               :data-testid="`asset-${a.id}`">""", 1)

# nextTick 引入
s = s.replace("import { computed, h, onErrorCaptured, ref, watch } from 'vue'",
              "import { computed, h, nextTick, onErrorCaptured, ref, watch } from 'vue'", 1)

# ---------- ③ 分镜勾选弹窗：只显示当前操作类型的版本/数量 ----------
s = s.replace("""/** 弹窗要展示的分镜列表：镜号 + 该镜资源版本（取最新一条 still/voice 产物所属版本）+ 语音段数 */
const pickerShots = computed(() => {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return []
  return (plan.shots ?? []).map((s) => {
    const rel = (jobs.data.value ?? []).filter(
      (j) => j.payload?.shot_no === s.shot_no && ['still', 'clip', 'voice'].includes(j.kind) && j.state === 'succeeded',
    )
    const newest = newestStamp(rel)
    const rev = newest ? revOfJob(newest) : undefined
    return {
      shotNo: s.shot_no,
      revNo: rev?.no ?? null,
      stale: rev?.stale === true,
      lineCount: (s.narrations ?? []).length,
    }
  })
})""",
              """/**
 * 弹窗要展示的分镜列表：镜号 + **当前操作类型**的资源版本与数量。
 *
 * 点「生成关键帧」就只看 still 的版本/张数；点「生成配音」才看 voice 的版本/段数 ——
 * 混着显示其它类型（如语音段数）对当前操作没意义。
 */
const pickerKind = ref<'still' | 'clip' | 'voice'>('still')
const pickerShots = computed(() => {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return []
  const kind = pickerKind.value
  return (plan.shots ?? []).map((s) => {
    const rel = (jobs.data.value ?? []).filter(
      (j) => j.payload?.shot_no === s.shot_no && j.kind === kind && j.state === 'succeeded',
    )
    const newest = newestStamp(rel)
    const rev = newest ? revOfJob(newest) : undefined
    return {
      shotNo: s.shot_no,
      revNo: rev?.no ?? null,
      stale: rev?.stale === true,
      // 只有配音才显示段数；关键帧/motion 显示该镜已产出的张数
      lineCount: kind === 'voice' ? (s.narrations ?? []).length : rel.length,
      unit: kind === 'voice' ? '段语音' : '张已出',
    }
  })
})""", 1)

# withShotPicker 接收 kind
s = s.replace("""async function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
): Promise<void> {
  // P13：生成前预检（未确认改动 → 先问）
  if (!(await ensureApprovedForGenerate(title))) return""",
              """async function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
  kind: 'still' | 'clip' | 'voice' = 'still',
): Promise<void> {
  pickerKind.value = kind
  // P13：生成前预检（未确认改动 → 先问）
  if (!(await ensureApprovedForGenerate(title))) return""", 1)

# 调用处补 kind
s = s.replace("withShotPicker(isVideoNow ? '生成关键帧(still)' : '开始生成', (nos) => startGeneration(nos))",
              "withShotPicker(isVideoNow ? '生成关键帧(still)' : '开始生成', (nos) => startGeneration(nos), 'still')", 1)
s = s.replace("withShotPicker('生成配音(voice)', (nos) => startVoice(nos))",
              "withShotPicker('生成配音(voice)', (nos) => startVoice(nos), 'voice')", 1)
s = s.replace("withShotPicker('运动(motion)', (shotNos) => startMotion(f, shotNos))",
              "withShotPicker('运动(motion)', (shotNos) => startMotion(f, shotNos), 'clip')", 1)

# 弹窗展示单位
s = s.replace("""          <span v-if=\"s.lineCount\" class=\"sp-extra font-mono\">{{ s.lineCount }} 段语音</span>""",
              """          <span v-if=\"s.lineCount\" class=\"sp-extra font-mono\">{{ s.lineCount }} {{ s.unit }}</span>""", 1)

# 高亮样式
s = s.replace("<style scoped>", """<style scoped>
.g-item.newest {
  outline: 2px solid var(--wv-accent);
  outline-offset: 2px;
  border-radius: 10px;
  animation: gal-pop 1.6s ease-out 1;
}
@keyframes gal-pop {
  0% { box-shadow: 0 0 0 6px color-mix(in srgb, var(--wv-accent) 35%, transparent); }
  100% { box-shadow: 0 0 0 0 transparent; }
}
""", 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
