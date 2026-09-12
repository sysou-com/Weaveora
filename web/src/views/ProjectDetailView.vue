<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  Check,
  CheckCheck,
  RefreshCw,
  Save,
  WandSparkles,
} from 'lucide-vue-next'
import { NAlert, NButton, NIcon, NInputNumber, NModal, NSkeleton, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, onErrorCaptured, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  approveRevision,
  approveShot,
  generateDirector,
  getRevision,
  listRevisions,
  patchRevision,
  rewritePromptFromZh,
  type RewriteResult,
} from '@/api/director'
import { createBrief, listBriefs } from '@/api/briefs'
import { createJobs, listJobs, cancelJob, rerunJob, retryJobs, deleteJobs, JOB_STATE_LABEL } from '@/api/jobs'
import { shareProject } from '@/api/market'
import { listAssets, uploadReference, fetchAssetBlob, deleteAssets, uploadVoiceLine, useSampleAsLineVoice, deleteVoicePreset, auditionVoicePreset } from '@/api/assets'
import { createExport, fetchExportBlob, renderMaster, timecode } from '@/api/export'
import { aiGenerateLines, aiGenerateMusic, extractSubjects } from '@/api/director'
import { getEngineSettings } from '@/api/engineSettings'
import { getProject, updateProjectDuration } from '@/api/projects'
import { listShotLocks, setShotLocks } from '@/api/shotLocks'
import type { AssetRef, DirectorPlan, DirectorShot, JobRecord, PlanSubject, PlanSubjectRef } from '@/api/types'
import BriefComposer from '@/components/director/BriefComposer.vue'
import ImagePlanEditor from '@/components/director/ImagePlanEditor.vue'
import RevisionRail from '@/components/director/RevisionRail.vue'
import ShotPickerDialog from '@/components/director/ShotPickerDialog.vue'
import VoiceCloneDialog from '@/components/director/VoiceCloneDialog.vue'
import VideoPlanEditor from '@/components/director/VideoPlanEditor.vue'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, modeLabel } from '@/utils/format'
import {
  SOURCE_LABEL,
  autoLayoutShot,
  clonePlan,
  isVideoPlan,
  normalizePlan,
  planProblems,
  round2,
  shotHasText,
} from '@/utils/plan'
import type { ImagePlan, VideoPlan } from '@/api/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const message = useMessage()
const queryClient = useQueryClient()

// 渲染错误浮出（调试：若方案编辑器某处运行时报错，控制台可见原因）
onErrorCaptured((err, _instance, info) => {
  console.error('[weaveora detail render error]', info, err)
  return false
})

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')
const projectId = computed(() => String(route.params.projectId ?? ''))

// ---------- 服务端状态 ----------
const project = useQuery({
  queryKey: computed(() => ['project', workspaceId.value, projectId.value]),
  queryFn: () => getProject(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})

const briefs = useQuery({
  queryKey: computed(() => ['briefs', workspaceId.value, projectId.value]),
  queryFn: () => listBriefs(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})

const revisions = useQuery({
  queryKey: computed(() => ['revisions', workspaceId.value, projectId.value]),
  queryFn: () => listRevisions(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})

const selectedRevId = ref<string | null>(null)
const detail = useQuery({
  queryKey: computed(() => ['revision', workspaceId.value, projectId.value, selectedRevId.value ?? '']),
  queryFn: () => getRevision(workspaceId.value, projectId.value, selectedRevId.value as string),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== '' && !!selectedRevId.value),
})

// 默认选中最新版本；revision 列表变化时补选
watch(
  () => revisions.data.value,
  (list) => {
    if (!list || list.length === 0) {
      selectedRevId.value = null
      return
    }
    if (!selectedRevId.value || !list.some((r) => r.id === selectedRevId.value)) {
      selectedRevId.value = list[0].id
    }
  },
  { immediate: true },
)

// ---------- 编辑草稿（仅未确认版本可改） ----------
const draft = ref<DirectorPlan | null>(null)
const pristineJson = ref('')
const initKey = ref('')
const dirty = ref(false)
const briefEditing = ref(false)

/** 分支后取非空副本（编辑器只在该分支渲染时使用；副本与原对象共享引用，深改仍命中 reactive） */
const imgPlanForEdit = computed<ImagePlan>(() => draft.value as ImagePlan)
const vidPlanForEdit = computed<VideoPlan>(() => draft.value as VideoPlan)

watch(
  () => detail.data.value,
  (det) => {
    if (!det) return
    const key = `${det.id}#${det.revisionNo}`
    if (initKey.value === key && draft.value) return
    initKey.value = key
    draft.value = normalizePlan(clonePlan(det.plan))
    // P4：从方案回填参考图选择与主体标注（参考图随方案落库）
    const ra = (draft.value as unknown as { referenceAssets?: Array<{ assetId?: string; subject?: string }> }).referenceAssets
    const ids: string[] = []
    const subjects: Record<string, string> = {}
    const regions: Record<string, { x: string; y: string; w: string; h: string }> = {}
    if (Array.isArray(ra)) {
      for (const b of ra) {
        const aid = String(b?.assetId ?? '')
        if (!aid) continue
        ids.push(aid)
        if (b?.subject) subjects[aid] = String(b.subject)
        const rg = (b as { region?: { x?: number; y?: number; w?: number; h?: number } })?.region
        if (rg && Number.isFinite(Number(rg.w)) && Number(rg.w) > 0) {
          regions[aid] = {
            x: String(Math.round(Number(rg.x ?? 0) * 100)),
            y: String(Math.round(Number(rg.y ?? 0) * 100)),
            w: String(Math.round(Number(rg.w) * 100)),
            h: String(Math.round(Number(rg.h ?? 0) * 100)),
          }
        }
      }
    }
    refSelected.value = ids
    refSubjects.value = subjects
    refRegions.value = regions
    pristineJson.value = JSON.stringify(draft.value)
    dirty.value = false
  },
  { immediate: true },
)

watch(draft, () => {
  dirty.value = draft.value !== null && JSON.stringify(draft.value) !== pristineJson.value
}, { deep: true })

const detApproved = computed(() => detail.data.value?.approved === true)
// 已确认版本也可编辑（保存时后端自动解除确认，改后可重新确认）
const canEdit = computed(() => !!draft.value)
const latestBrief = computed(() => briefs.data.value?.[0] ?? null)
const activeRevision = computed(() =>
  (revisions.data.value ?? []).find((r) => r.id === selectedRevId.value) ?? null,
)
const problems = computed(() => (draft.value ? planProblems(draft.value) : []))

const isImageNow = computed(() => draft.value?.mode === 'image')
const isVideoNow = computed(() => draft.value?.mode === 'video')

/** 编辑 Brief 的入口模式：auto → 跟随项目；否则跟随该 brief 的显式模式 */
const composerMode = computed(() => {
  const pm = project.data.value?.mode ?? 'image'
  const bm = latestBrief.value?.mode
  if (bm === 'image' || bm === 'video') return bm
  if (pm === 'image' || pm === 'video' || pm === 'mixed') return pm
  return 'image'
})

const shownBrief = computed(() => {
  const bid = detail.data.value?.briefId
  if (!bid) return latestBrief.value
  return (briefs.data.value ?? []).find((b) => b.id === bid) ?? latestBrief.value
})

// ---------- 忙碌状态 ----------
const creating = ref(false)
const generating = ref(false)
const saving = ref(false)
const approving = ref(false)
const shotBusy = ref<number | null>(null)

async function invalidateAll(): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['revisions'] }),
    queryClient.invalidateQueries({ queryKey: ['revision'] }),
    queryClient.invalidateQueries({ queryKey: ['briefs'] }),
    queryClient.invalidateQueries({ queryKey: ['project'] }),
  ])
}

async function doGenerate(briefId: string, dirMode?: 'image' | 'video'): Promise<void> {
  generating.value = true
  try {
    const res = await generateDirector(workspaceId.value, projectId.value, {
      briefId,
      ...(dirMode ? { mode: dirMode } : {}),
    })
    await invalidateAll()
    selectedRevId.value = res.revisionId
    if (res.source === 'stub') {
      message.info('当前为「示例方案」：配置 weaveora.llm.* 后切换真实导演', { duration: 5000 })
    } else {
      message.success(`导演层给出 v${res.revisionNo}`)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '导演层暂时不可用')
  } finally {
    generating.value = false
  }
}

// ---------- W2C 参考图 ----------
const assets = useQuery({
  queryKey: computed(() => ['assets', workspaceId.value, projectId.value]),
  queryFn: () => listAssets(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})
const refAssets = computed(() => (assets.data.value ?? []).filter((a) => a.kind === 'reference'))

/**
 * P10：各段配音的**实际时长**（"镜号:段号" → 毫秒）。
 * 资产列表按 createdAt DESC，所以同一段取先遇到的那条（即最新一次生成/导入）。
 */
function voiceDurations(): Record<string, number> {
  const m: Record<string, number> = {}
  for (const a of assets.data.value ?? []) {
    if (a.kind !== 'voice' || a.shotNo == null || a.lineIndex == null) continue
    const ms = a.durationMs
    if (!ms || ms <= 0) continue
    const k = `${a.shotNo}:${a.lineIndex}`
    if (!(k in m)) m[k] = ms
  }
  return m
}
const durations = computed(() => voiceDurations())

/**
 * P10：生成完一条后，按实际时长自动铺排该镜（只动未被手动改过的段）：
 *   - end_sec = at_sec + 实际时长
 *   - 下一段若与上一段重叠 → 后移
 * 需要先等 assets 查询刷新，否则拿不到刚生成的那条时长。
 */
async function autoLayoutAfterGen(shotNo: number): Promise<void> {
  await queryClient.refetchQueries({ queryKey: ['assets'] })
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const shot = (plan.shots ?? []).find((s) => s.shot_no === shotNo)
  if (shot && autoLayoutShot(shot, voiceDurations())) {
    message.info(`第 ${shotNo} 镜已按配音实际时长自动对齐（可拖拽细调）`, { duration: 4000 })
  }
}

/** P10：分镜请求调整本镜（延长时长 / 允许溢出） */
function onPatchShot(shotNo: number, patch: { duration_sec?: number; allowNarrationOverflow?: boolean }): void {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const shot = (plan.shots ?? []).find((s) => s.shot_no === shotNo)
  if (!shot) return
  if (patch.duration_sec != null) {
    shot.duration_sec = patch.duration_sec
    shot.allowNarrationOverflow = null
    message.success(`第 ${shotNo} 镜时长已改为 ${patch.duration_sec.toFixed(1)}s`)
  }
  if (patch.allowNarrationOverflow) {
    shot.allowNarrationOverflow = true
    message.info(`第 ${shotNo} 镜已允许配音溢出到下一镜（不再提醒）`)
  }
}

/* ---------------- P11 AI 音频助手 ---------------- */
const aiAudioBusy = ref(false)

/** 把 AI 铺好的台词写进该镜（replace=true 覆盖，false 追加） */
function applyAiLines(
  shotNo: number,
  lines: Array<{ at_sec: number; end_sec: number; text: string; kind: string; subject?: string | null; speed: number }>,
  replace: boolean,
): void {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const shot = (plan.shots ?? []).find((s) => s.shot_no === shotNo)
  if (!shot) return
  const mapped = lines.map((l) => ({
    at_sec: l.at_sec,
    end_sec: l.end_sec,
    text: l.text,
    kind: (l.kind === 'dialogue' ? 'dialogue' : 'narration') as 'narration' | 'dialogue',
    subject: l.subject ?? null,
    // 不写死 voice：由角色绑定解析，避免“改了绑定但旧台词仍用老音色”
    voice: null,
    speed: l.speed,
    manual: false,
  }))
  if (replace) {
    // P12：AI 只写对白，“覆盖”只重写 AI 铺的台词，**不动手动新增的段**（旁白都是手加的）
    const kept = (shot.narrations ?? []).filter((n) => n.manual === true)
    if (kept.length) {
      // 手加段落留在原位置，AI 台词整体顺延到其后，避免两段叠在同一时刻
      const r1 = (v: number): number => Math.round(v * 10) / 10
      const endOf = (n: { at_sec?: number | null; end_sec?: number | null; text?: string | null }): number => {
        if (n.end_sec != null && Number(n.end_sec) > 0) return Number(n.end_sec)
        const at = Number(n.at_sec ?? 0)
        const chars = (n.text ?? '').replace(/\s/g, '').length
        return at + Math.max(0.8, chars / 4.5)
      }
      const offset = r1(Math.max(...kept.map(endOf)) + 0.25)
      const shifted = mapped.map((l) => ({ ...l, at_sec: r1(l.at_sec + offset), end_sec: r1(l.end_sec + offset) }))
      shot.narrations = [...kept, ...shifted]
      message.info(`保留了 ${kept.length} 段手动新增的语音，AI 台词已顺延到 ${offset}s 之后（可拖动调整）`)
    } else {
      shot.narrations = mapped
    }
  } else {
    shot.narrations = [...(shot.narrations ?? []), ...mapped]
  }
  message.success(`第 ${shotNo} 镜已写入 ${mapped.length} 段 AI 台词（记得保存方案）`)
}

/** AI 一键生成台词（本镜）；已有台词时询问追加还是覆盖 */
async function onAiLines(shotNo: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const shot = (plan.shots ?? []).find((s) => s.shot_no === shotNo)
  if (!shot) return
  const hasLines = (shot.narrations ?? []).length > 0

  const run = async (replace: boolean): Promise<void> => {
    aiAudioBusy.value = true
    try {
      const r = await aiGenerateLines(workspaceId.value, projectId.value, revId, shotNo, replace)
      const sl = r.shots.find((x) => x.shotNo === shotNo)
      for (const n of r.notes ?? []) message.info(n, { duration: 5000 })
      if (!sl || !sl.lines.length) {
        message.warning('AI 没有给出可用台词（可能是未配置 LLM，或本镜信息不足）')
        return
      }
      applyAiLines(shotNo, sl.lines, replace)
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'AI 台词生成失败')
    } finally {
      aiAudioBusy.value = false
    }
  }

  if (hasLines) {
    dialog.warning({
      title: `第 ${shotNo} 镜已有台词`,
      content: 'AI 生成的台词是「追加」到后面，还是「覆盖」掉现有 AI 台词？（手动新增的语音两都不会删）',
      positiveText: '覆盖',
      negativeText: '追加',
      onPositiveClick: () => void run(true),
      onNegativeClick: () => void run(false),
    })
    return
  }
  await run(false)
}

/** AI 一键配乐（按剧情分段，覆盖现有 music[]） */
async function onAiMusic(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  aiAudioBusy.value = true
  try {
    const r = await aiGenerateMusic(workspaceId.value, projectId.value, revId)
    for (const n of r.notes ?? []) message.info(n, { duration: 5000 })
    if (!r.music?.length) {
      message.warning('AI 没有给出可用配乐（可能是未配置 LLM）')
      return
    }
    plan.audio.music = r.music.map((c) => ({
      id: c.id,
      start_sec: c.start_sec,
      end_sec: c.end_sec,
      mood: c.mood,
      gain_db: c.gain_db,
      fade_in_sec: c.fade_in_sec,
      fade_out_sec: c.fade_out_sec,
      loop: c.loop,
      duck: c.duck,
    }))
    message.success(`已写入 ${r.music.length} 段配乐（记得保存方案，然后点「生成配乐」渲染）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'AI 配乐生成失败')
  } finally {
    aiAudioBusy.value = false
  }
}
/** 参考图按上传时间倒序（最新在前，防旧图排在前面看不清新上传） */
const refAssetsSorted = computed(() =>
  [...refAssets.value].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
)
function shortTime(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
const refSelected = ref<string[]>([])
/** P13：未勾选 = 不作为参考（默认全部参与） */
const refUnchecked = ref<string[]>([])
const subjectBusy = ref('')
const portraitBusy = ref(false)

/** P4 参考主体标注：assetId → 主体名（如 唐僧）；随方案 referenceAssets 落库，生成时按镜文本自动绑定 */
const refSubjects = ref<Record<string, string>>({})
/** P5 区域遮罩：assetId → 百分比 x/y/w/h（0–100，可选；填全且合法才生效） */
const refRegions = ref<Record<string, { x: string; y: string; w: string; h: string }>>({})

/** 资产 id → 资产（任意 kind），用于把资产库选中的图显示到参考图卡片 */
const assetById = computed<Record<string, AssetRef>>(() => {
  const m: Record<string, AssetRef> = {}
  for (const a of assets.data.value ?? []) m[a.id] = a
  return m
})
/** 已选参考（含资产库来源的 still/clip/master），按选择顺序 */
const selectedRefAssets = computed<AssetRef[]>(() =>
  refSelected.value.map((id) => assetById.value[id]).filter((a): a is AssetRef => !!a),
)
/** 参考图卡片顶部图库：项目内 reference 类上传 + 已选但不在图库里的（资产库来源） */
const refLibrary = computed<AssetRef[]>(() => {
  const lib = [...refAssetsSorted.value]
  const known = new Set(lib.map((a) => a.id))
  for (const a of selectedRefAssets.value) if (!known.has(a.id)) lib.push(a)
  return lib
})

/** P13：当前方案里的剧情主体（含定妆图状态） */
function planSubjects(): PlanSubject[] {
  const plan = draft.value
  if (!plan) return []
  const subs = (plan as unknown as { subjects?: PlanSubject[] }).subjects
  return Array.isArray(subs) ? subs : []
}
/** 写回 plan.subjects（就地改 draft，触发脏标记） */
function setPlanSubjects(subs: PlanSubject[]): void {
  const plan = draft.value
  if (!plan) return
  ;(plan as unknown as { subjects?: PlanSubject[] }).subjects = subs
  syncReferenceAssets()
}
/** 主体 → 该主体的定妆图资产（同主体多版时取最新） */
function portraitsOf(name: string): Array<{ id: string; url?: string; width?: number | null; height?: number | null }> {
  return (assets.data.value ?? [])
    .filter((a) => a.kind === 'portrait' && (a.promptSnapshot as Record<string, unknown> | undefined)?.subject === name)
    .map((a) => ({ id: a.id, url: galUrls.value[a.id], width: a.width, height: a.height }))
}
/** 一键生成主体（LLM 抽取；已有主体保留，只补新的） */
async function onExtractSubjects(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  subjectBusy.value = 'extract'
  try {
    const r = await extractSubjects(workspaceId.value, projectId.value, revId)
    const subs = planSubjects()
    const byName = new Map(subs.map((x) => [x.name, x]))
    for (const p of r.subjects ?? []) {
      const old = byName.get(p.name)
      if (old) {
        // 已存在：只补别名，不动勾选/定妆图
        const aliases = Array.from(new Set([...(old.aliases ?? []), ...(p.aliases ?? [])]))
        byName.set(p.name, { ...old, aliases })
      } else {
        byName.set(p.name, { name: p.name, kind: (p.kind as PlanSubject['kind']) ?? 'person', aliases: p.aliases ?? [], enabled: true, locked: false, refs: [], portraitAssetId: '', portraitVersion: 0 })
      }
    }
    setPlanSubjects([...byName.values()])
    message.success(r.added?.length ? `新增主体：${r.added.join('、')}（记得确认方案）` : '没有新主体（已有列表已是最新）')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '抽取主体失败')
  } finally {
    subjectBusy.value = ''
  }
}
/** 主体参与锚定开关 */
function toggleSubject(name: string, on: boolean): void {
  setPlanSubjects(planSubjects().map((s) => (s.name === name ? { ...s, enabled: on } : s)))
}
/** 生成该主体的定妆图（kind=portrait） */
async function genPortrait(name: string): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  portraitBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'portrait',
      subject: name,
    } as never)
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建定妆图任务')
    message.info(`「${name}」定妆图合成中…`)
    const job = await waitJobDone(jobId, 600000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    await refreshGallery()
    message.success(`「${name}」定妆图已生成 —— 点「选图」把它设为该主体的锚定图`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成定妆图失败')
  } finally {
    portraitBusy.value = false
  }
}
/** 选图：把最新一版定妆图写回该主体（供分镜锚定） */
function pickPortrait(name: string): void {
  const list = portraitsOf(name)
  if (!list.length) {
    message.warning(`「${name}」还没有定妆图，先点「生成图像」`)
    return
  }
  const newest = list[0]
  setPlanSubjects(planSubjects().map((s) => (s.name === name
    ? { ...s, portraitAssetId: newest.id, portraitVersion: (s.portraitVersion ?? 0) + 1 }
    : s)))
  message.success(`「${name}」已锚定最新定妆图（v${(planSubjects().find((x) => x.name === name)?.portraitVersion ?? 1)}）—— 记得确认方案后再生成分镜，否则读的是旧稿`)
}

function buildRefAssets(): Array<{
  assetId: string
  subject?: string
  region?: { x: number; y: number; w: number; h: number }
}> {
  return refSelected.value.map((id) => {
    const s = (refSubjects.value[id] ?? '').trim()
    const r = refRegions.value[id]
    const nums = r ? [r.x, r.y, r.w, r.h].map((v) => Number(String(v ?? '').trim())) : []
    const valid =
      !!r &&
      nums.length === 4 &&
      nums.every((n) => Number.isFinite(n) && n >= 0 && n <= 100) &&
      nums[2] > 0 &&
      nums[3] > 0 &&
      nums[0] + nums[2] <= 100 &&
      nums[1] + nums[3] <= 100
    const item: { assetId: string; subject?: string; region?: { x: number; y: number; w: number; h: number } } = {
      assetId: id,
    }
    if (s) item.subject = s
    if (valid) item.region = { x: nums[0] / 100, y: nums[1] / 100, w: nums[2] / 100, h: nums[3] / 100 }
    return item
  })
}

function syncReferenceAssets(): void {
  if (!draft.value) return
  const refs = buildRefAssets()
  ;(draft.value as unknown as { referenceAssets?: unknown }).referenceAssets = refs
  // P13：按主体聚合写回 subjects[]（勾选状态与定妆图一起落库）
  const prev = planSubjects()
  const byName = new Map(prev.map((s) => [s.name, s]))
  const grouped = new Map<string, PlanSubjectRef[]>()
  for (const r of refs) {
    const key = (r.subject ?? '').trim() || '（未命名）'
    grouped.set(key, [...(grouped.get(key) ?? []), { assetId: r.assetId, checked: !refUnchecked.value.includes(r.assetId), region: r.region ?? null }])
  }
  const out: PlanSubject[] = []
  for (const [name, refsOf] of grouped) {
    const old = byName.get(name)
    out.push({ name, kind: old?.kind ?? 'person', aliases: old?.aliases ?? [], enabled: old?.enabled ?? true, locked: old?.locked ?? false, refs: refsOf, portraitAssetId: old?.portraitAssetId ?? '', portraitVersion: old?.portraitVersion ?? 0 })
    byName.delete(name)
  }
  // 没有素材图但有定妆图/别名的主体也要保留（否则一键抽取的结果会丢）
  for (const s of byName.values()) out.push({ ...s, refs: [] })
  ;(draft.value as unknown as { subjects?: PlanSubject[] }).subjects = out
}
/** 勾选/取消某张参考图参与锚定 */
function toggleRefChecked(id: string, on: boolean): void {
  refUnchecked.value = on ? refUnchecked.value.filter((x) => x !== id) : [...new Set([...refUnchecked.value, id])]
  syncReferenceAssets()
}

function setRefRegion(id: string, k: 'x' | 'y' | 'w' | 'h', v: string): void {
  const cur = refRegions.value[id] ?? { x: '', y: '', w: '', h: '' }
  refRegions.value = { ...refRegions.value, [id]: { ...cur, [k]: v } }
  syncReferenceAssets()
}
function setRefSubject(id: string, v: string): void {
  refSubjects.value[id] = v
  syncReferenceAssets()
}
function onRefSubjectInput(id: string, e: Event): void {
  setRefSubject(id, (e.target as HTMLInputElement).value)
}


const POS_COLORS = ['#8FB9B4', '#C8A25E', '#C45C4A', '#7AA87A']
function parseRegionPct(r?: { x: string; y: string; w: string; h: string } | null) {
  if (!r) return null
  const n = [r.x, r.y, r.w, r.h].map((v) => Number(String(v ?? '').trim()))
  if (n.length !== 4 || n.some((v) => !Number.isFinite(v))) return null
  const [x, y, w, h] = n
  if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > 100.001 || y + h > 100.001) return null
  return { x, y, w, h }
}
function setRefRegionPct(id: string, region: { x: number; y: number; w: number; h: number }): void {
  const r = (v: number) => String(Math.round(Math.max(0, Math.min(100, v)) * 10) / 10)
  refRegions.value = { ...refRegions.value, [id]: { x: r(region.x), y: r(region.y), w: r(region.w), h: r(region.h) } }
  syncReferenceAssets()
}
/** 位置预览数据：主体名 + 区域 + 配色 */
const refPreviewItems = computed(() =>
  selectedRefAssets.value.map((a, i) => ({
    id: a.id,
    label: (refSubjects.value[a.id] ?? '').trim() || `主体${i + 1}`,
    region: parseRegionPct(refRegions.value[a.id]),
    color: POS_COLORS[i % POS_COLORS.length],
  })),
)
const posAspectCss = computed(() => {
  const ar = project.data.value?.aspectRatio ?? '16:9'
  const [w, h] = ar.split(':').map((n) => Number(n) || 0)
  return w > 0 && h > 0 ? `${w} / ${h}` : '16 / 9'
})
/** 多主体自动均分（2 → 左右；3 → 三等分；4 → 2×2），帮助用户快速得到合理相对位置 */
function autoLayoutRegions(): void {
  const items = selectedRefAssets.value
  const n = items.length
  if (n < 2) return
  const layouts: Array<{ x: number; y: number; w: number; h: number }> = []
  if (n === 2) {
    layouts.push({ x: 0, y: 0, w: 50, h: 100 }, { x: 50, y: 0, w: 50, h: 100 })
  } else if (n === 3) {
    layouts.push({ x: 0, y: 0, w: 34, h: 100 }, { x: 34, y: 0, w: 33, h: 100 }, { x: 67, y: 0, w: 33, h: 100 })
  } else {
    for (let i = 0; i < n; i++) {
      layouts.push({ x: (i % 2) * 50, y: Math.floor(i / 2) * 50, w: 50, h: 50 })
    }
  }
  items.forEach((a, i) => setRefRegionPct(a.id, layouts[i] ?? layouts[layouts.length - 1]))
  message.success('已按主体顺序自动均分区域，可拖动或微调')
}

// 位置预览拖动（移动 / 右下角缩放）
let posDragRect = { w: 1, h: 1 }
let posDrag: { id: string; mode: 'move' | 'resize'; sx: number; sy: number; orig: { x: number; y: number; w: number; h: number } } | null = null
function onBoxPointerDown(e: PointerEvent, item: { id: string; region: { x: number; y: number; w: number; h: number } | null }, mode: 'move' | 'resize'): void {
  e.preventDefault()
  e.stopPropagation()
  const frame = (e.currentTarget as HTMLElement).closest('.pos-frame') as HTMLElement | null
  if (!frame) return
  const r = frame.getBoundingClientRect()
  posDragRect = { w: r.width || 1, h: r.height || 1 }
  posDrag = { id: item.id, mode, sx: e.clientX, sy: e.clientY, orig: item.region ?? { x: 0, y: 0, w: 40, h: 50 } }
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}
function onBoxPointerMove(e: PointerEvent): void {
  if (!posDrag) return
  const dx = ((e.clientX - posDrag.sx) / posDragRect.w) * 100
  const dy = ((e.clientY - posDrag.sy) / posDragRect.h) * 100
  const o = posDrag.orig
  if (posDrag.mode === 'move') {
    setRefRegionPct(posDrag.id, {
      x: Math.min(Math.max(0, o.x + dx), 100 - o.w),
      y: Math.min(Math.max(0, o.y + dy), 100 - o.h),
      w: o.w,
      h: o.h,
    })
  } else {
    setRefRegionPct(posDrag.id, {
      x: o.x,
      y: o.y,
      w: Math.min(Math.max(5, o.w + dx), 100 - o.x),
      h: Math.min(Math.max(5, o.h + dy), 100 - o.y),
    })
  }
}
function onBoxPointerUp(): void {
  posDrag = null
}

/** 删除参考图（仅 reference 类可删；资产库产物请到资产库删除） */
async function removeRefAsset(id: string): Promise<void> {
  if (!window.confirm('删除这张参考图？不可恢复。')) return
  try {
    await deleteAssets(workspaceId.value, projectId.value, [id])
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]
    syncReferenceAssets()
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success('已删除参考图')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

/** 资产库：still/clip 显示第几镜（优先按 shotId 映射，其次 job payload.shot_no） */
const shotNoById = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const sh of detail.data.value?.shots ?? []) m[sh.id] = sh.shotNo
  return m
})
function galShotNo(a: AssetRef): number | null {
  if (a.shotNo != null) return a.shotNo
  if (a.shotId && shotNoById.value[a.shotId]) return shotNoById.value[a.shotId]
  const j = (jobs.data.value ?? []).find((x) => x.id === a.jobId)
  return j?.payload?.shot_no ?? null
}

/** P2：检测「机位/背影/过肩」类构图诉求 + 已选参考图 → 提示参考图可能拉走构图 */
const CAMERA_INTENT_RE = /(背影|背后|背面|过肩|机位|视角|俯视|仰视|穿过|透过|透视|behind|over[- ]the[- ]shoulder|from behind)/i
const cameraIntentWithRefs = computed(() => {
  if (!refSelected.value.length || !draft.value) return false
  let text = ''
  if (draft.value.mode === 'image') {
    const p = draft.value as ImagePlan
    text = `${p.positive_prompt ?? ''} ${p.prompt_zh ?? ''}`
  } else {
    const v = draft.value as VideoPlan
    for (const s of v.shots ?? []) {
      text += ` ${s.action ?? ''} ${s.positive_prompt ?? ''}`
      for (const kf of s.keyframes ?? []) text += ` ${kf.composition ?? ''} ${kf.positive_prompt ?? ''}`
    }
  }
  return CAMERA_INTENT_RE.test(text)
})
const uploadingRef = ref(false)
const thumbUrls = ref<Record<string, string>>({})

async function refreshThumbs(): Promise<void> {
  const picks = refLibrary.value
  const next: Record<string, string> = {}
  await Promise.all(
    picks.slice(0, 12).map(async (a) => {
      if (!thumbUrls.value[a.id]) {
        const blob = await fetchAssetBlob(workspaceId.value, a.id)
        if (blob) thumbUrls.value[a.id] = URL.createObjectURL(blob)
      }
      next[a.id] = thumbUrls.value[a.id]
    }),
  )
}
watch(() => [...refLibrary.value.map((a) => a.id)].join(','), () => { void refreshThumbs() }, { immediate: true })

/** P12：本方案最多可绑定的参考图张数（各模型对「一次能输入几张」另有各自上限，见引擎配置） */
const MAX_REFS = 15

/** 当前图片模型一次能收几张参考图（来自模型 schema 的 mapping.refsMax；未知则 0=不提示） */
const engineSettings = useQuery({
  queryKey: ['engine-settings'],
  queryFn: () => getEngineSettings(),
  enabled: computed(() => workspaceId.value !== ''),
})
const modelRefHint = computed(() => {
  const s = engineSettings.data.value
  const map = (s?.imageModelSchema?.mapping ?? {}) as Record<string, unknown>
  // 网关通道（方舟等）没有 schema → 用后端默认上限 15（超出会被裁剪，提示用户避免白烧费用）
  const gateway = !!(s?.imageCloudBaseUrl ?? '').trim()
  const max = Number(map.refsMax ?? 0) || (gateway ? Number(s?.gatewayRefsMax ?? 0) || 15 : 0)
  return { name: s?.imageCloudModel ?? '', max, isArray: map.refsIsArray === true }
})
const refOverModelLimit = computed(
  () => modelRefHint.value.max > 0 && refSelected.value.length > modelRefHint.value.max,
)

/** P12：项目详情各长列表默认只展示这么多行（分镜/镜头时长/音色绑定/配音/任务/时间线…） */
const LIST_PAGE = 5

/* ---------------- P12 任务 / 资产按类型分 Tab（避免一次刷一堆） ---------------- */
type AudioTab = 'master' | 'voice' | 'bgm' | 'still' | 'clip' | 'all'
/**
 * 任务卡片的 Tab：**不含成片 master**（任务区不会产出 master，成片是导出/合成的产物，只在资产库）。
 * “全部”放最后。
 */
const JOB_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  { key: 'voice', label: '配音', kind: 'voice', hint: '含试听产物 voice_preview' },
  { key: 'bgm', label: '配乐', kind: 'bgm', hint: '含试听产物 bgm_preview' },
  { key: 'still', label: '关键帧', kind: 'still', hint: '首帧图片 still' },
  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'all', label: '全部', hint: '全部任务（项多，缩略图按需懒加载）' },
]
/** 资产库 Tab：保留成片 master（导出/合成产物在这里） */
const GAL_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  { key: 'master', label: '成片', kind: 'master', hint: '导出/合成出的成片 master' },
  { key: 'voice', label: '配音', kind: 'voice', hint: '含试听产物 voice_preview' },
  { key: 'bgm', label: '配乐', kind: 'bgm', hint: '含试听产物 bgm_preview' },
  { key: 'still', label: '关键帧', kind: 'still', hint: '首帧图片 still' },
  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'all', label: '全部', hint: '全部产物（项多，缩略图按需懒加载）' },
]
/** 把 kind 归到 Tab（试听产物归入对应正式类型） */
function kindTab(kind: string): AudioTab {
  if (kind === 'voice' || kind === 'voice_preview') return 'voice'
  if (kind === 'bgm' || kind === 'bgm_preview') return 'bgm'
  if (kind === 'still') return 'still'
  if (kind === 'clip') return 'clip'
  if (kind === 'master') return 'master'
  return 'all'
}

/* ---------------- P12：Tab 选择记忆 + 默认停在「最近有更新」的一类 ---------------- */
const JOB_TAB_KEY = 'weaveora.jobTab'
const GAL_TAB_KEY = 'weaveora.galTab'

function savedTab(key: string, tabs: Array<{ key: AudioTab }>): AudioTab | null {
  try {
    const v = localStorage.getItem(key)
    return v && tabs.some((t) => t.key === v) ? (v as AudioTab) : null
  } catch {
    return null
  }
}
function rememberTab(key: string, t: AudioTab): void {
  try {
    localStorage.setItem(key, t)
  } catch {
    // 隐私模式等写不了就算了，不影响功能
  }
}

/** 上次点过的 Tab（没有则 null）；用 pinned 区分「用户/数据定过」与「还没定」 */
const jobTabPinned = ref(savedTab(JOB_TAB_KEY, JOB_TABS) != null)
const galTabPinned = ref(savedTab(GAL_TAB_KEY, GAL_TABS) != null)
const jobTab = ref<AudioTab>(savedTab(JOB_TAB_KEY, JOB_TABS) ?? 'still')
const galTab = ref<AudioTab>(savedTab(GAL_TAB_KEY, GAL_TABS) ?? 'master')

/** 点 Tab：切过去（列表只渲染当前 Tab 的项），并记住选择 */
function pickJobTab(t: AudioTab): void {
  jobTab.value = t
  jobTabPinned.value = true
  jobLimit.value = LIST_PAGE
  rememberTab(JOB_TAB_KEY, t)
}
function pickGalTab(t: AudioTab): void {
  galTab.value = t
  galTabPinned.value = true
  rememberTab(GAL_TAB_KEY, t)
}
/** 生成任务时自动切到对应 Tab，否则用户看不到刚发起任务的进度 */
function focusJobTab(t: AudioTab): void {
  pickJobTab(t)
}

function newestStamp<T extends { createdAt: string }>(list: T[]): T | undefined {
  let best: T | undefined
  for (const x of list) {
    if (!best || new Date(x.createdAt).getTime() > new Date(best.createdAt).getTime()) best = x
  }
  return best
}

// ---------- W4 资产库 ----------
const outputAssets = computed(() => (assets.data.value ?? []).filter((a) => ['still','clip','master','voice','bgm','voice_preview','bgm_preview'].includes(a.kind)))

/** P12：资产库也按类型分 Tab */
const galTabCounts = computed(() => {
  const m: Record<string, number> = { all: 0 }
  for (const a of outputAssets.value) {
    m.all++
    const t = kindTab(a.kind)
    m[t] = (m[t] ?? 0) + 1
  }
  return m
})
const galleryForTab = computed(() =>
  galTab.value === 'all' ? outputAssets.value : outputAssets.value.filter((a) => kindTab(a.kind) === galTab.value),
)
// 默认 Tab：没记录过就用「最新一条产物」那一类
watch(outputAssets, (list) => {
  if (galTabPinned.value) return
  const newest = newestStamp(list as unknown as Array<{ createdAt: string }>)
  if (!newest) return
  galTab.value = kindTab((newest as unknown as { kind: string }).kind)
  galTabPinned.value = true
})
const galUrls = ref<Record<string, string>>({})
async function refreshGallery(): Promise<void> {
  await Promise.all(outputAssets.value.map(async (a) => {
    if (!galUrls.value[a.id]) {
      const blob = await fetchAssetBlob(workspaceId.value, a.id)
      if (blob) galUrls.value[a.id] = URL.createObjectURL(blob)
    }
  }))
}
watch(() => outputAssets.value.map((a) => a.id).join(','), () => { void refreshGallery() }, { immediate: true })

// ---------- 资产库：管理态（勾选批量删除 / 单个删除） ----------
const galManage = ref(false)
const galSel = ref<string[]>([])
const galBusy = ref(false)
const galAllSelected = computed(
  () => outputAssets.value.length > 0 && galSel.value.length === outputAssets.value.length)
function toggleGalManage(): void {
  galManage.value = !galManage.value
  galSel.value = []
}
function toggleGalSel(id: string): void {
  galSel.value = galSel.value.includes(id)
    ? galSel.value.filter((x) => x !== id)
    : [...galSel.value, id]
}
function toggleGalAll(): void {
  galSel.value = galAllSelected.value ? [] : outputAssets.value.map((a) => a.id)
}
async function removeAssets(ids: string[]): Promise<void> {
  if (!ids.length) return
  if (!window.confirm(`删除 ${ids.length} 个产物/资产？不可恢复（相关任务记录仍保留）。`)) return
  galBusy.value = true
  try {
    const r = await deleteAssets(workspaceId.value, projectId.value, ids)
    ids.forEach((id) => {
      if (galUrls.value[id]) {
        URL.revokeObjectURL(galUrls.value[id])
        delete galUrls.value[id]
      }
    })
    galSel.value = []
    if (outputAssets.value.length - r.deleted <= 0) galManage.value = false
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success(`已删除 ${r.deleted} 个产物`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    galBusy.value = false
  }
}
function removeGalSelected(): void {
  void removeAssets([...galSel.value])
}
function removeAssetOne(id: string): void {
  void removeAssets([id])
}

/** 资产库管理态：分享选中素材（仅将所选上架集市；待审） */
async function shareSelectedAssets(): Promise<void> {
  if (!galSel.value.length) return
  if (!window.confirm(`将所选 ${galSel.value.length} 个素材提交到集市（等待管理员审批）？集市仅展示这些素材，只读浏览。`)) return
  try {
    await shareProject(workspaceId.value, projectId.value, [...galSel.value])
    message.success('已提交集市（仅所选素材），等待管理员审批')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '分享失败')
  }
}

function firstFrame(e: Event): void {
  const v = e.target as HTMLVideoElement
  if (v.readyState >= 2) {
    v.currentTime = 0.05
  } else {
    v.addEventListener('loadedmetadata', () => {
      v.currentTime = 0.05
    }, { once: true })
  }
}

// 沉浸式预览（大图/大视频）
const immersive = ref<{ url: string; mime: string } | null>(null)
function openImmersive(id: string, mime: string): void {
  const url = galUrls.value[id]
  if (!url) return
  immersive.value = { url, mime }
}
function setRefFromAsset(id: string): void {
  if (refSelected.value.includes(id)) return
  if (refSelected.value.length >= MAX_REFS) {
    message.warning(`参考图最多 ${MAX_REFS} 张`)
    return
  }
  refSelected.value.push(id)
  syncReferenceAssets()
  message.success('已加入参考图（显示在左侧参考图卡片，可标主体/区域）')
}

function onPickFile(e: Event): void {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  void (async () => {
    uploadingRef.value = true
    try {
      const a = await uploadReference(workspaceId.value, projectId.value, file)
      await queryClient.invalidateQueries({ queryKey: ['assets'] })
      if (refSelected.value.length >= MAX_REFS) {
        message.warning(`参考图最多 ${MAX_REFS} 张（已加入的不受影响）`)
      }
      else refSelected.value.push(a.id)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '上传失败')
    } finally {
      uploadingRef.value = false
      input.value = ''
    }
  })()
}
function pruneUnchecked(): void {
  refUnchecked.value = refUnchecked.value.filter((x) => refSelected.value.includes(x))
}
function toggleRef(id: string, on: boolean): void {
  if (on) {
    if (refSelected.value.length >= MAX_REFS) {
      message.warning(`参考图最多 ${MAX_REFS} 张`)
      return
    }
    if (!refSelected.value.includes(id)) refSelected.value.push(id)
  } else {
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]
    pruneUnchecked()
  }
  syncReferenceAssets()
}

/* ---------------- P12：生成前弹分镜勾选（>3 镜时） ---------------- */
const pickOpen = ref(false)
const pickBusy = ref(false)
const pickCtx = ref<{ title: string; run: (shotNos: number[] | null) => Promise<void> | void } | null>(null)

/**
 * 分镜多于 3 镜 → 先弹勾选窗（选部分镜还是全部，顺便可封版）；
 * ≤ 3 镜直接跑（不传 shotNos = 全部，后端会自动跳过已封版镜）。
 */
function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
): void {
  if (pickerShots.value.length <= 3) {
    void run(null)
    return
  }
  pickCtx.value = { title, run }
  pickOpen.value = true
}

/** 弹窗确认：先落封版变更，再按勾选的镜头跑 */
async function onShotPicked(p: { shotNos: number[]; lock: number[]; unlock: number[] }): Promise<void> {
  const ctx = pickCtx.value
  pickCtx.value = null
  if (!ctx) return
  pickBusy.value = true
  try {
    if (p.lock.length) {
      await setShotLocks(workspaceId.value, projectId.value, p.lock, true)
      message.success(`已封版 ${p.lock.length} 镜（第 ${p.lock.join('、')} 镜）—— 之后批量生成会自动跳过`)
    }
    if (p.unlock.length) {
      await setShotLocks(workspaceId.value, projectId.value, p.unlock, false)
      message.info(`已取消封版 ${p.unlock.length} 镜（第 ${p.unlock.join('、')} 镜）`)
    }
    if (p.lock.length || p.unlock.length) {
      await queryClient.invalidateQueries({ queryKey: ['shot-locks'] })
    }
    await ctx.run(p.shotNos)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    pickBusy.value = false
  }
}

/** 单独给某镜切换封版（分镜卡片上的小锁） */
async function toggleShotLock(shotNo: number, locked: boolean): Promise<void> {
  try {
    await setShotLocks(workspaceId.value, projectId.value, [shotNo], locked)
    await queryClient.invalidateQueries({ queryKey: ['shot-locks'] })
    if (locked) message.success(`第 ${shotNo} 镜已封版（批量生成会跳过它）`)
    else message.info(`第 ${shotNo} 镜已取消封版`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '封版设置失败')
  }
}

// ---------- W3 任务 ----------
const jobs = useQuery({
  queryKey: computed(() => ['jobs', workspaceId.value, projectId.value]),
  queryFn: () => listJobs(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})

// ---------- P12 分镜封版 ----------
const shotLocks = useQuery({
  queryKey: computed(() => ['shot-locks', workspaceId.value, projectId.value]),
  queryFn: () => listShotLocks(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})
const lockedShots = computed<number[]>(() => shotLocks.data.value ?? [])

/** 弹窗要展示的分镜列表：镜号 + 该镜资源版本（取最新一条 still/voice 产物所属版本）+ 语音段数 */
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
})
const activeJobCount = computed(() => (jobs.data.value ?? []).filter((j) =>
  ['queued', 'running'].includes(j.state)).length)

// 轮询只针对“新鲜”任务（创建 25 分钟内）；超时的 queued/running 视为卡死，不再刷接口
const freshActiveCount = computed(() => {
  const now = Date.now()
  return (jobs.data.value ?? []).filter((j) => {
    if (!['queued', 'running'].includes(j.state)) return false
    const age = now - new Date(j.createdAt).getTime()
    return age < 25 * 60 * 1000
  }).length
})

// 有进行中任务时 3s 轮询（避免查询配置自引用）
let jobsTimer: ReturnType<typeof setInterval> | undefined
watch(
  freshActiveCount,
  (n) => {
    if (n > 0 && !jobsTimer) {
      jobsTimer = setInterval(() => {
        void jobs.refetch()
      }, 5000)
    } else if (n === 0 && jobsTimer) {
      clearInterval(jobsTimer)
      jobsTimer = undefined
      // 任务全部结束后资产已落库 → 自动刷新资产库预览
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    }
  },
  { immediate: true },
)
const imgCount = ref(1)
const genBusy = ref(false)
const cancelBusy = ref<string | null>(null)

// P12：长列表默认只展示 5 行，点「查看更多（余 N 条）」逐次再展开 5 条
const jobLimit = ref(LIST_PAGE)
const filterLatest = ref(true)
/**
 * 一个任务对应的「产物位」：**同一位同类型的重复生成才互相覆盖**。
 *  - still → 按关键帧序号（一镜多帧各自一行）
 *  - clip  → 每镜一个
 *  - voice → **按段号**（一镜多段配音各自一行，原来按“镜”去重 → 只看到最后一段）
 *  - bgm   → 按情绪（不同情绪各一个产物）
 */
function jobSlotKey(j: JobRecord): string {
  const p = j.payload ?? {}
  const shot = p.shot_no ?? 'x'
  switch (j.kind) {
    case 'still':
      return `shot:${shot}:still:${p.keyframe_index ?? ''}`
    case 'clip':
      return `shot:${shot}:clip`
    case 'voice':
      return `shot:${shot}:voice:${p.line_index ?? ''}`
    case 'bgm':
      return `bgm:${p.mood ?? ''}`
    default:
      return `shot:${shot}:${j.kind}`
  }
}

/**
 * 「只看最近一轮」= 每个产物位 × **每种状态** 各保留最新一条：
 *  - 多段配音/多帧关键帧各自成行（不再互相覆盖）
 *  - 失败/取消的不会被后来成功的同一位任务顶掉（否则没法「重试选中」）
 */
const latestJobs = computed(() => {
  const all = jobs.data.value ?? []
  if (!filterLatest.value) return all
  const newest = new Map<string, JobRecord>()
  for (const j of all) {
    const key = `${jobSlotKey(j)}|${j.state}`
    const cur = newest.get(key)
    // 取 createdAt 最大者（与列表返回顺序无关，防“留下最旧一条”导致全是 v1 旧任务）
    if (!cur || new Date(j.createdAt).getTime() > new Date(cur.createdAt).getTime()) {
      newest.set(key, j)
    }
  }
  return [...newest.values()].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  )
})
const visibleJobs = computed(() => jobsForTab.value.slice(0, jobLimit.value))
function showMoreJobs(): void {
  jobLimit.value += LIST_PAGE
}

/* ---------------- P12：任务列表按类型分 Tab（定义在上文 W4 之前，这里只用） ---------------- */
const jobTabCounts = computed(() => {
  const m: Record<string, number> = { all: 0 }
  for (const j of latestJobs.value) {
    m.all++
    const t = kindTab(j.kind)
    m[t] = (m[t] ?? 0) + 1
  }
  return m
})
const jobsForTab = computed(() =>
  jobTab.value === 'all' ? latestJobs.value : latestJobs.value.filter((j) => kindTab(j.kind) === jobTab.value),
)

// 默认 Tab：没记录过就用「最新一条任务」那一类（最近有更新的一类）
watch(
  () => latestJobs.value,
  (list) => {
    if (jobTabPinned.value) return
    const newest = newestStamp(list)
    if (!newest) return
    jobTab.value = kindTab(newest.kind)
    jobTabPinned.value = true
  },
)

async function startGeneration(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  // P12：生成后自动切到对应 Tab（顺手解锁），否则用户看不到刚发起任务的进度
  focusJobTab('still')
  // P4：先把当前草稿（含参考图/主体标注/提示词改动）落库，再发起生成
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const isVideo = draft.value?.mode === 'video'
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'still',
      count: isVideo ? undefined : imgCount.value,
      ...(shotNos && shotNos.length ? { shotNos } : {}),
    })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已创建 ${created.length} 个任务（关键帧 · 基于确认稿 v${approvedRev.value?.revisionNo ?? '?'}）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建任务失败')
  } finally {
    genBusy.value = false
  }
}
async function cancelOne(jobId: string): Promise<void> {
  cancelBusy.value = jobId
  try {
    await cancelJob(workspaceId.value, jobId)
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
  } catch (e) {
    message.error(e instanceof Error ? e.message : '取消失败')
  } finally {
    cancelBusy.value = null
  }
}

const rerunBusy = ref<string | null>(null)
async function rerunJobOne(jobId: string): Promise<void> {
  rerunBusy.value = jobId
  try {
    const neu = await rerunJob(workspaceId.value, jobId)
    void queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已重新生成并加入队列（${neu.kind}）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重生成失败')
  } finally {
    rerunBusy.value = null
  }
}

// ---------- 失败/取消任务：勾选批量/单个 重试 或 删除 ----------
const eligibleJobs = computed(() => jobsForTab.value.filter(
  (j) => j.state === 'failed' || j.state === 'cancelled'))
const jobSel = ref<string[]>([])
const jobActionBusy = ref(false)

function isJobActionable(j: JobRecord): boolean {
  return j.state === 'failed' || j.state === 'cancelled'
}

// ---------- 任务版本标注（防“旧版产物误导新版”操作）：任务按生成时的确认稿版本打标 ----------
const revisionById = computed<Record<string, { no: number; approved: boolean }>>(() => {
  const m: Record<string, { no: number; approved: boolean }> = {}
  for (const r of revisions.data.value ?? []) m[r.id] = { no: r.revisionNo, approved: r.approved }
  return m
})
const approvedRev = computed(() => (revisions.data.value ?? []).find((r) => r.approved) ?? null)
/** 生成/运动始终以“当前确认稿”为准（服务端同样只认确认稿）；避免用户对着旧 tab/旧任务误生成 */
function genRevisionId(): string | null {
  return approvedRev.value?.id ?? selectedRevId.value
}
function revOfJob(j: JobRecord): { no: number; stale: boolean } | null {
  if (!j.revisionId) return null
  const r = revisionById.value[j.revisionId]
  if (!r) return null
  return { no: r.no, stale: approvedRev.value !== null && approvedRev.value.id !== j.revisionId }
}
/** 任务审计（P3）：悬停可查“用 vN 的哪句话 + prompt_md5” */
function jobAuditTitle(j: JobRecord): string | undefined {
  const p = j.payload
  if (!p) return undefined
  // P12：配音任务把该段文本也带进悬停提示，方便区分一镜多段/多次重跑
  const lineInfo = j.kind === 'voice' && p.text
    ? `\n台词（第${(p.line_index ?? 0) + 1}段${p.subject ? ' · ' + p.subject : ''}）：${p.text}`
    : ''
  if (!p.positive_prompt) return lineInfo ? lineInfo.trim() : undefined
  const r = revOfJob(j)
  const no = p.revision_no ?? r?.no ?? '?'
  const prompt = p.positive_prompt.length > 120 ? `${p.positive_prompt.slice(0, 120)}…` : p.positive_prompt
  const hist = p.keyframeHistorical
    ? `\n关键帧：沿用历史版本第${p.shot_no ?? '?'}镜的关键帧${p.keyframeHistoricalRevisionNo ? `（v${p.keyframeHistoricalRevisionNo}）` : ''}`
    : ''
  return `版本 v${no}${r?.stale ? '（旧版）' : ''} · md5 ${(p.prompt_md5 ?? '-').slice(0, 16)}${hist}\n提示词：${prompt}${lineInfo}`
}
/** 资产库：由产物 jobId 反查生成版本（vN），便于区分旧版产物 */
function galRevNo(jobId: string | null): number | null {
  if (!jobId) return null
  const j = (jobs.data.value ?? []).find((x) => x.id === jobId)
  return j ? revOfJob(j)?.no ?? null : null
}
const eligibleAllSelected = computed(
  () => eligibleJobs.value.length > 0 && jobSel.value.length === eligibleJobs.value.length)
function toggleEligibleAll(): void {
  jobSel.value = eligibleAllSelected.value ? [] : eligibleJobs.value.map((j) => j.id)
}
function toggleJobSel(id: string): void {
  jobSel.value = jobSel.value.includes(id)
    ? jobSel.value.filter((x) => x !== id)
    : [...jobSel.value, id]
}

async function retryJobsSel(): Promise<void> {
  const ids = [...jobSel.value]
  if (!ids.length) return
  jobActionBusy.value = true
  try {
    const created = await retryJobs(workspaceId.value, projectId.value, ids)
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    jobSel.value = []
    message.success(
      `已重试 ${created.length} 条：若确认稿已更新，将按当前确认稿 v${approvedRev.value?.revisionNo ?? '?'} 重新取词（旧失败记录保留可再删）`,
    )
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重试失败')
  } finally {
    jobActionBusy.value = false
  }
}
async function deleteJobsSel(): Promise<void> {
  const ids = [...jobSel.value]
  if (!ids.length) return
  if (!window.confirm(`删除所选 ${ids.length} 条失败/已取消记录？不可恢复。`)) return
  jobActionBusy.value = true
  try {
    const r = await deleteJobs(workspaceId.value, projectId.value, ids)
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    await jobs.refetch()
    jobSel.value = []
    if (r.deleted > 0) {
      message.success(`已删除 ${r.deleted} 条记录`)
    } else {
      // 只有失败/已取消可删；同一位若有更旧的失败记录，删掉最新那条后列表会顶上来 → 看着像“没反应”
      message.warning('没有记录被删除：该任务可能已被删除，或状态已不是「失败/已取消」；同一位若有更早的失败记录，会继续显示')
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    jobActionBusy.value = false
  }
}
function retryJobOne(jobId: string): void {
  jobSel.value = [jobId]
  void retryJobsSel()
}
function deleteJobOne(jobId: string): void {
  jobSel.value = [jobId]
  void deleteJobsSel()
}
// motion 帧数（系统范围 32–96）
const MOTION_MIN = 32
const MOTION_MAX = 96
const motionOpen = ref(false)
const motionFrames = ref(48)
function openMotionModal(): void {
  motionFrames.value = 48
  motionOpen.value = true
}
function confirmMotion(): void {
  focusJobTab('clip')
  const f = Number(motionFrames.value)
  if (!Number.isInteger(f) || f < MOTION_MIN || f > MOTION_MAX) {
    message.warning(`运动帧数需在 ${MOTION_MIN}–${MOTION_MAX} 之间`)
    return
  }
  motionOpen.value = false
  withShotPicker('运动(motion)', (shotNos) => startMotion(f, shotNos))
}
const KIND_LABEL: Record<string, string> = { still: '关键帧', clip: '运动', voice: '配音', bgm: '配乐' }

/** P7：逐镜配音（自托管 CosyVoice） */
async function startVoice(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  focusJobTab('voice')
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'voice',
      ...(shotNos && shotNos.length ? { shotNos } : {}),
    })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已创建 ${created.length} 个配音任务（逐镜旁白）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建配音任务失败')
  } finally {
    genBusy.value = false
  }
}

/** P7.1 配音试听：为某镜（默认第一个有旁白的镜）创建一个 voice 任务，完成后本地播放 */
const previewBusy = ref(false)
/**
 * 试听播放器状态。`slot` 决定它显示在哪块：
 *  - 'voice' → ① 配音音色（音色试听，就在按钮下一行）
 *  - 'line'  → ③ 配音（试听本镜 / 单条）
 *  - 'music' → ④ 配乐（试听配乐，就在按钮下一行）
 */
const audioPreview = ref<{
  url: string
  label: string
  kind: 'voice' | 'bgm'
  slot: 'voice' | 'line' | 'music'
} | null>(null)
const dialog = useDialog()

function closeAudioPreview(): void {
  if (audioPreview.value) URL.revokeObjectURL(audioPreview.value.url)
  audioPreview.value = null
}

async function waitJobDone(jobId: string, timeoutMs: number): Promise<JobRecord> {
  const t0 = Date.now()
  for (;;) {
    const list = await listJobs(workspaceId.value, projectId.value)
    const j = list.find((x) => x.id === jobId)
    if (j && ['succeeded', 'failed', 'cancelled'].includes(j.state)) return j
    if (Date.now() - t0 > timeoutMs) throw new Error('试听超时（首次加载模型可能较久，稍后重试）')
    await new Promise((r) => setTimeout(r, 4000))
  }
}

/**
 * P12 音色试听：直接播放「下拉里选中的那个音色」自己的样本。
 *  - 克隆音色 → 克隆时录入的那段音频（后端直接回，零延迟）
 *  - 内置音色 → 「你好，欢迎试音」模板（首次现合成并缓存，之后秒回）
 */
async function auditionVoice(): Promise<void> {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) {
    message.warning('当前不是视频方案，无法试听音色')
    return
  }
  const voice = (plan.audio.voice || '').trim() || '中文女'
  const isClone = voice.startsWith('clone:')
  const preset = isClone ? (plan.audio.voicePresets ?? []).find((p) => `clone:${p.id}` === voice) : undefined
  if (isClone && !preset?.assetId) {
    message.warning('这个克隆音色在方案里找不到对应的音频资产（可能已被删除），请重新选择或重新克隆')
    return
  }
  previewBusy.value = true
  try {
    const r = await auditionVoicePreset(
      workspaceId.value,
      projectId.value,
      isClone ? '' : voice,
      preset?.assetId ?? null,
    )
    const blob = await fetchAssetBlob(workspaceId.value, r.assetId)
    if (!blob) throw new Error('试听音频读取失败')
    const name = preset?.name || voice
    const dur = r.durationMs ? ` · ${(r.durationMs / 1000).toFixed(1)}s` : ''
    closeAudioPreview()
    audioPreview.value = {
      url: URL.createObjectURL(blob),
      kind: 'voice',
      slot: 'voice',
      label: `音色试听 · ${name}${r.fromSample ? '（克隆样本）' : ''}${dur}`,
    }
    if (r.fromSample) {
      message.success('音色试听就绪（克隆时录入的样本）')
    } else if (r.cached) {
      message.success('音色试听就绪（模板已缓存）')
    } else {
      message.success(`已用「${name}」合成模板「你好，欢迎试音」（已缓存，下次秒开）`)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '音色试听失败')
  } finally {
    previewBusy.value = false
  }
}

async function previewVoice(shotNo?: number): Promise<void> {
  // P12：不带镜号 = 「音色试听」→ 直接听该音色自己的样本（克隆→录入音频；内置→缓存模板）
  if (shotNo == null) {
    return auditionVoice()
  }
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  const plan = draft.value
  const shots = plan && isVideoPlan(plan) ? plan.shots : []
  if (!shots.length) {
    message.warning('当前不是视频方案，无法试听配音')
    return
  }
  const target = shotNo != null
    ? shots.find((x) => x.shot_no === shotNo)
    : shots.find((x) => shotHasText(x))
  if (!target) {
    message.warning('没有可试听的旁白/台词：请先在分镜里填写旁白（narration 或 narrations）')
    return
  }
  if (!shotHasText(target)) {
    message.warning(`第 ${target.shot_no} 镜没有旁白/台词，无法试听`)
    return
  }
  const rec = (detail.data.value?.shots ?? []).find((r) => r.shotNo === target.shot_no)
  previewBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'voice',
      preview: true,
      ...(rec ? { shotId: rec.id } : {}),
    })
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建试听任务')
    message.info(`第 ${target.shot_no} 镜配音合成中…（首次会加载模型）`)
    const job = await waitJobDone(jobId, 300000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `试听任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    const asset = (assets.data.value ?? []).find((a) => a.jobId === jobId)
    if (!asset) throw new Error('未找到试听音频')
    const blob = await fetchAssetBlob(workspaceId.value, asset.id)
    if (!blob) throw new Error('试听音频读取失败')
    closeAudioPreview()
    audioPreview.value = {
      url: URL.createObjectURL(blob),
      kind: 'voice',
      slot: 'line',
      label: `配音 · 第 ${target.shot_no} 镜 · 音色 ${(draft.value && isVideoPlan(draft.value) ? draft.value.audio.voice : '') || '中文女'}`,
    }
    message.success('试听就绪')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '试听失败')
  } finally {
    previewBusy.value = false
  }
}

/**
 * P8：单条重新生成配音 —— 只给「该镜第 lineIndex 段」建 voice 任务，
 * 不去任务区点「生成配音」（那会把全片所有段落都重建一遍）。
 */
async function genVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return     // 先落盘，否则服务端拿到的是旧段落
  const rec = (detail.data.value?.shots ?? []).find((r) => r.shotNo === shotNo)
  previewBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'voice',
      lineIndex,
      ...(rec ? { shotId: rec.id } : {}),
    })
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建配音任务')
    message.info(`第 ${shotNo} 镜第 ${lineIndex + 1} 段合成中…（首次会加载模型）`)
    const job = await waitJobDone(jobId, 300000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    await autoLayoutAfterGen(shotNo)
    message.success(`第 ${shotNo} 镜第 ${lineIndex + 1} 段配音已更新（渲染时会用最新一条）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重新生成失败')
  } finally {
    previewBusy.value = false
  }
}

/** P8：试听单条 —— 按该条现有的音色/文本起一个 preview 任务，完成后本地播放 */
async function previewVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  const rec = (detail.data.value?.shots ?? []).find((r) => r.shotNo === shotNo)
  previewBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'voice',
      lineIndex,
      preview: true,
      ...(rec ? { shotId: rec.id } : {}),
    })
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建试听任务')
    message.info(`第 ${shotNo} 镜第 ${lineIndex + 1} 段试听合成中…`)
    const job = await waitJobDone(jobId, 300000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `试听任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    const asset = (assets.data.value ?? []).find((a) => a.jobId === jobId)
    if (!asset) throw new Error('未找到试听音频')
    const blob = await fetchAssetBlob(workspaceId.value, asset.id)
    if (!blob) throw new Error('试听音频读取失败')
    closeAudioPreview()
    const p = draft.value
    const voice = p && isVideoPlan(p) ? p.audio.voice || '中文女' : '中文女'
    audioPreview.value = {
      url: URL.createObjectURL(blob),
      kind: 'voice',
      slot: 'line',
      label: `配音 · 第 ${shotNo} 镜 · 第 ${lineIndex + 1} 段 · 音色 ${voice}`,
    }
    message.success('试听就绪')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '试听失败')
  } finally {
    previewBusy.value = false
  }
}

/** P8：导入自己配好的声音（覆写该段配音资产） */
async function importVoiceLine(
  shotNo: number,
  lineIndex: number,
  file: File,
  atSec: number,
  subject: string,
): Promise<void> {
  if (dirty.value && !(await handleSave())) return
  previewBusy.value = true
  try {
    await uploadVoiceLine(workspaceId.value, projectId.value, { file, shotNo, lineIndex, atSec, subject })
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success(`已导入第 ${shotNo} 镜第 ${lineIndex + 1} 段的配音（渲染时优先用最新一条）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '导入配音失败')
  } finally {
    previewBusy.value = false
  }
}

/** P7.2 配乐试听：按当前 music_mood 生成一条试听（换情绪后重生成） */
async function previewBgm(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  previewBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'bgm',
      preview: true,
    })
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建试听任务')
    const mood = draft.value && isVideoPlan(draft.value) ? draft.value.audio.music_mood : ''
    message.info(`配乐生成中…（情绪：${mood || '默认'}；首次会加载模型）`)
    const job = await waitJobDone(jobId, 600000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `试听任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    const asset = (assets.data.value ?? []).find((a) => a.jobId === jobId)
    if (!asset) throw new Error('未找到试听音频')
    const blob = await fetchAssetBlob(workspaceId.value, asset.id)
    if (!blob) throw new Error('试听音频读取失败')
    closeAudioPreview()
    audioPreview.value = {
      url: URL.createObjectURL(blob),
      kind: 'bgm',
      slot: 'music',
      label: `配乐 · 情绪 ${mood || '默认'}`,
    }
    message.success('配乐试听就绪')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '配乐试听失败')
  } finally {
    previewBusy.value = false
  }
}

/** P7：整片配乐（自托管音乐生成） */
async function startBgm(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  focusJobTab('bgm')
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, { revisionId: revId, kind: 'bgm' })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已创建 ${created.length} 个配乐任务（按 music_mood）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建配乐任务失败')
  } finally {
    genBusy.value = false
  }
}

async function startMotion(frames?: number, shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'clip',
      ...(frames ? { frames } : {}),
      ...(shotNos && shotNos.length ? { shotNos } : {}),
    })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已创建 ${created.length} 个运动任务（关键帧→motion · 基于确认稿 v${approvedRev.value?.revisionNo ?? '?'}）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建运动任务失败')
  } finally {
    genBusy.value = false
  }
}

const motionReady = computed(() =>
  detApproved.value &&
  !!isVideoNow.value &&
  (jobs.data.value ?? []).some((j) => j.kind === 'still' && j.state === 'succeeded'),
)

// ---------- W6 成片 / 时间线导出 ----------
const exportBusy = ref(false)
const exportRows = computed(() => {
  const recs = detail.data.value?.shots ?? []
  const as = assets.data.value ?? []
  let cursor = 0
  const planShots = (draft.value && isVideoPlan(draft.value)) ? draft.value.shots : []
  return recs.map((rec, i) => {
    const dur = Number(planShots[i]?.duration_sec ?? 3)
    const row = { rec, start: cursor, dur, hasMedia: as.some((a) => a.shotId === rec.id || (a.shotNo != null && a.shotNo === rec.shotNo)) }
    cursor += dur
    return row
  })
})
const exportTotalSec = computed(() => {
  const last = exportRows.value[exportRows.value.length - 1]
  return last ? last.start + last.dur : 0
})
const exportTotal = computed(() => timecode(exportTotalSec.value))
// 成片时间线同样默认 5 条 + 查看更多
const tlLimit = ref(LIST_PAGE)
const visibleExportRows = computed(() => exportRows.value.slice(0, tlLimit.value))
function showMoreTl(): void {
  tlLimit.value += LIST_PAGE
}
async function doExport(): Promise<void> {
  if (!selectedRevId.value) return
  exportBusy.value = true
  try {
    const info = await createExport(workspaceId.value, projectId.value, selectedRevId.value)
    const blob = await fetchExportBlob(workspaceId.value, info.id)
    if (!blob) throw new Error('下载失败')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `weaveora-export-${info.id.slice(0, 8)}.zip`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(a.href)
    message.success('已生成成片包（edit_list.json + 素材）')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exportBusy.value = false
  }
}
const renderBusy = ref(false)
async function doRender(): Promise<void> {
  if (!selectedRevId.value) return
  focusJobTab('master')
  renderBusy.value = true
  try {
    const a = await renderMaster(workspaceId.value, projectId.value, selectedRevId.value, 'fade')
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success('已渲染成片（master mp4，带淡入淡出与音轨），可在资产库播放/下载')
    void a
  } catch (e) {
    message.error(e instanceof Error ? e.message : '渲染失败')
  } finally {
    renderBusy.value = false
  }
}

/** 首次：写 Brief 并立即导演 */
async function handleFirstBrief(payload: { rawText: string; dirMode: 'image' | 'video' }): Promise<void> {
  creating.value = true
  try {
    // P4：参考图选择/主体标注一并写入 brief.constraints（未出方案前也能绑定与告知导演）
    const refAssets = buildRefAssets()
    const b = await createBrief(workspaceId.value, projectId.value, {
      rawText: payload.rawText,
      mode: payload.dirMode,
      ...(refSelected.value.length
        ? { referenceAssetIds: [...refSelected.value], constraints: { referenceAssets: refAssets } }
        : {}),
    })
    await doGenerate(b.id, payload.dirMode)
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'Brief 保存失败')
  } finally {
    creating.value = false
  }
}

/** 换一段需求：新建 Brief 后导演（成功关闭编辑态） */
async function handleNewBrief(payload: { rawText: string; dirMode: 'image' | 'video' }): Promise<void> {
  await handleFirstBrief(payload)
  briefEditing.value = false
}

const aiShot = ref<DirectorShot | null>(null)
const aiOpen = ref(false)
const aiBusy = ref(false)
const aiPreview = ref<RewriteResult | null>(null)
const aiImageMode = ref(false)

async function openAiImageRewrite(): Promise<void> {
  const plan = draft.value as unknown as { prompt_zh?: string } | undefined
  const zh = (plan?.prompt_zh ?? '').trim()
  if (!zh) {
    message.info('请先填写中文解释 prompt_zh')
    return
  }
  aiImageMode.value = true
  aiShot.value = null
  aiOpen.value = true
  aiBusy.value = true
  aiPreview.value = null
  try {
    aiPreview.value = await rewritePromptFromZh(workspaceId.value, projectId.value, zh)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成失败，请重试')
    aiOpen.value = false
  } finally {
    aiBusy.value = false
  }
}

async function openAiRewrite(shot: DirectorShot): Promise<void> {
  aiImageMode.value = false
  aiShot.value = shot
  aiOpen.value = true
  aiBusy.value = true
  aiPreview.value = null
  try {
    const r = await rewritePromptFromZh(workspaceId.value, projectId.value, ((shot.action ?? shot.zh) ?? '').trim(), shot.positive_prompt, shot.negative_prompt)
    aiPreview.value = r
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成失败，请重试')
    aiOpen.value = false
  } finally {
    aiBusy.value = false
  }
}

function applyAiPrompt(): void {
  const p = aiPreview.value
  if (!p) return
  if (aiImageMode.value) {
    const plan = draft.value as unknown as { positive_prompt?: string; negative_prompt?: string } | undefined
    if (plan) {
      plan.positive_prompt = p.positive_prompt
      plan.negative_prompt = p.negative_prompt
    }
    aiImageMode.value = false
    aiOpen.value = false
    message.success('已写入图片正/负提示词（记得保存方案）')
    return
  }
  const s = aiShot.value
  if (!s) return
  s.positive_prompt = p.positive_prompt
  s.negative_prompt = p.negative_prompt
  s.en_synced = true
  aiOpen.value = false
  message.success('已写入该镜提示词（记得保存方案）')
}

interface BatchItem {
  shot: DirectorShot
  zh: string
  positive: string
  negative: string
}

const aiBatchOpen = ref(false)
const aiBatchBusy = ref(false)
const aiBatch = ref<BatchItem[]>([])

async function aiSyncAll(): Promise<void> {
  // 只同步“改动过但未 AI 同步”的镜头（en_synced===false；未改动/已同步的跳过）
  const shots = ((draft.value as unknown as { shots?: DirectorShot[] })?.shots ?? []).filter(
    (s) => s.en_synced === false && ((s.action ?? s.zh) ?? '').trim().length > 0,
  )
  if (!shots.length) {
    message.info('没有待同步的镜头（改动画面动作后会标记待同步；未改动的不会重复生成）')
    return
  }
  aiBatchBusy.value = true
  aiBatch.value = []
  try {
    for (const shot of shots) {
      const r = await rewritePromptFromZh(workspaceId.value, projectId.value, ((shot.action ?? shot.zh) ?? '').trim(), shot.positive_prompt, shot.negative_prompt)
      aiBatch.value.push({
        shot,
        zh: ((shot.action ?? shot.zh) ?? '').trim(),
        positive: r.positive_prompt,
        negative: r.negative_prompt,
      })
    }
    aiBatchOpen.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '批量生成失败')
  } finally {
    aiBatchBusy.value = false
  }
}

function aiBatchApply(): void {
  for (const item of aiBatch.value) {
    item.shot.positive_prompt = item.positive
    item.shot.negative_prompt = item.negative
    item.shot.en_synced = true
  }
  aiBatchOpen.value = false
  message.success('已写入 ' + aiBatch.value.length + ' 镜提示词（记得保存方案）')
}

async function handleSave(): Promise<boolean> {
  if (!draft.value || !selectedRevId.value) return false
  saving.value = true
  try {
    // 视频：镜头总长变化 → 先同步项目时长（patch 与 approve 按项目时长校验镜头总长）
    if (draft.value.mode === 'video') {
      const shots = (draft.value.shots ?? []) as DirectorShot[]
      const total = shots.reduce((a, s) => a + (Number(s.duration_sec) || 0), 0)
      if (total >= 1) {
        await updateProjectDuration(workspaceId.value, projectId.value, Math.round(total * 100) / 100)
        void queryClient.invalidateQueries({ queryKey: ['project'] })
      }
    }
    const det = await patchRevision(workspaceId.value, projectId.value, selectedRevId.value, draft.value)
    // 已确认版本微调会“另存新版本”：跟随新版本 id
    if (det && det.id && det.id !== selectedRevId.value) {
      selectedRevId.value = det.id
    }
    // 以服务端回读为准重建草稿（负词合并/尺寸补齐等归一化），并刷新版本摘要（source→user）
    initKey.value = ''
    void queryClient.invalidateQueries({ queryKey: ['revisions'] })
    await queryClient.invalidateQueries({ queryKey: ['revision', workspaceId.value, projectId.value, selectedRevId.value] })
    message.success('已保存修改（手改版）')
    return true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

/* ---------- P8：导入方案 JSON（把一份 plan 灌进当前草稿，复用保存链路） ---------- */
const importOpen = ref(false)
const importText = ref('')
const importProblems = ref<string[]>([])
const importBusy = ref(false)

function openImport(): void {
  importText.value = ''
  importProblems.value = []
  importOpen.value = true
}

/** 选文件读入文本框 */
function onImportFile(ev: Event): void {
  const f = (ev.target as HTMLInputElement)?.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => {
    importText.value = String(reader.result ?? '')
    importProblems.value = []
  }
  reader.onerror = () => message.error('文件读取失败')
  reader.readAsText(f)
  ;(ev.target as HTMLInputElement).value = ''   // 允许重复选同一文件
}

/** 解析 + 归一化 + 预检；返回可用 plan（失败返回 null 并写入 importProblems） */
function parseImport(): DirectorPlan | null {
  const raw = importText.value.trim()
  if (!raw) {
    importProblems.value = ['请先选择文件或粘贴 JSON']
    return null
  }
  let parsed: DirectorPlan
  try {
    parsed = JSON.parse(raw) as DirectorPlan
  } catch (e) {
    importProblems.value = ['JSON 解析失败：' + (e instanceof Error ? e.message : String(e))]
    return null
  }
  if (!parsed || typeof parsed !== 'object') {
    importProblems.value = ['不是合法的方案对象']
    return null
  }
  const cur = draft.value
  if (cur && parsed.mode && parsed.mode !== cur.mode) {
    importProblems.value = [`模式不匹配：当前项目是 ${cur.mode}，导入的是 ${parsed.mode}`]
    return null
  }
  const norm = normalizePlan(parsed)
  importProblems.value = planProblems(norm)   // 与后端 §10.3 同口径的精简预检
  return norm
}

async function doImport(save: boolean): Promise<void> {
  const norm = parseImport()
  if (!norm) return
  draft.value = norm
  importBusy.value = true
  try {
    if (save) {
      const ok = await handleSave()
      if (!ok) return
      message.success('已导入并保存为手改版')
    } else {
      message.info('已载入草稿（未保存），确认无误后点「保存修改」')
    }
    importOpen.value = false
  } finally {
    importBusy.value = false
  }
}

/** P9：克隆配音弹窗上下文 */
const cloneOpen = ref(false)
const cloneCtx = ref<{ mode: 'preset' | 'line'; name: string; shotNo: number; lineIndex: number; atSec: number; subject: string; replaceId?: string }>({
  mode: 'preset', name: '', shotNo: 0, lineIndex: 0, atSec: 0, subject: '',
})

function openCloneDialog(ctx: { mode: 'preset' | 'line'; name?: string; shotNo?: number; lineIndex?: number; atSec?: number; subject?: string; replaceId?: string }): void {
  cloneCtx.value = {
    mode: ctx.mode,
    name: ctx.name ?? '',
    shotNo: ctx.shotNo ?? 0,
    lineIndex: ctx.lineIndex ?? 0,
    atSec: ctx.atSec ?? 0,
    subject: ctx.subject ?? '',
    replaceId: ctx.replaceId,
  }
  cloneOpen.value = true
}

/** 删除一组音色资产（重录后清理旧文件 / 删除音色时用）；失败不阻断主流程 */
async function cleanupPresetAssets(presetAssetId?: string | null, rawAssetId?: string | null): Promise<void> {
  if (!presetAssetId) return
  try {
    await deleteVoicePreset(workspaceId.value, projectId.value, presetAssetId, rawAssetId)
    void queryClient.invalidateQueries({ queryKey: ['assets'] })
  } catch {
    // 文件清理失败不影响方案（后续可手动清资产）
  }
}

/** 弹窗保存：写入音色库（A）；replaceId 时是“重录替换”；line 模式下 usedForLine 紧跟着触发 */
function onCloneSaved(p: {
  id: string
  name: string
  presetAssetId: string
  rawAssetId: string
  promptText: string
  durationSec: number
}): void {
  lastCloneAssetId.value = p.presetAssetId
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const audio = plan.audio as unknown as Record<string, unknown>
  const list = Array.isArray(audio.voicePresets) ? (audio.voicePresets as Record<string, unknown>[]) : []
  const id = cloneCtx.value.replaceId || p.id
  const idx = list.findIndex((x) => x && x.id === id)
  const old = idx >= 0 ? list[idx] : undefined
  const entry = {
    id,
    name: p.name,
    assetId: p.presetAssetId,
    rawAssetId: p.rawAssetId,
    promptText: p.promptText,
    durationSec: p.durationSec,
    processed: true,
  }
  if (idx >= 0) list[idx] = entry
  else list.push(entry)
  audio.voicePresets = list
  if (!cloneCtx.value.replaceId && !(plan.audio.voice ?? '').trim()) {
    plan.audio.voice = `clone:${p.id}`
  }
  message.success(
    cloneCtx.value.replaceId
      ? `音色「${p.name}」已重录替换（记得保存方案）`
      : `音色「${p.name}」已加入音色库（记得保存方案）`,
  )
  // 重录：旧的两份文件删掉，避免越积越多
  if (old && old.assetId && old.assetId !== p.presetAssetId) {
    void cleanupPresetAssets(String(old.assetId), (old.rawAssetId as string | undefined) ?? null)
  }
}

/** 删除音色：先把引用情况摆给用户看，确认后清引用 + 删文件 */
async function removeClonePreset(id: string): Promise<void> {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const audio = plan.audio as unknown as Record<string, unknown>
  const list = Array.isArray(audio.voicePresets) ? (audio.voicePresets as Record<string, unknown>[]) : []
  const idx = list.findIndex((x) => x && x.id === id)
  if (idx < 0) return
  const entry = list[idx]
  const voice = `clone:${id}`
  const subjects = (plan.audio.voiceBindings ?? []).filter((b) => b.voice === voice).map((b) => b.subject || '(未命名)')
  let lines = 0
  for (const sh of plan.shots ?? []) {
    for (const l of sh.narrations ?? []) {
      if ((l.voice ?? '') === voice) lines++
    }
  }
  const refs = [
    subjects.length ? `角色绑定（${subjects.join('、')}）` : '',
    lines ? `${lines} 行台词的音色覆盖` : '',
  ].filter(Boolean).join(' 和 ')

  dialog.warning({
    title: '删除音色',
    content: refs
      ? `音色「${entry.name}」正被${refs}引用。删除后这些引用会被清空（回落到默认音色）。确定删除？`
      : `确定删除音色「${entry.name}」？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      // 1) 清引用（否则方案会指向不存在的音色，生成时报错）
      if (Array.isArray(plan.audio.voiceBindings)) {
        plan.audio.voiceBindings = plan.audio.voiceBindings.filter((b) => b.voice !== voice)
      }
      for (const sh of plan.shots ?? []) {
        for (const l of sh.narrations ?? []) {
          if ((l.voice ?? '') === voice) l.voice = null
        }
      }
      // 2) 从音色库移除
      list.splice(idx, 1)
      audio.voicePresets = list
      if ((plan.audio.voice ?? '') === voice) plan.audio.voice = ''
      // 3) 删存储文件
      await cleanupPresetAssets(String(entry.assetId ?? ''), (entry.rawAssetId as string | undefined) ?? null)
      message.success(`音色「${entry.name}」已删除（记得保存方案）`)
    },
  })
}

/** B：把刚处理好的样本落成本行配音 */
async function onCloneUsedForLine(): Promise<void> {
  const ctx = cloneCtx.value
  const presetAssetId = lastCloneAssetId.value
  if (!presetAssetId) {
    message.warning('没有拿到样本资产，请重新处理一次')
    return
  }
  try {
    await useSampleAsLineVoice(workspaceId.value, projectId.value, {
      shotNo: ctx.shotNo,
      lineIndex: ctx.lineIndex,
      atSec: ctx.atSec,
      subject: ctx.subject,
      srcAssetId: presetAssetId,
    })
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success(`第 ${ctx.shotNo} 镜第 ${ctx.lineIndex + 1} 段已用你的录音作为配音`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '落库失败')
  }
}

/** 弹窗最近一次处理出的样本资产（usedForLine 要用） */
const lastCloneAssetId = ref('')

async function handleApprove(): Promise<void> {
  if (!selectedRevId.value) return
  // 有未保存改动：先保存草稿再确认（否则确认会用服务端旧方案，草稿丢失）
  if (dirty.value && draft.value) {
    const ok = await handleSave()
    if (!ok) return
  }
  approving.value = true
  try {
    const res = await approveRevision(workspaceId.value, projectId.value, selectedRevId.value)
    await invalidateAll()
    message.success(`方案 v${activeRevision.value?.revisionNo ?? ''} 已确认 — W3 由此发起生成`)
    void res
  } catch (e) {
    message.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    approving.value = false
  }
}

async function handleApproveShot(shotNo: number): Promise<void> {
  const rec = detail.data.value?.shots.find((s) => s.shotNo === shotNo)
  if (!rec) return
  shotBusy.value = shotNo
  try {
    await approveShot(workspaceId.value, projectId.value, rec.id)
    await queryClient.invalidateQueries({
      queryKey: ['revision', workspaceId.value, projectId.value, selectedRevId.value],
    })
    message.success(`第 ${shotNo} 镜已确认`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '单镜确认失败')
  } finally {
    shotBusy.value = null
  }
}

const summaryChips = computed(() => {
  const p = project.data.value
  if (!p) return []
  const chips = [modeLabel(p.mode), `${p.aspectRatio} ${aspectNote(p.aspectRatio)}`]
  if (p.mode === 'video' && p.durationSec) chips.push(`${round2(p.durationSec)}s`)
  return chips
})

function revLabel(rev: { revisionNo: number; source: string; approved: boolean }): string {
  return `v${rev.revisionNo} · ${SOURCE_LABEL[rev.source] ?? rev.source}${rev.approved ? ' ✓已确认' : ''}`
}

const shotTotal = computed(() => {
  const shots = draft.value && isVideoPlan(draft.value) ? draft.value.shots : []
  const sum = shots.reduce((acc, s) => acc + (Number(s.duration_sec) || 0), 0)
  return round2(sum)
})
</script>

<template>
  <div class="studio-page" data-testid="director-studio">
    <!-- 顶部 -->
    <div class="studio-head">
      <button type="button" class="back" @click="router.push({ name: 'projects' })">
        <NIcon size="15"><ArrowLeft /></NIcon>
        <span>项目</span>
      </button>
      <div class="head-main">
        <div class="head-copy">
          <div class="eyebrow-row">
            <span class="font-mono eyebrow">{{ summaryChips.join(' · ') }}</span>
            <NTag v-if="detApproved" size="small" :bordered="false" type="success" class="state-tag">已确认</NTag>
            <NTag
              v-else-if="draft"
              size="small"
              :bordered="false"
              type="info"
              class="state-tag"
              data-testid="state-directing"
            >
              待确认
            </NTag>
          </div>
          <h1 class="title font-display">{{ project.data.value?.title ?? '…' }}</h1>
        </div>
      </div>
    </div>

    <!-- 加载中骨架 -->
    <template v-if="project.isPending.value || briefs.isPending.value">
      <div class="skel-row">
        <NSkeleton height="420px" width="260px" />
        <NSkeleton height="420px" width="100%" />
      </div>
    </template>

    <!-- 无 Brief：导演入口 -->
    <template v-else-if="!latestBrief">
      <div class="intro">
        <section class="intro-card">
          <p class="font-mono eyebrow">STEP 1 · 一句话需求</p>
          <h2 class="intro-title">导演层帮你把 Brief 织成可编辑方案</h2>
          <p class="text-secondary intro-copy">
            系统先产出提示词 / 剧本 / 分镜，经你逐字段修改与确认后才进入生成（§0-3 确认闸门）。
          </p>
          <div class="intro-composer">
            <BriefComposer
              :mode="(project.data.value?.mode ?? 'image')"
              :busy="creating || generating"
              @submit="handleFirstBrief"
            />
          </div>
        </section>
      </div>
    </template>

    <!-- 导演台：左 Brief / 中方案 / 底部版本条 -->
    <template v-else>
      <div class="studio-grid">
        <!-- ① BRIEF 单独一行 -->
        <section class="brief-row">
          <div class="brief-panel">
            <div class="brief-head">
              <span class="font-mono eyebrow">BRIEF</span>
              <button v-if="!briefEditing" type="button" class="link-btn" data-testid="btn-new-brief" @click="briefEditing = true">
                换一段需求
              </button>
              <button v-else type="button" class="link-btn" @click="briefEditing = false">取消</button>
            </div>

            <BriefComposer
              v-if="briefEditing"
              :mode="composerMode"
              :busy="creating || generating"
              @submit="handleNewBrief"
            />
            <template v-else>
              <p class="brief-text">{{ shownBrief?.rawText }}</p>
            </template>
          </div>
          <div v-if="generating || creating" class="brief-spinner">
            <NButton size="small" loading :bordered="false" quaternary>导演层思考中…</NButton>
          </div>
        </section>

        <!-- ② 左：参考图卡片；右：位置预览卡片 -->
        <div class="ref-row">
          <div class="refs-panel" data-testid="refs-panel">
            <div class="brief-head">
              <span class="font-mono eyebrow">参考图 / 剧情主体</span>
              <div class="ref-head-ops">
                <button type="button" class="op" :disabled="subjectBusy === 'extract' || !canEdit"
                        data-testid="btn-extract-subjects" @click="onExtractSubjects">
                  {{ subjectBusy === 'extract' ? '抽取中…' : '一键生成主体' }}
                </button>
                <label class="upload-link" :class="{ busy: uploadingRef }">
                  <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                  <span v-if="uploadingRef">上传中…</span>
                  <span v-else>+ 上传</span>
                </label>
              </div>
            </div>

            <!-- P13：剧情主体列表（勾选=参与锚定；定妆图=一致性锚定图） -->
            <div v-if="planSubjects().length" class="subj-list" data-testid="subject-list">
              <div v-for="sub in planSubjects()" :key="sub.name" class="subj-row" :data-testid="`subj-${sub.name}`">
                <label class="subj-check" :title="sub.enabled === false ? '未勾选 = 不参与锚定' : '参与锚定'">
                  <input type="checkbox" :checked="sub.enabled !== false"
                         @change="toggleSubject(sub.name, ($event.target as HTMLInputElement).checked)" />
                </label>
                <span class="subj-name">{{ sub.name }}</span>
                <span v-if="sub.aliases?.length" class="subj-alias font-mono" :title="sub.aliases?.join('、')">
                  {{ sub.aliases?.slice(0, 2).join('/') }}<template v-if="(sub.aliases?.length ?? 0) > 2">…</template>
                </span>
                <span :class="['subj-portrait', 'font-mono', { on: !!sub.portraitAssetId }]">
                  {{ sub.portraitAssetId ? `定妆图 v${sub.portraitVersion ?? 1}` : '定妆图 ✗' }}
                </span>
                <span class="subj-refs font-mono" :title="'该主体的素材参考图张数'">
                  {{ (sub.refs ?? []).length }} 图
                </span>
                <NButton size="tiny" secondary :loading="portraitBusy" :disabled="!canEdit"
                         :data-testid="`subj-gen-${sub.name}`" title="用该主体勾选的素材图生成标准定妆图"
                         @click="genPortrait(sub.name)">
                  {{ sub.portraitAssetId ? '换一版' : '生成图像' }}
                </NButton>
                <NButton size="tiny" quaternary :disabled="!portraitsOf(sub.name).length"
                         :data-testid="`subj-pick-${sub.name}`" title="把最新一版定妆图设为该主体的锚定图"
                         @click="pickPortrait(sub.name)">
                  选图
                </NButton>
              </div>
              <p class="subj-hint text-secondary">
                勾选 = 参与锚定；分镜锚定用「定妆图」优先，没有定妆图才用素材图。改动后请<b>保存并重新确认</b>，否则生成仍读旧稿。
              </p>
            </div>
            <div v-if="refLibrary.length" class="refs-grid">
              <div
                v-for="a in refLibrary.slice(0, 8)"
                :key="a.id"
                :class="['ref-thumb', { sel: refSelected.includes(a.id), off: refUnchecked.includes(a.id) }]"
                :title="refSelected.includes(a.id) ? '点击取消加入' : '点击用作参考'"
                @click="toggleRef(a.id, !refSelected.includes(a.id))"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <!-- P13：勾选=参与参考（未勾选视为不作为参考） -->
                <label v-if="refSelected.includes(a.id)" class="ref-check" title="勾选=参与参考；取消勾选=不参与（不必删除）"
                       @click.stop>
                  <input type="checkbox" :checked="!refUnchecked.includes(a.id)"
                         :data-testid="`ref-check-${a.id.slice(0, 8)}`"
                         @change="toggleRefChecked(a.id, ($event.target as HTMLInputElement).checked)" />
                </label>
                <i v-else class="ref-badge font-mono">REF</i>
                <span class="ref-time font-mono">{{ shortTime(a.createdAt) }}</span>
                <button
                  v-if="a.kind === 'reference'"
                  type="button"
                  class="ref-del"
                  title="删除该参考图"
                  @click.stop="removeRefAsset(a.id)"
                >
                  ×
                </button>
              </div>
            </div>

            <div v-if="selectedRefAssets.length" class="ref-subjects">
              <p class="ref-subjects-title font-mono">已选（资产库点「参考」的图也会出现在这里）</p>
              <div v-for="a in selectedRefAssets" :key="a.id" class="ref-subject-block">
                <div class="ref-subject-row">
                  <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" class="ref-subject-thumb" alt="" />
                  <input
                    class="text"
                    type="text"
                    :value="refSubjects[a.id] ?? ''"
                    placeholder="主体名，如 唐僧 / 女王"
                    @input="onRefSubjectInput(a.id, $event)"
                  />
                  <button type="button" class="ref-op" title="取消选择" @click="toggleRef(a.id, false)">取消</button>
                  <button
                    v-if="a.kind === 'reference'"
                    type="button"
                    class="ref-op danger"
                    title="删除该参考图"
                    @click="removeRefAsset(a.id)"
                  >
                    删除
                  </button>
                </div>
                <div class="ref-region-row">
                  <span class="ref-region-label font-mono">区域%</span>
                  <input
                    v-for="k in (['x', 'y', 'w', 'h'] as const)"
                    :key="k"
                    class="text ref-region-input"
                    type="text"
                    inputmode="numeric"
                    :placeholder="k"
                    :value="refRegions[a.id]?.[k] ?? ''"
                    @input="setRefRegion(a.id, k, ($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>
            </div>
            <p v-else class="ref-hint text-secondary">
              上传参考图（png/jpg/webp，本方案最多 15 张）或从下方资产库点「参考」；给选中的图标主体名（如「唐僧」）后，系统只会在文案提到该主体的镜头里使用它，并把「形象以参考图为准」写入提示词。
            </p>
            <p v-if="refSelected.length" class="ref-count font-mono">{{ refSelected.length }}/{{ MAX_REFS }} 已选</p>
            <!-- P12：模型一次能收几张参考图（各模型不同）——超出会被丢弃，提前提示 -->
            <p v-if="refOverModelLimit" class="ref-conflict" data-testid="ref-model-limit">
              当前图片模型「{{ modelRefHint.name }}」最多接收 {{ modelRefHint.max }} 张参考图，
              本方案已绑定 {{ refSelected.length }} 张 —— 超出的会被自动丢弃（多主体镜头建议改用
              支持多图的模型，如 bytedance/seedream-4 / google/nano-banana：image_input）。
            </p>
            <p v-if="cameraIntentWithRefs" class="ref-conflict">
              检测到「背影/过肩/机位」类构图诉求：参考图可能把构图拉回参考视角。建议先取消勾选参考图（仅需形象/画风锚定时再选），或把机位写进「视角/前景/主体朝向」字段。
            </p>
          </div>

          <div class="pos-panel" data-testid="pos-panel">
            <div class="brief-head">
              <span class="font-mono eyebrow">位置预览（相对位置 / 区域%）</span>
              <button v-if="selectedRefAssets.length > 1" type="button" class="link-btn" @click="autoLayoutRegions">
                自动均分
              </button>
            </div>
            <div class="pos-frame" :style="{ aspectRatio: posAspectCss }">
              <div class="pos-third pos-third-v1" /><div class="pos-third pos-third-v2" />
              <div class="pos-third pos-third-h1" /><div class="pos-third pos-third-h2" />
              <div
                v-for="it in refPreviewItems.filter((i) => i.region)"
                :key="it.id"
                class="pos-box"
                :style="{
                  left: (it.region?.x ?? 0) + '%',
                  top: (it.region?.y ?? 0) + '%',
                  width: (it.region?.w ?? 100) + '%',
                  height: (it.region?.h ?? 100) + '%',
                  borderColor: it.color,
                  background: it.color + '22',
                }"
                @pointerdown="onBoxPointerDown($event, it, 'move')"
                @pointermove="onBoxPointerMove"
                @pointerup="onBoxPointerUp"
                @pointercancel="onBoxPointerUp"
              >
                <span class="pos-label font-mono" :style="{ color: it.color }">{{ it.label }}</span>
                <span class="pos-resize" @pointerdown.stop="onBoxPointerDown($event, it, 'resize')" />
              </div>
              <p v-if="!refPreviewItems.some((i) => i.region)" class="pos-empty text-secondary">尚无区域：点「自动均分」或拖动下方未设区域的条目</p>
            </div>
            <p v-if="refPreviewItems.some((i) => !i.region)" class="pos-unset">
              未设区域（整幅生效）：
              <span v-for="it in refPreviewItems.filter((i) => !i.region)" :key="it.id" class="pos-chip font-mono">{{ it.label }}</span>
            </p>
            <p class="ref-hint text-secondary">
              拖动色块移动、右下角拖动缩放；也可在上方「区域%」精确填写（x/y=左上角，w/h=宽高，0–100）。填了区域后，GPU(Comfy) 会按区域分别注入参考图（彻底解耦多角色）；云模型无遮罩能力，会把方位写进提示词。
            </p>
          </div>
        </div>

        <!-- 中：方案编辑区 -->
        <main class="col-main">
          <template v-if="!draft">
            <div class="plan-empty">
              <NButton
                v-if="!generating && !creating"
                type="primary"
                size="large"
                :data-testid="'gen-from-brief'"
                @click="latestBrief && doGenerate(latestBrief.id, undefined)"
              >
                <template #icon><NIcon><WandSparkles :size="16" /></NIcon></template>
                让导演层基于这段 Brief 出方案
              </NButton>
              <p v-else class="text-secondary font-mono">DIRECTING…</p>
            </div>
          </template>

          <template v-else>
            <section class="plan-head">
              <div class="plan-title-row">
                <span class="font-mono rev-pill" data-testid="revision-label">
                  {{ activeRevision ? revLabel(activeRevision) : '' }}
                </span>
                <span v-if="activeRevision?.source === 'stub'" class="stub-note">未接 LLM，示例方案（配置 weaveora.llm.* 启用真导演）</span>
              </div>
              <template v-if="isImageNow">
                <ImagePlanEditor :plan="imgPlanForEdit" :disabled="!canEdit" @ai-prompt-zh="openAiImageRewrite" />
              </template>
              <template v-else-if="isVideoNow">
                <VideoPlanEditor
                  :plan="vidPlanForEdit"
                  :records="detail.data.value?.shots ?? []"
                  :disabled="!canEdit"
                  :busy-shot="shotBusy"
                  :preview-busy="previewBusy"
                  :audio-preview="audioPreview"
                  :durations="durations"
                  :locked-shots="lockedShots"
                  @toggle-lock="toggleShotLock"
                  @approve-shot="handleApproveShot"
                  @ai-prompt="openAiRewrite"
                  @ai-sync-all="aiSyncAll"
                  @preview-voice="previewVoice"
                  @preview-bgm="previewBgm"
                  @gen-line="genVoiceLine"
                  @preview-line="previewVoiceLine"
                  @import-line="importVoiceLine"
                  @clone-voice="openCloneDialog"
                  @patch-shot="onPatchShot"
                  @ai-lines="onAiLines"
                  @ai-music="onAiMusic"
                  @remove-preset="removeClonePreset"
                  @update:plan="() => {}"
                  @close-preview="closeAudioPreview"
                />
              </template>
            </section>

            <NAlert
              v-if="problems.length"
              type="warning"
              :show-icon="true"
              :bordered="false"
              size="small"
              class="plan-alert"
            >
              提交前请修正：{{ problems.join('；') }}
            </NAlert>
            <NAlert
              v-else-if="isVideoPlan(draft)"
              type="info"
              :show-icon="false"
              :bordered="false"
              size="small"
              class="plan-alert"
            >
              镜头时长合计 {{ shotTotal }}s / 目标 {{ round2(Number(draft.duration_sec) || 0) }}s —— 与目标一致后即可确认
            </NAlert>
          </template>
        </main>
      </div>

      <!-- P8：试听播放条已上提到方案编辑器里（紧跟试听配音/试听配乐按钮） -->

      <!-- 任务区（W3）：确认后发起生成，展示状态/进度 -->
      <div v-if="detApproved || (jobs.data.value ?? []).length" class="jobs-panel" data-testid="jobs-panel">
        <div class="jobs-head">
          <span class="font-mono eyebrow">任务 / 生成</span>
          <label class="filter-latest" title="只看每个分镜每类最新一条（隐藏历史版本）">
            <input type="checkbox" v-model="filterLatest" />
            只看最近一轮
          </label>

          <!--
            P12：所有生成按钮（含运动）固定在卡片右上角、横向一行。
            注意：**不能用 v-if 按状态隐藏** —— 否则未确认方案/有进行中任务时按钮会“消失”，
            用户找不到入口；改为常显 + :disabled + title 说明前置条件。
          -->
          <div class="jobs-actions" data-testid="job-gen-actions">
            <span v-if="activeJobCount" class="state-hint font-mono gen-live" data-testid="job-live-hint">
              {{ freshActiveCount || activeJobCount }} 个进行中…
            </span>
            <span v-if="!isVideoNow" class="count-inline">
              张数
              <select v-model="imgCount" class="mini-select">
                <option :value="1">1</option>
                <option :value="2">2</option>
                <option :value="4">4</option>
              </select>
            </span>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-motion-jobs"
              :disabled="!motionReady"
              :title="motionReady ? '把已确认的关键帧做成运动片段(motion)' : '先出关键帧(still)并确认后可用（两段式 §11.3）'"
              @click="openMotionModal"
            >
              运动(motion)
            </NButton>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-voice-jobs"
              :disabled="!detApproved"
              :title="detApproved ? '自托管 CosyVoice：逐镜台词 → 配音' : '需先确认方案（右上角「确认」）后生成配音；未确认的镜头不能配音'"
              @click="withShotPicker('生成配音(voice)', (nos) => startVoice(nos))"
            >
              生成配音(voice)
            </NButton>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-bgm-jobs"
              :disabled="!detApproved"
              :title="detApproved ? '自托管音乐生成：按 music_mood 生成整片 BGM' : '需先确认方案（右上角「确认」）后生成配乐'"
              @click="startBgm"
            >
              生成配乐(bgm)
            </NButton>
            <NButton size="small" type="primary" :loading="genBusy" data-testid="btn-gen-jobs" @click="withShotPicker(isVideoNow ? '生成关键帧(still)' : '开始生成', (nos) => startGeneration(nos))">
              {{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}
            </NButton>
          </div>
        </div>

        <!-- P12：按类型分 Tab（不含成片 master——任务区不产出成片） -->
        <nav class="type-tabs" data-testid="job-tabs">
          <button
            v-for="t in JOB_TABS"
            :key="t.key"
            type="button"
            :class="['type-tab', { on: jobTab === t.key, zero: !(jobTabCounts[t.key] ?? 0) }]"
            :title="t.hint"
            :data-testid="`job-tab-${t.key}`"
            @click="pickJobTab(t.key)"
          >
            <span>{{ t.label }}</span>
            <span v-if="t.kind" class="type-tab-k font-mono">{{ t.kind }}</span>
            <span class="type-tab-n font-mono">{{ jobTabCounts[t.key] ?? 0 }}</span>
          </button>
        </nav>

        <div v-if="eligibleJobs.length" class="batchbar" data-testid="job-batchbar">
          <label class="batch-check">
            <input type="checkbox" :checked="eligibleAllSelected" @change="toggleEligibleAll" />
            <span class="text-secondary">全选失败/取消</span>
          </label>
          <span class="batch-count font-mono">{{ jobSel.length }} 已选</span>
          <button type="button" class="op primary" :disabled="jobSel.length === 0 || jobActionBusy"
                  data-testid="btn-retry-batch" @click="retryJobsSel">
            重试选中
          </button>
          <button type="button" class="op danger" :disabled="jobSel.length === 0 || jobActionBusy"
                  data-testid="btn-delete-batch" @click="deleteJobsSel">
            删除选中
          </button>
        </div>
        <div v-if="(jobs.data.value ?? []).length" class="job-list">
          <div v-for="j in visibleJobs" :key="j.id" class="job-row" :data-testid="'job-' + j.id.slice(0, 8)" :title="jobAuditTitle(j)">
            <label v-if="isJobActionable(j)" class="row-check">
              <input type="checkbox" :checked="jobSel.includes(j.id)" @change="toggleJobSel(j.id)" />
            </label>
            <span v-else class="row-check" />
            <span class="job-kind font-mono">[{{ KIND_LABEL[j.kind] ?? j.kind }}{{ j.payload?.preview ? '·试听' : '' }}<template v-if="j.kind === 'still' || j.kind === 'clip' || j.kind === 'voice'"> · 第{{ j.payload?.shot_no ?? '—' }}镜</template><template v-if="j.kind === 'voice' && j.payload?.line_index != null"> · 第{{ (j.payload.line_index ?? 0) + 1 }}段</template><template v-if="j.payload?.subject"> · {{ j.payload.subject }}</template><template v-if="j.kind === 'bgm' && j.payload?.mood"> · {{ j.payload.mood }}</template><template v-if="j.payload?.frame_label"> · {{ j.payload.frame_label }}</template>]</span>
            <span
              v-if="revOfJob(j)"
              :class="['job-rev', 'font-mono', { stale: revOfJob(j)?.stale }]"
              :title="revOfJob(j)?.stale
                ? `此任务生成时锚定的是 v${revOfJob(j)?.no}（非当前确认稿）。改镜并重新确认后：新生成的/重试的任务会自动按当前确认稿取词`
                : '基于当前确认稿生成'"
            >
              v{{ revOfJob(j)?.no }}{{ revOfJob(j)?.stale ? '·旧' : '' }}
            </span>
            <span :class="['job-state', j.state]">
              {{ JOB_STATE_LABEL[j.state] ?? j.state }}{{ j.state === 'running' && j.stage ? ' · ' + j.stage : '' }}
            </span>
            <div class="job-bar"><span class="job-fill" :style="{ width: j.progress + '%' }" /></div>
            <span class="job-pct font-mono">{{ j.progress }}%</span>
            <NButton
              v-if="(j.state === 'queued' || j.state === 'running') && !j.cancelRequested"
              size="tiny"
              quaternary
              :loading="cancelBusy === j.id"
              data-testid="btn-cancel-job"
              @click="cancelOne(j.id)"
            >
              取消
            </NButton>
            <span v-else-if="j.errorMessage" class="job-err" :title="j.errorMessage">!</span>
            <NButton
              v-if="['succeeded', 'failed', 'cancelled'].includes(j.state)"
              size="tiny"
              quaternary
              type="primary"
              :loading="rerunBusy === j.id"
              data-testid="btn-rerun-job"
              @click="rerunJobOne(j.id)"
            >
              重跑
            </NButton>
            <template v-if="isJobActionable(j)">
              <button type="button" class="op primary" :disabled="jobActionBusy"
                      data-testid="btn-retry-job" @click="retryJobOne(j.id)">
                重试
              </button>
              <button type="button" class="op danger" :disabled="jobActionBusy"
                      data-testid="btn-delete-job" @click="deleteJobOne(j.id)">
                删除
              </button>
            </template>
          </div>
          <button
            v-if="(jobs.data.value ?? []).length > jobLimit"
            type="button"
            class="op panel-more"
            data-testid="btn-more-jobs"
            @click="showMoreJobs"
          >
            查看更多（余 {{ (jobs.data.value ?? []).length - jobLimit }} 条）
          </button>
        </div>
        <p v-else-if="detApproved" class="job-empty text-secondary">
          方案已确认 —— 点「{{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}」发起（先出静帧关键帧，确认后再运动）。
        </p>
      </div>

      <!-- 资产库（W4） -->
      <div v-if="outputAssets.length || galManage" class="gallery-panel" data-testid="gallery-panel">
        <div class="jobs-head">
          <span class="font-mono eyebrow">资产库</span>
          <span class="state-hint font-mono">{{ galleryForTab.length }} / {{ outputAssets.length }} 个产物</span>
          <div class="jobs-actions">
            <template v-if="galManage">
              <label class="batch-check">
                <input type="checkbox" :checked="galAllSelected" @change="toggleGalAll" />
                <span class="text-secondary">全选</span>
              </label>
              <span class="batch-count font-mono">{{ galSel.length }} 已选</span>
              <button type="button" class="op primary" :disabled="!galSel.length || galBusy"
                      data-testid="btn-share-assets" @click="shareSelectedAssets">
                分享选中
              </button>
              <button type="button" class="op danger" :disabled="galSel.length === 0 || galBusy"
                      data-testid="btn-del-assets-batch" @click="removeGalSelected">
                删除选中
              </button>
              <button type="button" class="op" :disabled="galBusy" @click="toggleGalManage">完成</button>
            </template>
            <button v-else type="button" class="op" data-testid="btn-manage-assets" @click="toggleGalManage">
              管理
            </button>
          </div>
        </div>

        <!-- P12：资产库同样按类型分 Tab -->
        <nav class="type-tabs" data-testid="gallery-tabs">
          <button
            v-for="t in GAL_TABS"
            :key="t.key"
            type="button"
            :class="['type-tab', { on: galTab === t.key, zero: !(galTabCounts[t.key] ?? 0) }]"
            :title="t.hint"
            :data-testid="`gallery-tab-${t.key}`"
            @click="pickGalTab(t.key)"
          >
            <span>{{ t.label }}</span>
            <span v-if="t.kind" class="type-tab-k font-mono">{{ t.kind }}</span>
            <span class="type-tab-n font-mono">{{ galTabCounts[t.key] ?? 0 }}</span>
          </button>
        </nav>

        <div class="gallery-grid">
          <div v-for="a in galleryForTab" :key="a.id" :class="['g-item', { manage: galManage, sel: galSel.includes(a.id) }]">
            <label v-if="galManage" class="g-sel">
              <input type="checkbox" :checked="galSel.includes(a.id)" @change="toggleGalSel(a.id)" />
            </label>
            <button v-if="galManage" type="button" class="g-del" :disabled="galBusy"
                    :title="'删除此' + a.kind" @click="removeAssetOne(a.id)">
              ×
            </button>
            <audio
              v-if="(a.mime ?? '').startsWith('audio/') && galUrls[a.id]"
              :src="galUrls[a.id]"
              class="g-audio"
              controls
              preload="metadata"
            />
            <video
              v-else-if="(a.kind === 'clip' || a.kind === 'master') && (a.mime ?? '').startsWith('video/') && galUrls[a.id]"
              :src="galUrls[a.id]"
              class="g-video"
              controls
              muted
              playsinline
              preload="metadata"
              @loadedmetadata="firstFrame"
            />
            <img v-else-if="galUrls[a.id]" :src="galUrls[a.id]" :alt="a.kind" loading="lazy" />
            <div v-else class="g-loading">…</div>
            <div class="g-meta">
              <span class="g-kind font-mono">{{ a.kind }}<template v-if="galShotNo(a)"> · 第{{ galShotNo(a) }}镜</template><template v-if="a.width"> · {{ a.width }}×{{ a.height }}</template><template v-if="galRevNo(a.jobId)"> · v{{ galRevNo(a.jobId) }}</template></span>
              <span class="g-actions">
                <button v-if="galUrls[a.id]" type="button" class="g-max" title="沉浸预览/播放"
                        @click.stop="openImmersive(a.id, a.mime ?? '')">
                  ⤢
                </button>
                <a v-if="galUrls[a.id]" :href="galUrls[a.id]" :download="'weaveora-' + a.id.slice(0, 8) + '.png'" title="下载">↓</a>
                <button
                  type="button"
                  class="g-ref"
                  :disabled="refSelected.includes(a.id)"
                  :title="refSelected.includes(a.id) ? '已选为参考' : '设为参考图（IP-Adapter 一致性）'"
                  @click="setRefFromAsset(a.id)"
                >
                  {{ refSelected.includes(a.id) ? '已选' : '参考' }}
                </button>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 沉浸式预览（大图/大视频） -->
      <NModal v-model:show="motionOpen" preset="card" :title="'生成运动(motion)'" style="max-width: 420px">
        <div class="motion-form">
          <p class="text-secondary">
            设置视频帧数（系统支持 {{ MOTION_MIN }}–{{ MOTION_MAX }}，帧数越高越流畅、耗时越长）：
          </p>
          <NInputNumber v-model:value="motionFrames" :min="MOTION_MIN" :max="MOTION_MAX" :step="4" style="width: 180px" />
          <div class="motion-ops">
            <NButton size="small" @click="motionOpen = false">取消</NButton>
            <NButton size="small" type="primary" @click="confirmMotion">开始生成</NButton>
          </div>
        </div>
      </NModal>

      <!-- AI 提示词确认（①：可确认/取消/微调后应用） -->
      <NModal v-model:show="aiOpen" preset="card" title="AI 生成提示词（可确认或取消）" style="max-width: 720px">
        <p class="text-secondary" style="margin: 0 0 10px; font-size: 13px;">
          第 {{ aiShot?.shot_no ?? '' }} 镜 · 中文：{{ (aiShot?.action ?? aiShot?.zh) ?? '' }}
        </p>
        <template v-if="aiBusy">
          <div class="g-loading" style="padding: 24px 0">AI 生成中…</div>
        </template>
        <template v-else-if="aiPreview">
          <div class="ai-fields">
            <label class="ai-label">正向提示词（英文，可微调）</label>
            <NInput v-model:value="aiPreview.positive_prompt" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" />
            <label class="ai-label">负向提示词（中文，可微调）</label>
            <NInput v-model:value="aiPreview.negative_prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" />
          </div>
          <div class="ai-actions">
            <NButton size="small" @click="aiOpen = false">取消</NButton>
            <NButton size="small" type="primary" data-testid="ai-apply" @click="applyAiPrompt">应用并写入</NButton>
          </div>
        </template>
      </NModal>

      <!-- AI 批量同步（①：多镜中文→LLM 更新，可确认/取消/逐镜微调） -->
      <NModal
        v-model:show="importOpen"
        preset="card"
        title="导入方案 JSON"
        style="max-width: 760px"
        data-testid="import-plan-modal"
      >
        <p class="hint-line text-secondary" style="margin-top: 0">
          把一份 plan JSON 灌入当前草稿（如
          <span class="font-mono">packages/fixtures/guan-yu-vs-lvbu.plan.json</span>）。
          导入前会先做客户端预检，再按需保存为手改版。
        </p>
        <div class="import-row">
          <input type="file" accept=".json,application/json" data-testid="import-plan-file" @change="onImportFile" />
          <NButton size="tiny" quaternary :disabled="!importText.trim()" @click="importText = ''">清空</NButton>
        </div>
        <NInput
          v-model:value="importText"
          type="textarea"
          :autosize="{ minRows: 8, maxRows: 20 }"
          placeholder='直接粘贴 JSON，或以 {"mode":"video",...} 开头的内容'
          data-testid="import-plan-text"
        />
        <NAlert v-if="importProblems.length" type="warning" style="margin-top: 10px" :show-icon="true">
          <p style="margin: 0 0 4px">预检发现 {{ importProblems.length }} 个问题（仍可先载入草稿再看）：</p>
          <ul style="margin: 0; padding-left: 18px">
            <li v-for="(p, i) in importProblems" :key="i">{{ p }}</li>
          </ul>
        </NAlert>
        <div class="ai-actions">
          <NButton size="small" :loading="importBusy" @click="doImport(false)">仅载入草稿</NButton>
          <NButton size="small" type="primary" :loading="importBusy" data-testid="import-plan-save" @click="doImport(true)">
            导入并保存
          </NButton>
        </div>
      </NModal>

      <NModal v-model:show="aiBatchOpen" preset="card" title="AI 同步提示词（确认或取消）" style="max-width: 760px">
        <template v-if="aiBatchBusy">
          <div class="g-loading" style="padding: 24px 0">AI 批量生成中（逐镜进行）…</div>
        </template>
        <template v-else>
          <div v-for="item in aiBatch" :key="item.shot.shot_no" class="ai-batch-item">
            <p class="ai-batch-title">第 {{ item.shot.shot_no }} 镜 · {{ item.zh }}</p>
            <label class="ai-label">正向提示词（英文，可微调）</label>
            <NInput v-model:value="item.positive" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" />
            <label class="ai-label">负向提示词（中文，可微调）</label>
            <NInput v-model:value="item.negative" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" />
          </div>
          <div class="ai-actions">
            <NButton size="small" @click="aiBatchOpen = false">取消（不应用）</NButton>
            <NButton size="small" type="primary" data-testid="ai-batch-apply" @click="aiBatchApply">应用全部</NButton>
          </div>
        </template>
      </NModal>

      <!-- 沉浸式预览（大图/大视频） -->
      <div v-if="immersive" class="im-overlay" @click.self="immersive = null">
        <div class="im-card">
          <button type="button" class="op im-close" @click="immersive = null">×</button>
          <video
            v-if="immersive.mime.startsWith('video/')"
            :src="immersive.url"
            class="im-media"
            controls
            playsinline
            autoplay
            muted
            preload="metadata"
            @loadedmetadata="firstFrame"
          />
          <img v-else :src="immersive.url" class="im-media im-img" alt="" />
        </div>
      </div>

      <!-- 成片 / 时间线（W6） -->
      <div v-if="isVideoNow && detApproved && exportRows.length" class="export-panel" data-testid="export-panel">
        <div class="jobs-head">
          <span class="font-mono eyebrow">成片 / 时间线</span>
          <div class="jobs-actions">
            <span class="state-hint font-mono">共 {{ exportTotal }}</span>
            <NButton size="small" secondary :loading="renderBusy" data-testid="btn-render"
                     @click="doRender">
              渲染成片(mp4)
            </NButton>
            <NButton size="small" type="primary" :loading="exportBusy" data-testid="btn-export"
                     @click="doExport">
              导出成片包(edit_list)
            </NButton>
          </div>
        </div>
        <div class="tl-rows">
          <div v-for="r in visibleExportRows" :key="r.rec.id" class="tl-row">
            <span class="tl-no font-mono">SHOT {{ r.rec.shotNo }}</span>
            <span class="tl-tc font-mono">{{ timecode(r.start) }}–{{ timecode(r.start + r.dur) }}</span>
            <div class="tl-bar"><span class="tl-fill" :style="{ width: (r.dur / Math.max(exportTotalSec, 1)) * 100 + '%' }" /></div>
            <span :class="['tl-state', r.hasMedia ? 'ok' : 'empty']">{{ r.hasMedia ? '素材已就绪' : '待生成' }}</span>
          </div>
        </div>
        <button
          v-if="exportRows.length > tlLimit"
          type="button"
          class="op panel-more"
          data-testid="btn-more-tl"
          @click="showMoreTl"
        >
          查看更多（余 {{ exportRows.length - tlLimit }} 条）
        </button>
        <p class="export-hint text-secondary">按方案分镜顺序生成 edit_list.json + 素材（clip 优先，无 motion 时用关键帧兜底）；剪映导入为兼容可选项。</p>
      </div>

      <!-- 底：版本条 + 确认闸门（§9.1/§9.5） -->
      <!-- P12：分镜勾选弹窗（>3 镜时生成前先选镜 + 可顺手封版） -->
      <ShotPickerDialog
        v-model:show="pickOpen"
        :title="pickCtx?.title ?? '选择分镜'"
        :shots="pickerShots"
        :locked="lockedShots"
        :busy="pickBusy"
        @confirm="onShotPicked"
      />

      <!-- P9：克隆配音弹窗（放在页面级，方案区与分镜共用同一个） -->
      <VoiceCloneDialog
        v-model:show="cloneOpen"
        :mode="cloneCtx.mode"
        :default-name="cloneCtx.name"
        :replace-id="cloneCtx.replaceId"
        :workspace-id="workspaceId"
        :project-id="projectId"
        :shot-no="cloneCtx.shotNo"
        :line-index="cloneCtx.lineIndex"
        :at-sec="cloneCtx.atSec"
        :subject="cloneCtx.subject"
        :disabled="!canEdit"
        @saved="onCloneSaved"
        @used-for-line="onCloneUsedForLine"
      />

      <div class="studio-rail">
        <RevisionRail
          :revisions="revisions.data.value ?? []"
          :active-id="selectedRevId"
          @select="(id: string) => (selectedRevId = id)"
        >
          <template #default>
            <NButton
              v-if="canEdit && dirty"
              size="small"
              secondary
              :loading="saving"
              data-testid="btn-save"
              @click="handleSave"
            >
              <template #icon><NIcon><Save :size="14" /></NIcon></template>
              保存修改
            </NButton>
            <NButton
              v-if="canEdit"
              size="small"
              quaternary
              data-testid="btn-import-plan"
              title="导入一份方案 JSON（导出包/样例文件）到当前草稿"
              @click="openImport"
            >
              导入 JSON
            </NButton>
            <NButton
              v-if="draft && !detApproved && !problems.length"
              size="small"
              type="primary"
              :loading="approving"
              :data-testid="'btn-confirm'"
              @click="handleApprove"
            >
              <template #icon><NIcon><CheckCheck :size="14" /></NIcon></template>
              确认方案
            </NButton>
            <NButton
              v-else-if="detApproved"
              size="small"
              secondary
              disabled
              :data-testid="'btn-confirmed'"
            >
              <template #icon><NIcon><Check :size="14" /></NIcon></template>
              已确认 · W3 从此版发起生成
            </NButton>
            <NButton
              v-if="latestBrief"
              size="small"
              quaternary
              :loading="generating"
              :data-testid="'btn-redirect'"
              @click="doGenerate(latestBrief.id, undefined)"
            >
              <template #icon><NIcon><RefreshCw :size="13" /></NIcon></template>
              导演再给一版
            </NButton>
          </template>
        </RevisionRail>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ref-head-ops { display: inline-flex; align-items: center; gap: 6px; }
.subj-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 6px 0 8px;
  padding: 6px 8px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface-sunken);
}
.subj-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; flex-wrap: wrap; }
.subj-check { display: inline-flex; align-items: center; }
.subj-name { font-weight: 500; }
.subj-alias { font-size: 10.5px; color: var(--wv-text-4); }
.subj-portrait { font-size: 10.5px; color: var(--wv-text-4); }
.subj-portrait.on { color: var(--wv-success, #7BC47F); }
.subj-refs { font-size: 10.5px; color: var(--wv-text-4); margin-right: auto; }
.subj-hint { margin: 2px 0 0; font-size: 11px; line-height: 1.6; }
.ref-thumb.off img { opacity: 0.35; filter: grayscale(0.7); }
.ref-check { position: absolute; top: 4px; left: 4px; z-index: 2; }
.ref-check input { width: 14px; height: 14px; }

.studio-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 1120px;
  margin: 0 auto;
  width: 100%;
}
.studio-head {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 8px;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  position: absolute;
  left: 0;
  top: 8px;
  padding: 5px 10px;
  margin-left: -10px;
  background: none;
  border: none;
  border-radius: var(--wv-radius-s);
  color: var(--wv-text-3);
  font-size: 13px;
  cursor: pointer;
}
.back:hover {
  color: var(--wv-text);
  background: var(--wv-surface);
}
.head-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}
.eyebrow-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 0.24em;
  color: var(--wv-text-4);
  text-transform: uppercase;
}
.state-tag {
  font-size: 11px;
}
.title {
  margin: 6px 0 0;
  font-size: 30px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.skel-row {
  display: flex;
  gap: 16px;
}

/* 无 Brief 空态 */
.intro {
  display: flex;
  justify-content: center;
}
.intro-card {
  width: 100%;
  max-width: 680px;
  padding: 30px 30px 26px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
}
.intro-title {
  margin: 4px 0 8px;
  font-size: 22px;
}
.intro-copy {
  margin: 0 0 22px;
  font-size: 13.5px;
  line-height: 1.9;
}

/* 导演台：标题下固定 Brief+参考图横条；下方分镜/任务/资产库/成片单列同宽卡片 */
.studio-grid {
  display: block;
}
.col-brief {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  gap: 12px;
  margin-bottom: 16px;
}
.brief-panel {
  flex: 1 1 auto;
  min-width: 260px;
  padding: 16px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
}
.brief-text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.85;
  color: var(--wv-text-2);
  max-height: 140px;
  overflow: auto;
}
.link-btn:hover,
.ghost-line:hover {
  color: var(--wv-accent-text);
  background: var(--wv-surface-raised);
}
.brief-spinner {
  display: flex;
  justify-content: center;
  padding: 6px 0;
}
@media (max-width: 900px) {
  .col-brief {
    flex-direction: column;
  }
  .refs-panel { width: auto !important; }
}
.brief-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.brief-text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.85;
  color: var(--wv-text-2);
  max-height: 140px;
  overflow: auto;
}
.link-btn,
.ghost-line {
  appearance: none;
  background: none;
  border: none;
  color: var(--wv-text-4);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 6px;
}

.col-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 16px;
}
.plan-empty {
  min-height: 240px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.plan-head {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.plan-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.rev-pill {
  font-size: 12px;
  color: var(--wv-text-3);
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  padding: 3px 10px;
  border-radius: 999px;
}
.stub-note {
  font-size: 11px;
  color: var(--wv-text-4);
}
.plan-alert {
  margin: 0;
}

/* P12：版本条固定在底部会遮住正文/操作区，改成普通流（就在页面底部），不吸底 */
.studio-rail {
  position: static;
  margin-top: 18px;
}

/* ---------- W2C 参考图 ---------- */
.refs-panel {
  width: 330px;
  flex: none;
  padding: 14px 14px 12px;
  background: var(--wv-surface);
  border: 1px dashed var(--wv-line-strong);
  border-radius: var(--wv-radius-m);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.upload-link {
  appearance: none;
  cursor: pointer;
  font-size: 12px;
  color: var(--wv-accent-text);
  background: var(--wv-accent-soft);
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 3px 10px;
}
.upload-link.busy { opacity: 0.6; pointer-events: none; }
.upload-link input { display: none; }
.refs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(64px, 1fr));
  gap: 6px;
}
.ref-thumb {
  position: relative;
  aspect-ratio: 1;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  cursor: pointer;
}
.ref-thumb img {
  width: 100%; height: 100%; object-fit: cover; display: block;
}
.ref-thumb.sel { border-color: var(--wv-accent); }
.ref-badge {
  position: absolute;
  left: 4px; bottom: 4px;
  background: rgba(11,11,10,.72);
  color: var(--wv-accent-text);
  font-size: 8px;
  letter-spacing: .08em;
  padding: 1px 4px;
  border-radius: 4px;
}
.ref-time {
  position: absolute;
  right: 4px; bottom: 4px;
  background: rgba(11,11,10,.72);
  color: var(--wv-text-4);
  font-size: 8px;
  padding: 1px 4px;
  border-radius: 4px;
}
.ref-empty { color: var(--wv-text-4); display:flex; align-items:center; justify-content:center; height:100%; font-size: 12px; }
.ref-hint { margin: 0; font-size: 11.5px; line-height: 1.7; }
.ref-count { margin: 0; font-size: 10px; color: var(--wv-accent-text); letter-spacing: .12em; }
.ref-subjects { display: flex; flex-direction: column; gap: 6px; }
.ref-subjects-title { margin: 0; font-size: 10px; letter-spacing: .1em; color: var(--wv-text-4); }
.ref-subject-block { display: flex; flex-direction: column; gap: 6px; }
.ref-subject-row { display: flex; align-items: center; gap: 8px; }
.ref-region-row { display: flex; align-items: center; gap: 6px; padding-left: 36px; }
.ref-region-label { font-size: 9px; color: var(--wv-text-4); flex: none; }
.ref-region-input { width: 52px; flex: none; text-align: center; }
.ref-op {
  appearance: none; flex: none; cursor: pointer; font-size: 11px;
  color: var(--wv-text-3); background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line); border-radius: 6px; padding: 3px 8px;
}
.ref-op:hover { border-color: var(--wv-accent); color: var(--wv-text); }
.ref-op.danger:hover { border-color: var(--wv-danger); color: var(--wv-danger); }
.ref-del {
  position: absolute; right: 3px; top: 3px; width: 16px; height: 16px; line-height: 1;
  border: none; border-radius: 50%; cursor: pointer; font-size: 12px; padding: 0;
  background: rgba(11,11,10,.72); color: var(--wv-danger);
}
.ref-del:hover { background: var(--wv-danger); color: #fff; }

/* 布局：BRIEF 一行；下方左参考图 / 右位置预览 */
.brief-row { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.ref-row { display: grid; grid-template-columns: minmax(0, 360px) minmax(0, 1fr); gap: 12px; margin-bottom: 16px; }
.refs-panel { width: auto; }
.pos-panel {
  display: flex; flex-direction: column; gap: 8px; min-width: 0;
  padding: 14px; background: var(--wv-surface);
  border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m);
}
.pos-frame {
  position: relative; width: 100%; max-height: 340px; margin: 0 auto;
  background: var(--wv-surface-sunken); border: 1px solid var(--wv-line-strong);
  border-radius: 8px; overflow: hidden; touch-action: none;
}
.pos-third { position: absolute; background: var(--wv-line); opacity: .35; }
.pos-third-v1 { left: 33.33%; top: 0; width: 1px; height: 100%; }
.pos-third-v2 { left: 66.66%; top: 0; width: 1px; height: 100%; }
.pos-third-h1 { top: 33.33%; left: 0; height: 1px; width: 100%; }
.pos-third-h2 { top: 66.66%; left: 0; height: 1px; width: 100%; }
.pos-box {
  position: absolute; border: 1.5px dashed currentColor; border-radius: 6px;
  display: flex; align-items: flex-start; cursor: move; touch-action: none;
}
.pos-label { font-size: 10px; padding: 2px 5px; letter-spacing: .06em; white-space: nowrap; }
.pos-resize {
  position: absolute; right: -5px; bottom: -5px; width: 12px; height: 12px;
  border-radius: 3px; background: currentColor; cursor: nwse-resize; opacity: .85;
}
.pos-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; margin: 0; font-size: 12px; }
.pos-unset { margin: 0; font-size: 11px; color: var(--wv-text-4); }
.pos-chip {
  display: inline-block; margin: 2px 4px 0 0; padding: 1px 6px; font-size: 10px;
  border: 1px solid var(--wv-line); border-radius: 999px; color: var(--wv-text-3);
}
@media (max-width: 900px) {
  .ref-row { grid-template-columns: 1fr; }
}
.ref-subject-thumb { width: 28px; height: 28px; object-fit: cover; border-radius: 6px; border: 1px solid var(--wv-line); flex: none; }
.ref-subject-row .text { flex: 1 1 auto; min-width: 0; }
.ref-conflict {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--wv-danger);
  background: color-mix(in srgb, var(--wv-danger) 9%, var(--wv-surface));
  border: 1px solid color-mix(in srgb, var(--wv-danger) 40%, var(--wv-line));
  border-radius: 8px;
  padding: 7px 9px;
}

/* ---------- W3 任务区 ---------- */
.jobs-panel {
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.jobs-head {
  display: flex;
  align-items: center;
  /* P12：勾选紧跟标题（原来是 space-between，勾选被推到右上角） */
  justify-content: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-latest {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--wv-text-2);
  cursor: pointer;
  user-select: none;
}
.filter-latest input {
  accent-color: var(--wv-accent, #d0a24e);
}
.jobs-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.count-inline { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--wv-text-3); }
.mini-select {
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  color: var(--wv-text-2);
  border-radius: 6px;
  font-size: 12px;
  padding: 2px 6px;
}
.state-hint { font-size: 11px; color: var(--wv-accent-text); letter-spacing: .06em; }
.job-list { display: flex; flex-direction: column; gap: 6px; }
.job-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  background: var(--wv-surface-sunken);
  border-radius: 8px;
}
.job-kind { font-size: 10px; color: var(--wv-text-4); flex: none; width: 46px; }
.job-rev {
  font-size: 10px;
  color: var(--wv-text-4);
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 999px;
  padding: 1px 7px;
  flex: none;
}
.job-rev.stale {
  color: var(--wv-danger);
  border-color: color-mix(in srgb, var(--wv-danger) 55%, var(--wv-line));
  background: color-mix(in srgb, var(--wv-danger) 10%, var(--wv-surface));
  cursor: help;
}
.job-state { font-size: 12px; color: var(--wv-text-2); flex: none; min-width: 84px; }
.job-state.running { color: var(--wv-accent-text); }
.job-state.failed { color: var(--wv-danger); }
.job-state.cancelled { color: var(--wv-text-4); }
.job-state.succeeded { color: var(--wv-success); }
.job-bar {
  flex: 1; height: 5px; border-radius: 999px;
  background: var(--wv-line);
  overflow: hidden;
}
.job-fill { display: block; height: 100%; background: var(--wv-accent); transition: width 300ms ease; }
.job-pct { font-size: 10px; color: var(--wv-text-4); width: 34px; text-align: right; flex: none; }
.job-err { color: var(--wv-danger); cursor: help; }
.job-empty { margin: 0; font-size: 12.5px; }

/* ---------- W4 资产库 ---------- */
.gallery-panel {
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.gallery-grid {
  display: flex;
  flex-direction: row;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 6px;
  scroll-snap-type: x proximity;
  -webkit-overflow-scrolling: touch;
}
.gallery-grid::-webkit-scrollbar { height: 8px; }
.gallery-grid::-webkit-scrollbar-thumb {
  background: var(--wv-line-strong);
  border-radius: 999px;
}
.gallery-grid::-webkit-scrollbar-track { background: transparent; }
.g-item {
  flex: none;
  width: 216px;
  border: 1px solid var(--wv-line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--wv-surface-sunken);
  scroll-snap-align: start;
}
.g-item img { width: 100%; aspect-ratio: 1; object-fit: cover; display: block; }
.g-loading { width: 100%; aspect-ratio: 1; display: flex; align-items: center; justify-content: center; color: var(--wv-text-4); }
.g-meta {
  display: flex; align-items: center; justify-content: space-between;
  gap: 6px; padding: 6px 8px;
}
.g-kind { font-size: 10px; color: var(--wv-text-4); }
.g-audio { width: 100%; height: 34px; display: block; }
/* 试听播放条的样式在 VideoPlanEditor.vue：本文件是 scoped，作用不到子组件内部 */
.g-actions { display: inline-flex; align-items: center; gap: 8px; }
.g-actions a { color: var(--wv-accent-text); text-decoration: none; font-size: 14px; line-height: 1; }
.g-ref {
  appearance: none; border: none; background: var(--wv-accent-soft);
  color: var(--wv-accent-text); font-size: 11px; border-radius: 6px;
  padding: 2px 8px; cursor: pointer;
}
.g-ref:disabled { opacity: .5; cursor: default; }

.g-video {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  display: block;
  background: #000;
}

/* ---------- 批量操作 / 失败任务管理 ---------- */
.batchbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 8px;
}
.batch-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 12px;
  color: var(--wv-text-3);
}
.batch-check input, .row-check input, .g-sel input {
  accent-color: var(--wv-accent);
  cursor: pointer;
}
.batch-count { font-size: 11px; color: var(--wv-text-4); }
.op {
  appearance: none;
  border: 1px solid var(--wv-line-strong);
  background: transparent;
  color: var(--wv-text-3);
  font-size: 12px;
  line-height: 1;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background var(--wv-dur) var(--wv-ease), color var(--wv-dur) var(--wv-ease);
}
.op:hover:not(:disabled) { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.primary { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); }
.op.primary:hover:not(:disabled) { background: var(--wv-accent-soft); }
.op.danger { color: #d98a78; border-color: rgba(196, 92, 74, .55); }
.op.danger:hover:not(:disabled) { background: rgba(196, 92, 74, .12); }
.op:disabled { opacity: .4; cursor: default; }
.row-check { width: 14px; flex: none; display: inline-flex; align-items: center; }

/* ---------- 资产库管理态 ---------- */
.g-item { position: relative; }
.g-item.manage { outline: 1px dashed var(--wv-line-strong); outline-offset: 2px; }
.g-item.sel { outline: 1px solid var(--wv-accent); outline-offset: 2px; }
.g-sel {
  position: absolute;
  top: 6px; left: 6px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px; height: 20px;
  border-radius: 5px;
  background: rgba(11, 11, 10, .66);
}
.g-del {
  position: absolute;
  top: 4px; right: 4px;
  z-index: 2;
  appearance: none;
  border: none;
  width: 20px; height: 20px;
  border-radius: 5px;
  background: rgba(196, 92, 74, .85);
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.g-del:disabled { opacity: .5; cursor: default; }

/* 更多分页 / 对齐 */
.panel-more {
  align-self: center;
}


/* ---------- W6 成片时间线 ---------- */
.export-panel {
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tl-rows { display: flex; flex-direction: column; gap: 6px; }
.tl-row {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 8px; background: var(--wv-surface-sunken); border-radius: 8px;
}
.tl-no { font-size: 10px; color: var(--wv-text-4); width: 64px; flex: none; }
.tl-tc { font-size: 11px; color: var(--wv-text-3); width: 108px; flex: none; font-variant-numeric: tabular-nums; }
.tl-bar { flex: 1; height: 8px; background: var(--wv-line); border-radius: 999px; overflow: hidden; }
.tl-fill { display: block; height: 100%; background: var(--wv-accent); }
.tl-state { font-size: 11px; color: var(--wv-text-3); flex: none; }
.tl-state.ok { color: var(--wv-success); }
.tl-state.empty { color: var(--wv-text-4); }
.export-hint { margin: 0; font-size: 12px; line-height: 1.7; }

/* 沉浸预览 */
.motion-form { display: flex; flex-direction: column; gap: 12px; }
.motion-ops { display: flex; justify-content: flex-end; gap: 8px; }
.g-max {
  appearance: none; border: none; background: var(--wv-accent-soft); color: var(--wv-accent-text);
  font-size: 13px; line-height: 1; padding: 3px 7px; border-radius: 6px; cursor: pointer;
}
.g-max:hover { background: color-mix(in srgb, var(--wv-accent) 22%, var(--wv-accent-soft)); }
.im-overlay {
  position: fixed; inset: 0; z-index: 80; background: rgba(8, 8, 7, 0.9);
  display: flex; align-items: center; justify-content: center; padding: 24px;
}
.im-card {
  position: relative; width: min(1080px, 100%); max-height: 92vh;
  display: flex; align-items: center; justify-content: center;
}
.im-media {
  max-width: 100%; max-height: 92vh; border-radius: 8px; display: block;
  background: #000;
}
.im-img { width: auto; }
.im-close {
  position: absolute; top: -6px; right: -6px; z-index: 2; font-size: 15px;
}
.import-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 0;
}

/* ---------------- P12：类型 Tab + 移动端适配 ---------------- */
.type-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 2px 0 6px;
}
.type-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--wv-line);
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  opacity: 0.75;
}
.type-tab:hover { opacity: 1; }
.type-tab.on {
  opacity: 1;
  border-color: color-mix(in srgb, var(--wv-accent) 60%, var(--wv-line));
  background: color-mix(in srgb, var(--wv-accent) 16%, transparent);
}
.type-tab-n { font-size: 10px; opacity: 0.7; }
.type-tab-k {
  font-size: 10px;
  opacity: 0.5;
}
.type-tab.zero { opacity: 0.42; }
.type-tab.zero.on { opacity: 0.9; }
/* Tab 行紧跟按钮行，给一点呼吸 */
.type-tabs {
  padding-top: 2px;
}
/* P12：生成按钮（含运动）固定卡片右上角、横向一行、等距 */
.jobs-head > .jobs-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.gen-live {
  flex: none;
  margin-right: 2px;
}
.jobs-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 窄屏（手机）：卡片/操作区堆叠，Tab 收紧，缩略图换行 */
@media (max-width: 720px) {
  .jobs-head,
  .jobs-head-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
  /* 窄屏：生成按钮不再右对齐，改为整行左起铺开 */
  .jobs-head > .jobs-actions {
    margin-left: 0;
    justify-content: flex-start;
    width: 100%;
  }
  .jobs-actions {
    flex-wrap: wrap;
    gap: 6px;
  }
  .type-tab {
    padding: 3px 8px;
    font-size: 11.5px;
  }
  /* 窄屏省掉 kind 尾缀，只留中文名 + 计数 */
  .type-tab-k { display: none; }
  .gallery-grid { flex-wrap: wrap; }
  /* 任务行窄屏换行，避免进度条/操作被挤出可视区 */
  .job-row {
    flex-wrap: wrap;
    row-gap: 4px;
  }
  .job-bar { min-width: 70px; }
}
</style>
