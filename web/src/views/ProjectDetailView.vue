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
import { NAlert, NButton, NDropdown, NIcon, NInput, NInputNumber, NModal, NRadioButton, NRadioGroup, NSelect, NSkeleton, NTag, useDialog, useMessage } from 'naive-ui'
import type { SelectOption } from 'naive-ui'
import { computed, h, nextTick, onErrorCaptured, ref, watch } from 'vue'
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
import { createJobs, getEngineStatus, listJobs, cancelJob, rerunJob, retryJobs, deleteJobs, JOB_STATE_LABEL } from '@/api/jobs'
import { shareProject } from '@/api/market'
import { listAssets, uploadReference, fetchAssetBlob, deleteAssets, uploadVoiceLine, useSampleAsLineVoice, deleteVoicePreset, auditionVoicePreset, assetAsPortrait, assetAsReference } from '@/api/assets'
import { createExport, fetchExportBlob, renderMaster, timecode } from '@/api/export'
import { aiGenerateLines, aiGenerateMusic, extractSubjects, patchPlanInPlace, patchSubjectMeta, portraitPromptDefaults } from '@/api/director'
import { getEngineSettings } from '@/api/engineSettings'
import { getProject, updateProjectDuration } from '@/api/projects'
import { getVideoLimits, listShotLocks, setShotLocks } from '@/api/shotLocks'
import type { AssetRef, DirectorPlan, DirectorShot, JobRecord, PlanSubject, PlanSubjectRef } from '@/api/types'
import BriefComposer from '@/components/director/BriefComposer.vue'
import ImagePlanEditor from '@/components/director/ImagePlanEditor.vue'
import RevisionRail from '@/components/director/RevisionRail.vue'
import ShotPickerDialog from '@/components/director/ShotPickerDialog.vue'
import LipsyncFacePickerDialog from '@/components/director/LipsyncFacePickerDialog.vue'
import VoiceCloneDialog from '@/components/director/VoiceCloneDialog.vue'
import VideoPlanEditor from '@/components/director/VideoPlanEditor.vue'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, modeLabel } from '@/utils/format'
import {
  SOURCE_LABEL,
  autoLayoutShot,
  canonicalJson,
  clonePlan,
  expressionRiskOf,
  isVideoPlan,
  normalizePlan,
  audioVideoHealthCheck,
  calibrateAllShots,
  modelClipCap,
  planSubjectNames,
  planTailSec,
  planProblems,
  round2,
  shotCastInfo,
  shotHasText,
  subjectMatches,
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
    // 提示词语言（项目级）：方案里存了 zh/en 就用它当默认（用户 2026-09-17 转项目时选的）
    const storedLang = (draft.value as { promptLang?: string }).promptLang
    aiLang.value = storedLang === 'en' ? 'en' : 'zh'
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
    // P13：参考图默认**全部不选中**（它们只是生成定妆的素材；要用再点选）
    refUnchecked.value = [...ids]
    // 用 canonicalJson（键序无关）：schema_json 是 jsonb，键序会被 PG 重排，
    // 直接 JSON.stringify 比对会把「纯键序差异」当成「有改动」（见 utils/plan.ts 注释）
    pristineJson.value = canonicalJson(draft.value)
    dirty.value = false
  },
  { immediate: true },
)

watch(draft, () => {
  dirty.value = draft.value !== null && canonicalJson(draft.value) !== pristineJson.value
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
      // 语言跟随项目（也可在「AI 生成提示词」弹窗里改后重新生成）
      promptLang: aiLang.value,
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

/**
 * P13：对口型「谁在哪张脸」点选（多人同框必需）。
 *
 * 为何要人工点选：LatentSync 每帧只能驱动一张脸；按定妆照做人脸识别在
 * 480p/AI 古风这类风格化素材上区分度会崩（实测同一人只有 0.2 上下、互相混淆）。
 * 用户点一下是最可靠的信号。结果写入方案 `shots[].lipsync_targets`，随任务 payload 下发。
 */
const facePickShotNo = ref<number | null>(null)
const facePickOpen = ref(false)
const facePickShot = computed(() => {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan) || facePickShotNo.value == null) return null
  return (plan.shots ?? []).find((s) => s.shot_no === facePickShotNo.value) ?? null
})
/** 该镜有台词的说话人（去重、保序） */
const facePickSpeakers = computed<string[]>(() => {
  const ns = facePickShot.value?.narrations ?? []
  const out: string[] = []
  for (const n of ns) {
    if (n.kind !== 'dialogue') continue
    const s = (n.subject ?? '').trim()
    if (s && !out.includes(s)) out.push(s)
  }
  return out
})
const facePickTargets = computed<Record<string, { x: number; y: number }>>(() =>
  facePickShot.value?.lipsync_targets ?? {},
)
/** 该镜最新的画面产物（按对口型底片：指定静帧就用静帧）—— 用来点人脸 */
const facePickNewest = computed(() => {
  if (facePickShotNo.value == null) return null
  const list = (assets.data.value ?? []).filter(
    (a) => a.shotNo === facePickShotNo.value && (a.kind === 'clip' || a.kind === 'still'),
  )
  // A：人脸要在**真正当底片的那份画面**上点，否则点的坐标和实际驱动到的帧对不上
  const want = facePickShot.value?.lipsync_source
  const prefer = want === 'still' ? 'still' : want === 'clip' ? 'clip' : ''
  const preferred = prefer ? list.filter((a) => a.kind === prefer) : []
  const clips = list.filter((a) => a.kind === 'clip')
  const pool = preferred.length ? preferred : (clips.length ? clips : list)
  return [...pool].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0] ?? null
})
const facePickMedia = computed(() => {
  const a = facePickNewest.value
  return a ? (galUrls.value[a.id] ?? '') : ''
})
function openFacePicker(shotNo: number): void {
  facePickShotNo.value = shotNo
  facePickOpen.value = true
  const a = facePickNewest.value
  // 缩略图只会在对应 Tab 被浏览时加载 → 这里补一次按需拉取（否则弹窗里是空白）
  if (a && !galUrls.value[a.id]) {
    void assetBlob(a.id).then((blob) => {
      if (blob) galUrls.value[a.id] = URL.createObjectURL(blob)
    })
  }
}
/** 保存某说话人的点选坐标（归一化）；写入方案后立即就地方保存 */
async function onFacePick(p: { subject: string; x: number; y: number }): Promise<void> {
  const shot = facePickShot.value
  if (!shot) return
  const t = { ...(shot.lipsync_targets ?? {}) }
  t[p.subject] = { x: p.x, y: p.y }
  shot.lipsync_targets = t
  await savePlanInPlace()
  message.success(`已记录「${p.subject}」在第 ${shot.shot_no} 镜的人脸位置`)
}
async function onFaceClear(subject: string): Promise<void> {
  const shot = facePickShot.value
  if (!shot || !shot.lipsync_targets) return
  const t = { ...shot.lipsync_targets }
  delete t[subject]
  shot.lipsync_targets = t
  await savePlanInPlace()
  message.info(`已清除「${subject}」的人脸位置`)
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
/** 未勾选 = 不作为定妆照的参考（2026-09-16 用户裁定：参考图只留「勾选 / 删除」两态，没有“变暗”中间态） */
const refUnchecked = ref<string[]>([])
/** 正在「从资产复制成参考图」的资产 id（按钮 loading） */
const refBusy = ref('')
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
  // 定妆图判定：接口现在会回 subject / snapshotKind（新数据 kind=portrait；老数据 kind 被写成 still，
  // 靠 snapshotKind 兜住），因此已生成的定妆图能被「选图」正确识别
  return (assets.data.value ?? [])
    .filter((a) => (a.kind === 'portrait' || a.snapshotKind === 'portrait') && a.subject === name)
    .map((a) => ({ id: a.id, url: galUrls.value[a.id], width: a.width, height: a.height }))
}
/** 一键生成主体（LLM 抽取；已有主体保留，只补新的） */
async function onExtractSubjects(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await savePlanInPlace())) return
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
/** 主体操作菜单：生成/换一版 · 选图 · 设定 · 删除 */
function subjectActions(sub: PlanSubject): Array<{ label: string; key: string; disabled?: boolean }> {
  return [
    { label: sub.portraitAssetId ? '换一版定妆照' : '生成定妆照', key: 'gen' },
    { label: sub.portraitAssetId ? '主体设定（性别/年龄/体态…）' : '主体设定（性别/年龄/体态…）', key: 'traits' },
    { label: '把最新定妆照设为锚定图', key: 'pick', disabled: !portraitsOf(sub.name).length },
    { label: '把所选参考图设为定妆照', key: 'useRef', disabled: portraitRefCandidates().length === 0 },
    { label: '管理别名…', key: 'alias' },
    { label: '删除该主体', key: 'del' },
  ]
}
function onSubjectAction(key: string, name: string): void {
  if (key === 'gen') void openPortraitDialog(name)
  else if (key === 'traits') openTraits(name)
  else if (key === 'pick') pickPortrait(name)
  else if (key === 'del') removeSubject(name)
  else if (key === 'alias') openAlias(name)
  else if (key === 'useRef') useRefAsPortrait(name)
}

/* ================= P14 主体设定（人物档案）================= */
/**
 * 为什么要让用户填（2026-09-16 用户实测）：
 * 「宝玉」被关键帧画成了**女性** —— 根因是主体只有名字没有属性，LLM 与视觉模型只能靠名字猜性别。
 * 这份档案会同时进三处：①导演/重写提示词的 user 消息；②出图正词里的「Picture N = 主体[档案]」；③定妆照提示词。
 * 性别是最关键的一项：定妆照画错性别，后面每一镜都跟着错。
 */
const traitsOpen = ref(false)
const traitsSubject = ref('')
const traitsKind = ref('person')
const traitsDraft = ref<{
  gender: 'male' | 'female' | 'other' | ''
  age: string
  height: string
  build: string
  personality: string
  appearance: string
}>({ gender: '', age: '', height: '', build: '', personality: '', appearance: '' })
const traitsBusy = ref(false)

function openTraits(name: string): void {
  const sub = planSubjects().find((x) => x.name === name)
  if (!sub) return
  traitsSubject.value = name
  traitsKind.value = sub.kind ?? 'person'
  traitsDraft.value = {
    gender: (sub.gender as 'male' | 'female' | 'other' | '') ?? '',
    age: sub.age ?? '',
    height: sub.height ?? '',
    build: sub.build ?? '',
    personality: sub.personality ?? '',
    appearance: sub.appearance ?? '',
  }
  traitsOpen.value = true
}

async function confirmTraits(): Promise<void> {
  const name = traitsSubject.value
  const d = traitsDraft.value
  if (traitsKind.value === 'person' && !d.gender) {
    const ok = window.confirm(
      `「${name}」还没填性别。\n\n`
      + '不填的话，模型只能靠名字猜 —— 实测出现过把男性角色画成女性的问题。\n'
      + '确定要留空吗？（可稍后再补）',
    )
    if (!ok) return
  }
  traitsBusy.value = true
  try {
    setPlanSubjects(planSubjects().map((x) => (x.name === name
      ? { ...x, gender: d.gender, age: d.age.trim(), height: d.height.trim(), build: d.build.trim(),
          personality: d.personality.trim(), appearance: d.appearance.trim() }
      : x)))
    await saveSubjectMeta()
    traitsOpen.value = false
    const filled = d.gender || d.age || d.build || d.appearance
    message.success(filled
      ? `已保存「${name}」的主体设定（已就地保存，无需重新确认）`
      : `已清空「${name}」的主体设定`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存主体设定失败')
  } finally {
    traitsBusy.value = false
  }
}

/** 主体档案一行摘要（列表用）；空则返回空串 */
function traitsSummary(sub: PlanSubject): string {
  const g = sub.gender === 'male' ? '男' : sub.gender === 'female' ? '女' : sub.gender === 'other' ? '性别未定' : ''
  // ★ 2026-09-16 夜：体态（build）单独一个 chip 显示（用户反馈“列表里看不到体态”），
  //   这里就不再拿 build 顶替 height —— 否则「体态」会被身高盖住看不到。
  return [g, sub.age, sub.height].filter((x) => x && String(x).trim()).join(' · ')
}
/** 人物主体缺性别 → 列表上给个红色提醒（就是「宝玉被当女性」的根因） */
function traitsWarn(sub: PlanSubject): boolean {
  return (sub.kind ?? 'person') === 'person' && !sub.gender
}

/* ================= P13b 生成定妆图弹框（正/负向提示词可改） ================= */
/**
 * 为什么要弹框（2026-09-16 用户要求）：定妆图是**所有分镜的唯一身份锚定**，
 * 之前只能用后端写死的模板生成（「标准角色设定图：xxx 正面半身、纯色背景…」），
 * 用户想控（换背景色/换角度/换风格）只能改代码。现在与分镜的「AI 更新提示词」一致：
 * 弹框预填默认模板 → 用户改 → 用改后的提示词 + 参考图出图。
 */
const portraitOpen = ref(false)
const portraitSubject = ref('')
const portraitRefIds = ref<string[]>([])
const portraitPositive = ref('')
const portraitNegative = ref('')
const portraitPromptBusy = ref(false)
const portraitGenBusy = ref(false)

async function openPortraitDialog(name: string): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await savePlanInPlace())) return
  const picked = portraitRefCandidates()
  portraitSubject.value = name
  portraitRefIds.value = picked
  portraitPositive.value = ''
  portraitNegative.value = ''
  portraitOpen.value = true
  await loadPortraitPrompt()
}

/**
 * 定妆照弹框里的「本次会用哪几张参考图」说明（2026-09-16 夜）。
 *
 * 为什么要写清楚：用户实测「生成的定妆照没照我选定的参考图」—— 因为界面以前只写了个张数，
 * 而且“未勾选（变暗）”的图也会被送进模型（bug 已修）。现在只认勾选的，所以这里要说准：
 * 0 张时会回退到「当前定妆照」（后端行为），不是“纯文本生成”。
 */
const portraitRefHint = computed<string>(() => {
  const n = portraitRefIds.value.length
  if (n > 0) {
    return `参考图 ${n} 张（已勾选的）`
  }
  const sub = planSubjects().find((x) => x.name === portraitSubject.value)
  return sub?.portraitAssetId
    ? '参考图 0 张 → 将用「当前定妆照」换一版（不改身份，只重生成）；想按素材图生成，先在参考图格子上勾选它'
    : '参考图 0 张 → 零参考会退化为纯文生图、容易出噪声图/画得不像（仍可生成，会先确认；建议先勾选一张素材图）'
})

/**
 * 定妆照参考图提示（用户 2026-09-17：**不再硬拦**，只提示）——把原来的"能不能生成"改成"建不建议生成"。
 * 零参考时会退化为纯文生图（实测可能出噪声图），所以点击时仍要二次确认。
 */
const portraitNoRef = computed<boolean>(() => {
  if (portraitRefIds.value.length > 0) return false
  const sub = planSubjects().find((x) => x.name === portraitSubject.value)
  return !sub?.portraitAssetId
})

/** 拉取默认正/负向词（真源在后端 SubjectPrompts；拉不到就用本地兜底） */
async function loadPortraitPrompt(): Promise<void> {
  const name = portraitSubject.value
  const revId = genRevisionId()
  portraitPromptBusy.value = true
  try {
    if (!revId) throw new Error('no revision')
    const d = await portraitPromptDefaults(workspaceId.value, projectId.value, revId, name,
      portraitRefIds.value.length)
    portraitPositive.value = d.positivePrompt
    portraitNegative.value = d.negativePrompt
  } catch {
    const sub = planSubjects().find((x) => x.name === name)
    const kind = sub?.kind ?? 'person'
    const tail = kind === 'person'
      ? `${name} 正面半身、中性表情、纯色背景、全身服装与配饰清晰可辨、柔和均匀布光、写实电影质感；严格保持参考图的人物特征（五官/发型/服装/年龄感）。只画这一个角色，不要文字、不要边框、不要多人物。`
      : `${name} 标准设定图：主体居中、纯色背景、均匀布光、细节清晰；严格保持参考图的外形/材质/颜色。不要文字、不要边框。`
    portraitPositive.value = `标准角色设定图：${tail}`
    portraitNegative.value = 'text, watermark, logo, subtitle, multiple people, deformed face, extra limbs, lowres, blurry, 3d render, cgi'
  } finally {
    portraitPromptBusy.value = false
  }
}

/** 弹框里确认 → 用「用户改过的提示词 + 当前参考图」出定妆图 */
async function confirmPortrait(): Promise<void> {
  const revId = genRevisionId()
  const name = portraitSubject.value
  if (!revId || !name) return
  const pos = portraitPositive.value.trim()
  if (!pos) {
    message.warning('正向提示词不能为空')
    return
  }
  if (!portraitRefIds.value.length) {
    const ok = window.confirm(
      '这张定妆照没有任何参考图（零参考）：会退化成纯文生图，实测可能出噪声图/画得不像。\n' +
        '建议先在参考图格子上勾选一张素材图。\n\n仍然继续生成？',
    )
    if (!ok) return
  }
  portraitGenBusy.value = true
  portraitBusy.value = true
  try {
    const pickedIds = [...portraitRefIds.value]
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'portrait',
      subject: name,
      refAssetIds: pickedIds,
      positivePrompt: pos,
      negativePrompt: portraitNegative.value.trim(),
    } as never)
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建定妆图任务')
    portraitOpen.value = false
    message.info(`「${name}」定妆图合成中…（参考图 ${pickedIds.length} 张：${pickedIds.map((i) => '#' + i.slice(-4)).join(' ') || '无'}）`)
    const job = await waitJobDone(jobId, 600000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    await refreshGallery()
    // 参考图只是“生成定妆的素材”：用完**自动取消勾选**（不删图、不删主体标注；
    // 按用户裁定参考图只留「勾选/删除」两态，系统不替用户做“移除”）
    const used = new Set<string>(pickedIds)
    const sub = planSubjects().find((x) => x.name === name)
    for (const r of sub?.refs ?? []) used.add(r.assetId)
    for (const id of used) setRefChecked(id, false)
    syncReferenceAssets()
    message.success(`「${name}」定妆图已生成（用过的参考图已自动取消勾选）—— 用「操作 ▾ → 把最新定妆照设为锚定图」`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成定妆图失败')
  } finally {
    portraitGenBusy.value = false
    portraitBusy.value = false
  }
}

/* ================= 新增剧情主体（用户要求：剧情主体要有「+ 新增主体」） ================= */
const addSubjOpen = ref(false)
const addSubjName = ref('')
const addSubjKind = ref<'person' | 'vehicle' | 'object' | 'scene'>('person')
const addSubjAliases = ref('')
const addSubjBusy = ref(false)

function openAddSubject(): void {
  addSubjName.value = ''
  addSubjKind.value = 'person'
  addSubjAliases.value = ''
  addSubjOpen.value = true
}

async function confirmAddSubject(): Promise<void> {
  const name = addSubjName.value.trim()
  if (!name) {
    message.warning('请填写主体名（如：宝玉 / 赤兔马 / 通灵宝玉）')
    return
  }
  if (planSubjects().some((s) => s.name === name)) {
    message.warning(`已有同名主体「${name}」`)
    return
  }
  const aliases = addSubjAliases.value
    .split(/[,，、;；\s]+/).map((x) => x.trim()).filter((x) => x && x !== name)
  addSubjBusy.value = true
  try {
    setPlanSubjects([
      ...planSubjects(),
      { name, kind: addSubjKind.value, aliases, enabled: true, locked: false, refs: [],
        portraitAssetId: '', portraitVersion: 0, region: null },
    ])
    await saveSubjectMeta()
    addSubjOpen.value = false
    message.success(`已新增剧情主体「${name}」（已就地保存）。下一步：用「操作 ▾ → 生成定妆照」给它出定妆图`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '新增主体失败')
  } finally {
    addSubjBusy.value = false
  }
}

/** 别名管理：LLM 抽的别名可能张冠李戴（如把「浅蔷薇色纱衣女子」当成秦可卿），要能删 */
const aliasOpen = ref(false)
const aliasSubject = ref('')
const aliasDraft = ref('')
function openAlias(name: string): void {
  aliasSubject.value = name
  aliasDraft.value = ''
  aliasOpen.value = true
}
function removeAlias(name: string, alias: string): void {
  const subs = planSubjects().map((x) =>
    x.name === name ? { ...x, aliases: (x.aliases ?? []).filter((a) => a !== alias) } : x,
  )
  setPlanSubjects(subs)
  void saveSubjectMeta()
  message.success(`已删除「${name}」的别名「${alias}」（已就地保存，无需确认）`)
}
function addAlias(name: string): void {
  const v = aliasDraft.value.trim()
  if (!v) return
  const subs = planSubjects().map((x) =>
    x.name === name ? { ...x, aliases: Array.from(new Set([...(x.aliases ?? []), v])) } : x,
  )
  setPlanSubjects(subs)
  void saveSubjectMeta()
  aliasDraft.value = ''
  message.success(`已为「${name}」添加别名「${v}」（已就地保存，无需确认）`)
}

/** 剧情主体搜索（按名字 / 别名模糊过滤） */
const subjectQuery = ref('')
/**
 * 主体列表排序（2026-09-16 夜用户要求）：① **选定的（勾选＝参与）排最上**；② 其余按名字**拼音首字母**。
 *
 * 为什么用 `Intl.Collator('zh-Hans-CN')` 而不再引一个拼音库：zh 的默认排序规则就是拼音
 * （实测 宝玉 < 警幻 < 可卿 < 林黛玉 < 迷津 < 太虚 < 袭人 = bao/jing/ke/lin/mi/tai/xi），
 * 浏览器与 Node 都支持，零依赖。
 */
const SUBJECT_COLLATOR = new Intl.Collator('zh-Hans-CN')

function compareSubjects(a: PlanSubject, b: PlanSubject): number {
  const pin = Number(!!b.enabled) - Number(!!a.enabled)   // 选定的在前
  return pin !== 0 ? pin : SUBJECT_COLLATOR.compare(a.name, b.name)
}

function filteredSubjects(): PlanSubject[] {
  const q = subjectQuery.value.trim().toLowerCase()
  const all = planSubjects()
  const list = !q ? [...all] : all.filter(
    (s) => s.name.toLowerCase().includes(q) || (s.aliases ?? []).some((a) => a.toLowerCase().includes(q)),
  )
  return list.sort(compareSubjects)
}

/**
 * 定妆照的参考图候选 = **方案里所有勾选的参考图**。
 *
 * ★ 2026-09-16 用户裁定（重要，别再改回去）：
 *   ① 定妆的参考图**只看勾选没勾选，与命名/主体标注无关** —— 勾了就算，没勾就不算；
 *      所以这里**不再**按名字（本名/别称/未命名）筛，也不再关心“这张属于哪个主体”。
 *   ② 关键帧（出图）的参考图**只认绑定的定妆照**（`subjects[].portraitAssetId`，§7.5.1），
 *      参考图不参与出图锚定 —— 两条路职责分开。
 *
 * 历史教训：改之前既看“在不在列表里”又忽略勾选状态 → 用户勾了 A、结果照了未勾选的 B
 * （人脸探针实测 A 已勾选=0.106 / B 未勾选=0.759）。
 */
function portraitRefCandidates(): string[] {
  return refSelected.value.filter((id) => !refUnchecked.value.includes(id))
}
/**
 * 把所选参考图设为该主体的定妆照。
 *
 * ★ 2026-09-16 改（用户提案）：先在**资产库里物化出一张真正的定妆照**（kind=portrait，
 * 后端复制字节成独立资产），再让方案绑定**它** —— 而不是像以前那样把 portraitAssetId
 * 直接指向 kind=reference 的素材图。好处：资产库分类/按 kind 扫描/删素材连坐都不用再"宽容判断"。
 *
 * ⚠️ 语义提醒：这只是让数据的"身份"变正确，并**不会**把它变成标准角色设定图 ——
 * 多角色同框时参考集样式不统一照样串脸，所以界面同时保留了「生成定妆照」的推荐。
 *
 * ★ 2026-09-16 夜：候选口径改成“所有勾选的参考图”（与命名无关）→ 这里取第一张；
 *   多张勾选时请只保留想要的那张再点本项。
 */
async function useRefAsPortrait(name: string): Promise<void> {
  const cand = portraitRefCandidates()
  if (!cand.length) {
    message.warning('先在参考图格子上勾选一张图，再设为定妆照')
    return
  }
  const srcId = cand[0]
  const ver = (planSubjects().find((x) => x.name === name)?.portraitVersion ?? 0) + 1
  portraitBusy.value = true
  try {
    const made = await assetAsPortrait(workspaceId.value, projectId.value, srcId, name, ver)
    setPlanSubjects(planSubjects().map((x) => (x.name === name
      ? { ...x, portraitAssetId: made.id, portraitVersion: ver }
      : x)))
    void queryClient.invalidateQueries({ queryKey: ['assets'] })
    // 与「生成定妆照」一致：用完只**取消勾选**（参考图仍在列表里，主体标注也保留）
    setRefChecked(srcId, false)
    void saveSubjectMeta()
    message.success(
      `已把所选参考图复制为「${name}」的定妆照（v${ver} ·#${made.id.slice(-6)}，已就地保存）。`
      + '注：这是复制件，不是重新生成的设定图 —— 多角色同框想更稳，建议点「生成定妆照」。',
    )
  } catch (e) {
    message.error(e instanceof Error ? e.message : '设为定妆照失败')
  } finally {
    portraitBusy.value = false
  }
}

/** 删除主体：连同它在参考图上的主体标记一起清掉（否则会残留成无主参考图） */
function removeSubject(name: string): void {
  const target = planSubjects().find((s) => s.name === name)
  const ids = new Set<string>(Object.keys(refSubjects.value).filter((id) => (refSubjects.value[id] ?? '').trim() === name))
  for (const r of target?.refs ?? []) ids.add(r.assetId)
  for (const id of ids) {
    delete refSubjects.value[id]
    delete refRegions.value[id]
    refSelected.value = refSelected.value.filter((x) => x !== id)
  }
  pruneUnchecked()
  setPlanSubjects(planSubjects().filter((s) => s.name !== name))
  message.success(`已删除主体「${name}」（参考图仍留在图库里，可重新勾选并命名）`)
}

/**
 * P13：主体元数据（别名 / 参与勾选）**就地保存**：直接改当前版本，不另存 vN+1、不需要重新确认。
 * 只提交这两类字段，后端也只改这两类（不影响 prompt/分镜/台词等生成相关字段）。
 */
async function saveSubjectMeta(): Promise<void> {
  const revId = genRevisionId()
  const subs = planSubjects()
  if (!revId || !subs.length) return
  try {
    await patchSubjectMeta(workspaceId.value, projectId.value, revId,
      subs.map((x) => ({
        name: x.name,
        aliases: x.aliases ?? [],
        enabled: x.enabled !== false,
        portraitAssetId: x.portraitAssetId ?? '',
        portraitVersion: x.portraitVersion ?? 0,
        // ★ P14：人物档案随元数据一起就地保存（hasTraits=true → 后端整份替换，允许清空）
        hasTraits: true,
        gender: x.gender ?? '',
        age: x.age ?? '',
        height: x.height ?? '',
        build: x.build ?? '',
        personality: x.personality ?? '',
        appearance: x.appearance ?? '',
      })))
    // 元数据已落库 → 不置脏（避免又要求“保存 + 确认”）
    metaSyncedAt.value = Date.now()
    message.info('主体元数据已就地保存（别名/勾选/定妆照，无需重新确认）')
  } catch (e) {
    message.warning(e instanceof Error ? e.message : '元数据保存失败（不影响其它改动）')
  }
}

/** 主体参与锚定开关 */
function toggleSubject(name: string, on: boolean): void {
  setPlanSubjects(planSubjects().map((s) => (s.name === name ? { ...s, enabled: on } : s)))
  void saveSubjectMeta()
}

/**
 * 【已废弃】生成该主体的定妆图旧入口（2026-09-16 改为弹框 {@link openPortraitDialog}）：
 * 定妆图也要像分镜一样弹出正/负向提示词让用户改，再用「参考图 + 用户改过的提示词」出图。
 */
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

/**
 * 标注名 → 方案里的**规范主体名**（本名优先；命中别称/简称都归到本名）。
 *
 * 为什么必须做（2026-09-16 用户实测第 4 镜一致性又崩）：参考图面板里把某张图标成**别称**
 * （如主体本名「宝玉」、图上写「宝二爷」）时，旧逻辑按标注名分组，会**新建一个叫「宝二爷」的主体**：
 * 它带素材图但**没有定妆照**，而真主体「宝玉」的 refs 被清空 → 生成时别名主体找不到定妆照
 * （主主体直接报错、非主主体被静默剔除）→ 那一镜丢身份锚定、人物对不上参考图。
 * 找不到对应主体时原样返回（允许新建主体）。
 */
function canonicalSubjectName(raw: string, subs: PlanSubject[]): string {
  const key = (raw ?? '').trim()
  if (!key) return ''
  const exact = subs.find((s) => s.name === key)
  if (exact) return exact.name
  const hit = subs.find(
    (s) =>
      (s.aliases ?? []).includes(key)
      || subjectMatches(key, s.name)
      || (s.aliases ?? []).some((al) => subjectMatches(key, al)),
  )
  return hit ? hit.name : key
}

function syncReferenceAssets(): void {
  if (!draft.value) return
  const refs = buildRefAssets()
  ;(draft.value as unknown as { referenceAssets?: unknown }).referenceAssets = refs
  // P13：按主体聚合写回 subjects[]（勾选状态与定妆图一起落库）
  const prev = planSubjects()
  const byName = new Map(prev.map((s) => [s.name, s]))
  const grouped = new Map<string, PlanSubjectRef[]>()
  /** 本次按别称标注出来的名字 → 追加到该主体的 aliases（下次镜文本写别称也能自动绑定） */
  const aliasAdd = new Map<string, string[]>()
  for (const r of refs) {
    // 未标主体名的图**不创建“（未命名）”主体**（只留在 referenceAssets 里），
    // 否则随手选一张图就会多出一个空名主体（实测踩过）
    const typed = (r.subject ?? '').trim()
    if (!typed) {
      continue
    }
    const key = canonicalSubjectName(typed, prev)
    if (key !== typed) {
      aliasAdd.set(key, [...(aliasAdd.get(key) ?? []), typed])
    }
    grouped.set(key, [...(grouped.get(key) ?? []), { assetId: r.assetId, checked: !refUnchecked.value.includes(r.assetId), region: r.region ?? null }])
  }
  const out: PlanSubject[] = []
  for (const [name, refsOf] of grouped) {
    const old = byName.get(name)
    const extra = (aliasAdd.get(name) ?? []).filter((x) => x !== name && !(old?.aliases ?? []).includes(x))
    out.push({ name, kind: old?.kind ?? 'person', aliases: [...(old?.aliases ?? []), ...extra], enabled: old?.enabled ?? true, locked: old?.locked ?? false, refs: refsOf, portraitAssetId: old?.portraitAssetId ?? '', portraitVersion: old?.portraitVersion ?? 0, region: old?.region ?? null,
      // ★ P14：人物档案不能在这里被抹掉（syncReferenceAssets 每次改参考图都会重建 subjects）
      gender: old?.gender ?? '', age: old?.age ?? '', height: old?.height ?? '', build: old?.build ?? '',
      personality: old?.personality ?? '', appearance: old?.appearance ?? '' })
    byName.delete(name)
  }
  // 没有素材图但有定妆图/别名的主体也要保留（否则一键抽取的结果会丢）
  for (const s of byName.values()) out.push({ ...s, refs: [] })
  ;(draft.value as unknown as { subjects?: PlanSubject[] }).subjects = out
}
/** 勾选/取消勾选某张参考图（**唯一**的参与开关：定妆照的参考输入只看勾选） */
function toggleRefChecked(id: string, on: boolean): void {
  refUnchecked.value = on ? refUnchecked.value.filter((x) => x !== id) : [...new Set([...refUnchecked.value, id])]
  syncReferenceAssets()
}

/**
 * 参考图格子的点击行为（2026-09-16 夜用户要求：只留「勾选 / 删除」两态）。
 *
 * 点击图片 = 切「勾选」（不再“再点一下就移出”——以前那样用户分不清“加入”和“取消”）；
 * **移出**只留右上角 × 按钮；缩略图**不再变暗**（取消勾选只靠复选框本身表达）。
 */
function toggleRefTile(id: string): void {
  toggleRefChecked(id, refUnchecked.value.includes(id))
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
/**
 * 主体名 → 它**实际用于锚定的那张图**是什么（位置总控的主体表上显示）。
 *
 * 为什么必须显示（2026-09-16 实测第 4 镜串脸）：方案里 `portraitAssetId` 可以指向一张
 * **上传的素材图**（kind=reference）而不是生成出来的定妆照 —— 后端会尊重这个绑定（用户显式指定），
 * 但多角色同框时这种「身份不明」的图极易串脸，而界面上完全看不出来。所以这里标明 kind 并高亮告警。
 */
function subjectAnchorInfo(name: string): { label: string; warn: boolean; title: string } {
  const sub = planSubjects().find((x) => x.name === name)
  const pid = (sub?.portraitAssetId ?? '').trim()
  if (!pid) {
    return { label: '未定妆', warn: true, title: '没有绑定定妆照：该主体出镜时会用不上身份锚定（或被后端剔除）' }
  }
  const asset = assetById.value[pid]
  const suffix = `#${pid.slice(-6)}`
  if (!asset) {
    return { label: `锚定图 ${suffix}`, warn: true, title: `资产 ${pid} 不在当前资产列表里（可能已删除或在别的项目）` }
  }
  const isPortrait = asset.kind === 'portrait' || asset.snapshotKind === 'portrait'
  return isPortrait
    ? { label: `定妆照 ${suffix}`, warn: false, title: `用于身份锚定：定妆照资产 ${pid}` }
    : {
        label: `素材图（非定妆照）${suffix}`,
        warn: true,
        title: `这个主体实际用的是**上传素材图**（kind=${asset.kind}），不是生成的定妆照 —— `
          + '多角色同框时容易串脸。建议到「主体」面板生成/重新绑定定妆照。',
      }
}

/** 主体名 → 定妆照缩略图（位置总控的主体表用；老代码传 PlanSubject，这里按名查更顺手） */
function subjectThumbByName(name: string): string {
  const sub = planSubjects().find((s) => s.name === name)
  return sub ? subjectThumb(sub) : ''
}
/** 该主体显示的图：定妆照优先，其次第一张勾选素材图 */
/** 已选主体里显示的图 = **只显示定妆照**；图片未加载时按需拉取（否则会误显示「未定妆」） */
function subjectThumb(sub: PlanSubject): string {
  const id = sub.portraitAssetId
  if (!id) return ''
  const cached = galUrls.value[id] ?? thumbUrls.value[id]
  if (cached) return cached
  // 定妆照可能是 kind=reference（把参考图直接设为定妆照）或旧数据 kind=still，
  // 而缩略图只会在对应 Tab 被浏览时加载 → 这里补一次按需加载
  if (!loadTried.value.includes(id)) {
    loadTried.value = [...loadTried.value, id]
    void assetBlob(id).then((blob) => {
      if (blob) galUrls.value[id] = URL.createObjectURL(blob)
    })
  }
  return ''
}

/** 已按需加载过的资产 id（防止重复请求） */
const loadTried = ref<string[]>([])
/** 归一化 region（0–1）→ 百分比字符串 */
function pctOf(r: { x: number; y: number; w: number; h: number }, k: 'x' | 'y' | 'w' | 'h'): string {
  const v = r[k]
  return Number.isFinite(v) ? String(Math.round(v * 100)) : ''
}
// ================= P5 位置总控（作用范围：方案默认 / 第 N 镜 / 第 N 镜·帧 M） =================
/**
 * 一个镜可能有多个（运镜）帧，每帧可以各摆各的位置，一帧里可有一个或多个主体框。
 * 所以位置编辑必须能指定「改到哪一层」，三层优先级：
 *   帧级 `shots[].keyframes[j].layout` &gt; 镜级 `shots[].layout` &gt; 方案默认 `subjects[].region`
 *
 * 顶部这张卡是**唯一总控**（分镜卡只留一个「在顶部编辑」快捷入口，A 方案）。
 * 条目一律按**主体**分组（不是按参考图）—— 主体可能只有定妆照、没有任何素材图。
 */
type PosScope =
  | { kind: 'plan' }
  | { kind: 'shot'; shotNo: number }
  | { kind: 'frame'; shotNo: number; index: number }
  /** ★ 2026-09-16（用户要求）：作用范围还能直接选「定妆照 · 某主体」——只看/只改这个主体的
   *  全片默认位置（写 `subjects[].region`），预览框里直接展示它的定妆照。 */
  | { kind: 'portrait'; subject: string }
const posScope = ref<PosScope>({ kind: 'plan' })
const posPanelRef = ref<HTMLElement | null>(null)

const posShotList = computed<DirectorShot[]>(() => {
  const d = draft.value
  return d && isVideoPlan(d) ? (d.shots ?? []) : []
})
function shotOfNo(no: number): DirectorShot | undefined {
  return posShotList.value.find((s) => s.shot_no === no)
}
function posShotLabel(s: DirectorShot, i: number): string {
  const kf = (s.keyframes ?? [])[i]
  return `第 ${s.shot_no} 镜 · 帧 ${i + 1}${kf?.label ? ` ${kf.label}` : ''}`
}
/** 作用范围下拉（可搜索：输入「4」「帧 2」「定妆」「宝玉」都能命中） */
const posScopeOptions = computed(() => {
  const out: Array<{ value: string; label: string; keywords: string }> = [
    { value: 'plan', label: '方案默认（全片所有镜）', keywords: 'plan 方案 默认 全片 all' },
  ]
  for (const s of posShotList.value) {
    out.push({ value: `shot:${s.shot_no}`, label: `第 ${s.shot_no} 镜（整镜默认）`, keywords: `shot ${s.shot_no} 镜 整镜` })
    ;(s.keyframes ?? []).forEach((kf, i) => {
      out.push({
        value: `frame:${s.shot_no}:${i}`,
        label: posShotLabel(s, i),
        keywords: `frame ${s.shot_no} ${i + 1} 帧 ${kf.label ?? ''}`,
      })
    })
  }
  // 定妆照（放最后，但可搜索：输入「定妆」或主体名就直接定位）
  for (const sub of planSubjects()) {
    out.push({
      value: `portrait:${sub.name}`,
      label: `定妆照 · ${sub.name}${sub.portraitAssetId ? '' : '（未定妆）'}`,
      keywords: `定妆照 portrait subject ${sub.name} ${(sub.aliases ?? []).join(' ')}`,
    })
  }
  return out
})
/** 作用范围下拉的搜索：同时匹配 label 与 keywords（naive 默认只匹配 label）
 *  —— 用户输入「4」「帧 2」「定妆」「宝玉」「可卿」都能命中。
 *  签名必须用 naive 的 SelectOption（NSelect 的 filter 是强类型的，用自定义窄类型 vue-tsc 会报错）。 */
function filterPosScope(pattern: string, option: SelectOption): boolean {
  const q = (pattern || '').trim().toLowerCase()
  if (!q) return true
  const label = typeof option.label === 'string' ? option.label : ''
  const keywords = typeof option.keywords === 'string' ? option.keywords : ''
  return `${label} ${keywords}`.toLowerCase().includes(q)
}
const posScopeValue = computed<string>(() => {
  const s = posScope.value
  return s.kind === 'plan'
    ? 'plan'
    : s.kind === 'shot'
      ? `shot:${s.shotNo}`
      : s.kind === 'frame'
        ? `frame:${s.shotNo}:${s.index}`
        : `portrait:${s.subject}`
})
function setPosScope(v: string): void {
  if (!v || v === 'plan') {
    posScope.value = { kind: 'plan' }
    return
  }
  const parts = v.split(':')
  if (parts[0] === 'portrait') {
    const subject = v.slice('portrait:'.length)
    if (!subject) return
    posScope.value = { kind: 'portrait', subject }
    // 定妆照作用域：没有方案级位置时先给一个居中默认框，否则预览框里什么都看不到、也无从拖
    // （用户要的是“选定妆照的时候直接展示定妆照”）
    ensurePortraitScopeBox(subject)
    return
  }
  const no = Number(parts[1])
  if (!Number.isFinite(no)) return
  posScope.value =
    parts[0] === 'frame' ? { kind: 'frame', shotNo: no, index: Number(parts[2]) || 0 } : { kind: 'shot', shotNo: no }
  maybeAutoLayoutForScope()
}
/**
 * ★ 2026-09-16（用户要求）：切到某一镜/某一帧时，如果**该镜还没设过位置**，就把涉及主体的
 * 定妆照按主体顺序**自动均分**到框里；已经改过则不自动分配，直接展示之前的结果。
 */
function maybeAutoLayoutForScope(): void {
  const k = posScope.value.kind
  if (k !== 'shot' && k !== 'frame') return
  const names = posSubjects.value
  if (names.length < 2) return          // 0~1 个主体没有均分可言
  if (names.some((n) => posOwnBox(n))) return   // 本层已有位置 → 保留用户改过的结果
  autoLayoutRegions(true)
}
/** 定妆照作用域：无方案级位置则给一个居中默认框（宽 0.32 / 高 0.7，符合半身像比例） */
function ensurePortraitScopeBox(subject: string): void {
  if (posOwnBox(subject)) return
  posWrite(subject, { x: 0.34, y: 0.15, w: 0.32, h: 0.7 })
  message.info(`已给「${subject}」一个居中默认位置（可拖动/微调；也可在下方「区域%」直接填）`)
}
const posScopeLabel = computed(() => {
  const s = posScope.value
  if (s.kind === 'plan') return '方案默认（全片）'
  if (s.kind === 'portrait') return `定妆照 · ${s.subject}`
  const shot = shotOfNo(s.shotNo)
  if (s.kind === 'shot') return `第 ${s.shotNo} 镜`
  return shot ? posShotLabel(shot, s.index) : `第 ${s.shotNo} 镜 · 帧 ${s.index + 1}`
})

/** 当前范围要摆位置的主体（方案默认 = 全部启用主体；定妆照 = 该主体；镜/帧 = 该镜出镜主体） */
const posSubjects = computed<string[]>(() => {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return []
  const all = planSubjectNames(d)
  const sc = posScope.value
  if (sc.kind === 'plan') return all
  if (sc.kind === 'portrait') {
    return all.includes(sc.subject) ? [sc.subject] : []
  }
  const s = shotOfNo(sc.shotNo)
  return s ? shotCastInfo(s, all).subjects : all
})

/** 当前范围里**显式设置**过的框（帧级/镜级/方案级各自的存储） */
function posOwnBox(name: string): { x: number; y: number; w: number; h: number } | null {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return null
  const sc = posScope.value
  // 「方案默认」与「定妆照 · 某主体」都写在同一处：subjects[].region（按主体存的全片默认位置）
  if (sc.kind === 'plan' || sc.kind === 'portrait') {
    return (d.subjects ?? []).find((x) => x.name === name)?.region ?? null
  }
  const s = shotOfNo(sc.shotNo)
  if (!s) return null
  const list = sc.kind === 'frame' ? (s.keyframes ?? [])[sc.index]?.layout : s.layout
  const b = (list ?? []).find((x) => x.subject === name)
  return b ? { x: b.x, y: b.y, w: b.w, h: b.h } : null
}
/** 继承来源：镜继承方案默认；帧继承该镜的镜级 → 再不够就方案默认 */
function posInheritedBox(name: string): { x: number; y: number; w: number; h: number } | null {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return null
  const sc = posScope.value
  if (sc.kind === 'plan' || sc.kind === 'portrait') return null
  const s = shotOfNo(sc.shotNo)
  if (!s) return null
  if (sc.kind === 'frame') {
    const own = (s.layout ?? []).find((b) => b.subject === name)
    if (own) return { x: own.x, y: own.y, w: own.w, h: own.h }
  }
  return (d.subjects ?? []).find((x) => x.name === name)?.region ?? null
}
/** 显示用：本级有就用本级的，没有就显示继承来的（浅色虚线） */
function posBox(name: string): { x: number; y: number; w: number; h: number } | null {
  return posOwnBox(name) ?? posInheritedBox(name)
}
function posIsInherited(name: string): boolean {
  return !posOwnBox(name) && !!posInheritedBox(name)
}
/** 百分比输入框的值（0–100） */
function posPctStr(name: string): { x: string; y: string; w: string; h: string } {
  const b = posBox(name)
  return b
    ? { x: pctOf(b, 'x'), y: pctOf(b, 'y'), w: pctOf(b, 'w'), h: pctOf(b, 'h') }
    : { x: '', y: '', w: '', h: '' }
}

/** 写一个主体的框到**当前范围**（其余已设主体保留，不动上层数据） */
function posWrite(name: string, box: { x: number; y: number; w: number; h: number }): void {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return
  const cl = (v: number): number => Math.round(Math.max(0, Math.min(1, v)) * 1000) / 1000
  const one = { subject: name, x: cl(box.x), y: cl(box.y), w: cl(box.w), h: cl(box.h) }
  const sc = posScope.value
  if (sc.kind === 'plan' || sc.kind === 'portrait') {
    const subs = planSubjects()
    setPlanSubjects(
      subs.map((x) => (x.name === name ? { ...x, region: { x: one.x, y: one.y, w: one.w, h: one.h } } : x)),
    )
    return
  }
  const s = shotOfNo(sc.shotNo)
  if (!s) return
  // 顺序按“当前范围的主体顺序”，保证后端 imageN 槽位与界面展示一致
  const merge = (list: Array<{ subject: string; x: number; y: number; w: number; h: number }> | null | undefined) => {
    const next = [...(list ?? []).filter((b) => b.subject !== name), one]
    return posSubjects.value
      .map((n) => next.find((b) => b.subject === n))
      .filter((b): b is { subject: string; x: number; y: number; w: number; h: number } => !!b)
  }
  if (sc.kind === 'frame') {
    const kf = (s.keyframes ?? [])[sc.index]
    if (kf) kf.layout = merge(kf.layout)
  } else {
    s.layout = merge(s.layout)
  }
}
/** 拖动/缩放用：入参是百分比（0–100） */
function setPosPct(name: string, region: { x: number; y: number; w: number; h: number }): void {
  posWrite(name, { x: region.x / 100, y: region.y / 100, w: region.w / 100, h: region.h / 100 })
}
/** 表里直接填值（0–100 百分比） */
function setPosField(name: string, k: 'x' | 'y' | 'w' | 'h', raw: string): void {
  const n = Number(String(raw ?? '').trim().replace('%', ''))
  if (!Number.isFinite(n)) return
  const base = posBox(name) ?? { x: 0.3, y: 0.2, w: 0.4, h: 0.6 }
  const next = { ...base, [k]: Math.max(0, Math.min(100, n)) / 100 }
  if (k === 'w') next.x = Math.min(next.x, 1 - next.w)
  if (k === 'h') next.y = Math.min(next.y, 1 - next.h)
  posWrite(name, next)
}
/** 清掉当前范围内该主体的位置（回到上一层继承） */
function clearPos(name: string): void {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return
  const sc = posScope.value
  if (sc.kind === 'plan' || sc.kind === 'portrait') {
    setPlanSubjects(planSubjects().map((x) => (x.name === name ? { ...x, region: null } : x)))
    return
  }
  const s = shotOfNo(sc.shotNo)
  if (!s) return
  if (sc.kind === 'frame') {
    const kf = (s.keyframes ?? [])[sc.index]
    if (kf) kf.layout = (kf.layout ?? []).filter((b) => b.subject !== name)
  } else {
    s.layout = (s.layout ?? []).filter((b) => b.subject !== name)
  }
}

/** 分镜卡【在顶部编辑】快捷入口（A 方案）：跳到顶部总控并选中该镜 */
function editPosOnTop(shotNo: number, frameIndex?: number): void {
  // ★ 2026-09-16 夜：从「运镜关键帧」的某一帧点进来时，直接把作用范围切到那一帧
  if (frameIndex != null && frameIndex >= 0) {
    setPosScope(`frame:${shotNo}:${frameIndex}`)
  } else {
    setPosScope(`shot:${shotNo}`)
  }
  posPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  const kfN = (shotOfNo(shotNo)?.keyframes ?? []).length
  message.info(
    frameIndex != null && frameIndex >= 0
      ? `已在顶部「位置总控」选中第 ${shotNo} 镜 · 帧 ${frameIndex + 1}（拖框或填区域%即可）`
      : kfN > 1
        ? `已在顶部选中第 ${shotNo} 镜（该镜有 ${kfN} 帧）：要改某一帧，把作用范围切到「帧 N」`
        : `已在顶部「位置总控」选中第 ${shotNo} 镜`,
  )
}

/** 位置预览数据（**按主体**）：主体名 + 区域 + 配色 + 定妆照缩略图 */
const refPreviewItems = computed(() =>
  posSubjects.value.map((name, i) => ({
    id: name,
    label: name,
    region: parseRegionPct(posPctStr(name)),
    inherited: posIsInherited(name),
    color: POS_COLORS[i % POS_COLORS.length],
    // ★ 2026-09-16（用户要求）：预览框里直接显示定妆照图片（而不是只有线框）
    thumb: subjectThumbByName(name),
  })),
)
const posAspectCss = computed(() => {
  const ar = project.data.value?.aspectRatio ?? '16:9'
  const [w, h] = ar.split(':').map((n) => Number(n) || 0)
  return w > 0 && h > 0 ? `${w} / ${h}` : '16 / 9'
})
/** 「自动分配」：按当前范围的主体顺序摆位（2 → 左右；3 → 三等分；4 → 2×2）
 *
 * @param silent true = 由「切到某一镜时自动均分」触发（提示语不同，不当成用户主动操作）
 */
function autoLayoutRegions(silent = false): void {
  const names = posSubjects.value
  const n = names.length
  if (n < 2) {
    if (!silent) message.info('当前范围只有 0~1 个主体，无需自动分配')
    return
  }
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
  names.forEach((name, i) => setPosPct(name, layouts[i] ?? layouts[layouts.length - 1]))
  message.success(silent
    ? `已按主体顺序把 ${n} 个定妆照自动均分到「${posScopeLabel.value}」（可拖动微调；不想自动分配可先手动设一个框）`
    : `已按主体顺序自动分配（${posScopeLabel.value}），可拖动或微调`)
}

// 位置预览拖动（移动 / 右下角缩放）
/**
 * 位置框 → **估计脸宽（px）** 的经验公式（2026-09-16 实测标定，仅在 UI 上做提前预告）。
 *
 * 为什么要预告：位置框只影响**构图**（它只拼进正词，从不会把参考图裁/缩 —— 2026-09-16 已用 md5
 * 逐字节比对确认“送进模型的就是库里原图”）。但框画得远，生成图里的脸就小，**小到一定程度
 * 身份锚定（定妆照）就失效了** —— 实测：框高 h=1.00 → 脸 47~59px（掉身份）；h=0.76 → 100px（相似度 0.609 ✓）；
 * h=0.50 → 138px。拟合：脸宽 ≈ 0.09 × 画幅高 ÷ 框高（1280x704 时 h=1.0→63px、0.6→106px、0.45→141px）。
 *
 * 阈值（insightface det 下限 + ArcFace 经验 + 上述实测）：
 *   < 40px 检测极限以下（体检都测不出）；< 64px 身份基本失效（红）；64~96px 能用但脆弱（黄）；
 *   ≥ 96px 可用；≥ 112px 稳。
 */
const FACE_PX_RED = 64
const FACE_PX_AMBER = 96

/** 估计脸宽（px）。frameH = 出图画幅高（如 1280x704 → 704）；h = 主体框高（0~1）。取不到就返回 0（不预告）。 */
function estFacePx(h: number, frameH: number): number {
  if (!(h > 0) || !(frameH > 0)) return 0
  return Math.round(0.09 * frameH / h)
}

/** 当前画幅高度（按项目的宽高比 + 关键帧宽度 1280 估：1280x704 / 1280x720 …） */
const stillFrameH = computed<number>(() => {
  const ar = project.data.value?.aspectRatio ?? '16:9'
  const [w, h] = String(ar).split(':').map((n) => Number(n) || 0)
  return w > 0 && h > 0 ? Math.round(1280 * h / w) : 704
})

/** 位置总控：当前范围每个主体的「估计脸宽」与告警档（仅提示，不阻断） */
function faceSizeHint(sub: string): { px: number; level: 'ok' | 'warn' | 'bad' } | null {
  const h = Number(String(posPctStr(sub).h ?? '').trim()) / 100
  if (!(h > 0)) return null
  const px = estFacePx(h, stillFrameH.value)
  return { px, level: px < FACE_PX_RED ? 'bad' : px < FACE_PX_AMBER ? 'warn' : 'ok' }
}

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
    setPosPct(posDrag.id, {
      x: Math.min(Math.max(0, o.x + dx), 100 - o.w),
      y: Math.min(Math.max(0, o.y + dy), 100 - o.h),
      w: o.w,
      h: o.h,
    })
  } else {
    setPosPct(posDrag.id, {
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
  const a = assetById.value[id]
  const isRefCopy = (a?.kind ?? 'reference') === 'reference'
  if (!window.confirm(isRefCopy
    ? '删除这张参考图？不可恢复。'
    : '把这张图移出参考图列表？（它是 still/定妆等原件，不会被删除）')) return
  try {
    if (isRefCopy) {
      await deleteAssets(workspaceId.value, projectId.value, [id])
    }
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]
    pruneUnchecked()
    syncReferenceAssets()
    if (isRefCopy) await queryClient.invalidateQueries({ queryKey: ['assets'] })
    message.success(isRefCopy ? '已删除参考图' : '已移出参考图列表（原图保留）')
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
        const blob = await assetBlob(a.id)
        if (blob) thumbUrls.value[a.id] = URL.createObjectURL(blob)
      }
      next[a.id] = thumbUrls.value[a.id]
    }),
  )
}
watch(() => [...refLibrary.value.map((a) => a.id)].join(','), () => { void refreshThumbs() }, { immediate: true })

/** P12：本方案最多可绑定的参考图张数（各模型对「一次能输入几张」另有各自上限，见引擎配置） */
const MAX_REFS = 15

/**
 * 引擎在线状态（只提示、**不拦**生成）：GPU 不在线时用户仍可编辑分镜动作/提示词，
 * 出图任务留在队列里等节点恢复（用户 2026-09-17 口径）。
 */
const engineStatus = useQuery({
  queryKey: ['engine-status'],
  queryFn: () => getEngineStatus(),
  staleTime: 20_000,
  refetchInterval: 30_000,
})
const engineNotice = computed(() => engineStatus.data.value?.notice ?? '')

/**
 * 场景切换提示（**只提示不拦**，用户 2026-09-17 口径）：后端在方案产出时就已算好。
 * 两类：① 一镜内换场景（带明确转场用语才算，浪花/尖叫同画面共存不误报）；② 切换偏密 / 单镜过短。
 */
const planNotices = computed(() => detail.data.value?.notices ?? [])

/** 改 fps → 立刻重算并回写「模型上限(s)」（用户 2026-09-17：两者要同步；**反向不做**，秒变了不改帧率） */
watch(
  () => (draft.value as { edit_plan?: { fps?: number } } | null)?.edit_plan?.fps,
  (now, before) => {
    if (now === undefined || now === before) return
    void motionLimits.refetch()
  },
)

/** 【P13 口径】本机 GPU 车道：单次上限由显存决定，「模型上限(s)」无效 → 置灰 + 提示实际值 */const gpuMotionLane = computed(() => (motionLimits.data.value?.engine ?? 'gpu') !== 'cloud')
const modelCapHint = computed(() => {
  const v = motionLimits.data.value
  if (!v) return '本机 GPU 上限由显存决定（此值对本机车道无效）'
  const fps = Math.max(1, Number(v.fps) || 30)
  return `本机 GPU 上限由显存决定 ≈ ${(Number(v.gpuMaxFrames) / fps).toFixed(2)}s（此值对本机车道无效）`
})

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
type AudioTab = 'master' | 'portrait' | 'voice' | 'bgm' | 'still' | 'clip' | 'lipsync' | 'all'
/**
 * 任务卡片的 Tab：**不含成片 master**（任务区不会产出 master，成片是导出/合成的产物，只在资产库）。
 * “全部”放最后。
 */
const JOB_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  // ★ 2026-09-16 夜用户要求：任务区也加「定妆」Tab —— 否则生成定妆照时只能在「全部」里找，
  //   不知道它到底有没有在跑/跑到哪了（定妆照要 5–7 分钟，中途没进度显示很慌）。
  { key: 'portrait', label: '定妆', kind: 'portrait', hint: '剧情主体定妆照（生成中在这里看进度；它是所有分镜的唯一身份锚定）' },
  { key: 'voice', label: '配音', kind: 'voice', hint: '含试听产物 voice_preview' },
  { key: 'bgm', label: '配乐', kind: 'bgm', hint: '含试听产物 bgm_preview' },
  { key: 'still', label: '关键帧', kind: 'still', hint: '首帧图片 still' },
  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'lipsync', label: '对口型', kind: 'lipsync', hint: '音频驱动嘴型的片段（lipsync）；独占 GPU 显存（~7.9/8GiB），约 2.5 分钟/秒视频' },
  { key: 'all', label: '全部', hint: '全部任务（项多，缩略图按需懒加载）' },
]
/** 资产库 Tab：保留成片 master（导出/合成产物在这里） */
const GAL_TABS: Array<{ key: AudioTab; label: string; kind?: string; hint: string }> = [
  { key: 'master', label: '成片', kind: 'master', hint: '导出/合成出的成片 master' },
  { key: 'portrait', label: '定妆', kind: 'portrait', hint: '剧情主体定妆照（subject_portrait）' },
  { key: 'voice', label: '配音', kind: 'voice', hint: '含试听产物 voice_preview' },
  { key: 'bgm', label: '配乐', kind: 'bgm', hint: '含试听产物 bgm_preview' },
  { key: 'still', label: '关键帧', kind: 'still', hint: '首帧图片 still' },
  { key: 'clip', label: 'motion', kind: 'clip', hint: '图生视频片段 clip' },
  { key: 'lipsync', label: '对口型', kind: 'lipsync', hint: '音频驱动嘴型的片段（lipsync）；独占 GPU 显存（~7.9/8GiB），约 2.5 分钟/秒视频' },
  { key: 'all', label: '全部', hint: '全部产物（项多，缩略图按需懒加载）' },
]
/** 把 kind 归到 Tab（试听产物归入对应正式类型） */
function kindTab(kind: string): AudioTab {
  if (kind === 'portrait') return 'portrait'
  if (kind === 'lipsync') return 'lipsync'
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
/**
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
const galNewestHint = ref('')

function newestStamp<T extends { createdAt: string }>(list: T[]): T | undefined {
  let best: T | undefined
  for (const x of list) {
    if (!best || new Date(x.createdAt).getTime() > new Date(best.createdAt).getTime()) best = x
  }
  return best
}

// ---------- W4 资产库 ----------
// 注意：这里必须包含**所有**要展示的产物类型 —— GAL_TABS 里有的 kind 若不在白名单，
// 那个 Tab 会永远是空的（P13 实例：「对口型」Tab 有了，但 lipsync 资产被过滤掉，
// 任务已 succeeded、mp4 也已落盘，用户却在资源库里看不到）。
const OUTPUT_KINDS = ['still', 'clip', 'master', 'voice', 'bgm', 'voice_preview', 'bgm_preview', 'portrait', 'lipsync']
const outputAssets = computed(() => (assets.data.value ?? []).filter((a) => OUTPUT_KINDS.includes(a.kind)))

/**
 * 是不是视频/音频产物 —— 一律按 **mime** 判断，不要再写 `kind === 'clip' || kind === 'master'` 这种白名单。
 *
 * P13 实例：卡片模板的视频分支只认 clip/master，对口型（kind=lipsync, mime=video/mp4）
 * 就掉进了 `<img>` 分支（拿 mp4 当图片渲染）→ 资产在库里看得到但**没法预览**。
 * 以后再加新产物类型（lipsync/…）也不会再漏。
 */
function isVideoAsset(a: { mime?: string | null }): boolean {
  return (a.mime ?? '').startsWith('video/')
}
function isAudioAsset(a: { mime?: string | null }): boolean {
  return (a.mime ?? '').startsWith('audio/')
}
/** 下载文件名后缀：按 mime 推（之前写死 .png，视频会被存成 .png） */
function assetExt(a: { mime?: string | null }): string {
  const m = (a.mime ?? '').toLowerCase()
  if (m.includes('mp4')) return 'mp4'
  if (m.includes('webm')) return 'webm'
  if (m.includes('audio/mpeg') || m.includes('audio/mp3')) return 'mp3'
  if (m.includes('wav')) return 'wav'
  if (m.includes('jpeg')) return 'jpg'
  if (m.includes('png')) return 'png'
  if (m.includes('webp')) return 'webp'
  return 'bin'
}

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
/** 资产的分类：定妆照优先看快照（历史数据 kind 被写成 still） */
function assetTab(a: AssetRef): AudioTab {
  return a.kind === 'portrait' || a.snapshotKind === 'portrait' ? 'portrait' : kindTab(a.kind)
}
const galleryForTab = computed(() =>
  galTab.value === 'all' ? outputAssets.value : outputAssets.value.filter((a) => assetTab(a) === galTab.value),
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
/**
 * P13：取不到（404）的资产记下来，本次会话不再重试 ——
 * 否则一个已失效的 id 会在每次刷新时反复 404，既刷控制台又拖慢页面。
 */
const missingAssets = ref<string[]>([])
async function assetBlob(id: string): Promise<Blob | null> {
  if (missingAssets.value.includes(id)) return null
  const blob = await fetchAssetBlob(workspaceId.value, id)
  if (!blob) {
    missingAssets.value = [...missingAssets.value, id]
    console.warn('[asset] 读取失败（已标记，不再重试）:', id)
  }
  return blob
}

/**
 * P13：资产库只拉**当前 Tab 可见**的产物，并限并发 4。
 *
 * 以前对 outputAssets（全项目，动辄 170+ 项）一次性并发拉 blob：
 * ① 首屏很慢 ② 触发 nginx 站点级 limit_conn(20/IP) → 部分请求被打成 50x→404
 * （实测：控制台反复报某几个 assets/…/download 404，其实文件都在）
 */
/**
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

async function refreshGallery(): Promise<void> {
  const todo = galleryForTab.value.filter((a) => !galUrls.value[a.id] && !missingAssets.value.includes(a.id))
  let cursor = 0
  const worker = async (): Promise<void> => {
    while (cursor < todo.length) {
      const a = todo[cursor++]
      const blob = await assetBlob(a.id)
      if (blob) galUrls.value[a.id] = URL.createObjectURL(blob)
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, todo.length) }, () => worker()))
}
// 数据或 Tab 变化时再拉（切 Tab 才拉该 Tab 的图）
watch(() => [outputAssets.value.map((a) => a.id).join(','), galTab.value].join('|'),
  () => { void refreshGallery() }, { immediate: true })

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
async function setRefFromAsset(id: string): Promise<void> {
  if (refSelected.value.includes(id)) return
  if (refSelected.value.length >= MAX_REFS) {
    message.warning(`参考图最多 ${MAX_REFS} 张`)
    return
  }
  // ★ 2026-09-16 夜（用户要求）：从资产库点「参考」→ **复制一份**到参考图里。
  //   为什么：参考图只支持「勾选 / 删除」两态，是独立的一堆候选素材；
  //   直接引用原资产 id 的话，删参考图会把那张 still/portrait 原件一起删掉。
  const a = assetById.value[id]
  const srcKind = a?.kind ?? 'reference'
  refBusy.value = id
  try {
    const refId = srcKind === 'reference'
      ? id
      : (await assetAsReference(workspaceId.value, projectId.value, id)).id
    if (refId !== id) {
      await queryClient.invalidateQueries({ queryKey: ['assets'] })
    }
    refSelected.value = [...refSelected.value, refId]
    refUnchecked.value = refUnchecked.value.filter((x) => x !== refId)   // 新加入＝已勾选
    syncReferenceAssets()
    message.success(srcKind === 'reference'
      ? '已加入参考图并勾选（它作为**生成定妆照**的依据；出图锚定一律用定妆图）'
      : `已把这张 ${srcKind} 图**复制**一份进参考图并勾选（原件不受影响）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '复制为参考图失败')
  } finally {
    refBusy.value = ''
  }
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
      else { refSelected.value = [...refSelected.value, a.id] }   // 上传即已勾选（只留勾选/删除两态）
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
/** 勾选状态唯一入口（on=true 勾选参与，false 取消） */
function setRefChecked(id: string, on: boolean): void {
  refUnchecked.value = on
    ? refUnchecked.value.filter((x) => x !== id)
    : [...new Set([...refUnchecked.value, id])]
}

/** 参考图格子的 × ：删除参考图（reference 副本才删资产；非 reference 只从列表移出，不碰原件） */
/* ---------------- P12：生成前弹分镜勾选（>3 镜时） ---------------- */
const pickOpen = ref(false)
const pickBusy = ref(false)
const pickCtx = ref<{ title: string; run: (shotNos: number[] | null) => Promise<void> | void } | null>(null)

/**
 * 分镜多于 3 镜 → 先弹勾选窗（选部分镜还是全部，顺便可封版）；
 * ≤ 3 镜直接跑（不传 shotNos = 全部，后端会自动跳过已封版镜）。
 */
async function withShotPicker(
  title: string,
  run: (shotNos: number[] | null) => Promise<void> | void,
  kind: 'still' | 'clip' | 'voice' | 'lipsync' = 'still',
  opts?: { always?: boolean; hint?: string },
): Promise<void> {
  pickerKind.value = kind
  pickHint.value = opts?.hint ?? ''
  // P13：生成前预检（未确认改动 → 先问）
  if (!(await ensureApprovedForGenerate(title))) return
  // 镜少时默认跳过弹窗（等同于「全部」）；对口型贵且按镜判断前置条件 → 传 always 强制选镜
  if (!opts?.always && pickerShots.value.length <= 3) {
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

/**
 * 弹窗要展示的分镜列表：镜号 + **当前操作类型**的资源版本与数量。
 *
 * 点「生成关键帧」就只看 still 的版本/张数；点「生成配音」才看 voice 的版本/段数 ——
 * 混着显示其它类型（如语音段数）对当前操作没意义。
 */
const pickerKind = ref<'still' | 'clip' | 'voice' | 'lipsync'>('still')
const pickHint = ref('')
const pickerShots = computed(() => {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return []
  const kind = pickerKind.value
  const succ = (no: number, k: string) =>
    (jobs.data.value ?? []).filter(
      (j) => j.payload?.shot_no === no && j.kind === k && j.state === 'succeeded',
    )
  return (plan.shots ?? []).map((s) => {
    if (kind === 'lipsync') {
      // P13 对口型的前置条件（与后端 JobService.createLipsyncJobs 一致）：
      //   画面（该镜最新 motion 片段，没有则退关键帧静帧）+ 至少一段配音
      //   另外：motion 产物带 faceDetected（worker 出片时抽 6 帧检测）——没人脸的镜
      //   LatentSync 会直接报 Face not detected，所以在这里提前标出来（而不是跑到一半才失败）
      const clips = succ(s.shot_no, 'clip')
      const stills = succ(s.shot_no, 'still')
      const visuals = [...clips, ...stills]
      const voice = succ(s.shot_no, 'voice')
      const missing: string[] = []
      let multiHint = ''
      if (!visuals.length) missing.push('缺画面(motion/关键帧)')
      if (!voice.length) missing.push('缺配音')
      // 多人说话：**已支持** —— worker 按台词时间窗分段、每段只驱动该段说话人的脸，
      // 再按原时间轴把帧拼回去（见 comfy_client.generate_lipsync 按段驱动）。
      // 不再因此拒绝；只提醒耗时按段数累加。
      const speakers = [...new Set(
        (s.narrations ?? [])
          .filter((n) => n.kind === 'dialogue' && (n.subject ?? '').trim() !== '')
          .map((n) => (n.subject ?? '').trim()),
      )]
      if (speakers.length > 1) {
        multiHint = `多人（${speakers.join('、')}）· 按段驱动，耗时约×${speakers.length}`
      }
      // 多人镜必须为每个说话人指定“他的脸在哪”——否则 LatentSync 会把台词配到同一张脸上
      const tg = s.lipsync_targets ?? {}
      const needFace = speakers.filter((n) => !tg[n]).length
      const faceHint = speakers.length
        ? `${speakers.filter((n) => tg[n]).length}/${speakers.length}`
        : ''
      // A：底片（驱动嘴型的那份画面）——方案里显式指定优先；否则按后端同一套默认值算：
      //    有 clip 用 clip；但「惊恐/喊叫」类镜头（底片里嘴本来就大张）默认用静帧。
      //    必须先算底片，后面「无人脸」判定才能拿**真当底片的那份产物**去看人脸。
      const wantBase = s.lipsync_source === 'still' || s.lipsync_source === 'clip' ? s.lipsync_source : null
      const hasClip = clips.length > 0
      const hasStill = stills.length > 0
      const risk = expressionRiskOf(s)
      const autoBase: 'clip' | 'still' = !hasClip || (hasStill && risk) ? 'still' : 'clip'
      const lipsyncSource: 'clip' | 'still' = wantBase ?? autoBase
      const baseWord = lipsyncSource === 'still' ? '关键帧静帧' : 'motion 片段'
      // 底片产物的人脸结论（undefined = 未知/历史数据 → 不拦）
      const pool = lipsyncSource === 'still' ? (stills.length ? stills : clips) : (clips.length ? clips : stills)
      const newestVisual = [...pool].sort(
        (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      )[0]
      const face = newestVisual
        ? (assets.data.value ?? []).find((a) => a.jobId === newestVisual.id)?.faceDetected
        : undefined
      const faceFrames = newestVisual
        ? (assets.data.value ?? []).find((a) => a.jobId === newestVisual.id)?.faceFrames
        : undefined
      if (face === false) {
        // LatentSync 要求**每一帧**都能检出人脸：0/N = 完全无脸；其它 = 部分帧无脸（同样跑不了）
        missing.push(faceFrames && !faceFrames.startsWith('0/')
          ? `部分帧无人脸(${faceFrames})`
          : '无人脸')
      }
      const newest = newestStamp(visuals)
      const rev = newest ? revOfJob(newest) : undefined
      return {
        shotNo: s.shot_no,
        revNo: rev?.no ?? null,
        stale: rev?.stale === true,
        lineCount: voice.length,
        unit: '段语音',
        eligible: missing.length === 0,
        // 只要有台词说话人就给「指定人脸」入口 —— 单人镜同样需要：
        // 多人同框时「取最大脸/按定妆照识别」都不可靠（实测 480p/AI 古风下识别区分度崩塌），
        // 用户点一下最实在。多人镜额外用高亮提醒「还有谁没指定」。
        needsFaceHint: speakers.length > 0 && needFace > 0 && (speakers.length > 1 || multiHint !== ''),
        faceHint: speakers.length ? faceHint : '',
        lipsyncSource,
        lipsyncAuto: wantBase === null,
        hasClip,
        hasStill,
        expressionRisk: risk,
        note: missing.length
          ? `不可：${missing.join('、')}`
          : `可生成 · ${voice.length} 段语音 · 底片=${baseWord}${risk ? '（大张口风险镜）' : ''}${multiHint ? ` · ${multiHint}` : ''}`,
      }
    }
    const rel = succ(s.shot_no, kind)
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
      // 任务全部结束后资产已落库 → 刷新资产库，并**定位到当前 Tab 最新的一条**方便查看
      void (async () => {
        await queryClient.invalidateQueries({ queryKey: ['assets'] })
        await nextTick()
        await refreshGallery()
        focusNewestAsset()
      })()
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
  // P5：先过主体闸门（镜文本没点名主体 → 弹框让用户勾选，避免“全部注入/没参考图”）
  if (!preflightCast()) return
  // ★ 2026-09-16 夜（用户要求“做 a”）：生成**前**拦“脸会过小”的组合。
  //   为什么必须在生成前：事后告警等于白烧 GPU（用户原话：生成后再告警没意义，浪费了生图资源/时间）。
  if (!preflightFaceSize(shotNos)) return
  await doStartGeneration(shotNos, revId)
}

/** 真正发起关键帧生成（闸门过了、或用户在“脸过小”弹框里选了“仍然生成”） */
async function doStartGeneration(shotNos: number[] | null | undefined, revId: string): Promise<void> {
  // P12：生成后自动切到对应 Tab（顺手解锁），否则用户看不到刚发起任务的进度
  focusJobTab('still')
  // P4：先把当前草稿（含参考图/主体标注/提示词改动）落库，再发起生成
  if (dirty.value && !(await savePlanInPlace())) return
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
/**
 * 生成前的“脸过小”闸门（用户要求 a）。
 *
 * 返回 false = 已弹框拦住（用户可选“先去调框”或“仍然生成”）。
 * 判据：本镜该主体的**生效框**（帧级 > 镜级 > 方案默认）→ 估计脸宽 < 64px。
 * 为什么值这个闸门：这类图生成出来基本必是“换脸/不像”（实测 47~59px 那一档），
 * 而一张关键帧要占用 GPU 几分钟 —— 生成后再告警等于白烧（用户明确否定了事后告警）。
 */
function preflightFaceSize(shotNos?: number[] | null): boolean {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return true
  const names = planSubjectsNow.value
  if (!names.length) return true
  const want = shotNos && shotNos.length ? new Set(shotNos) : null
  const rows: Array<{ shot: number; sub: string; px: number }> = []
  for (const s of d.shots ?? []) {
    if (want && !want.has(s.shot_no)) continue
    for (const sub of shotCastInfo(s, names).subjects) {
      const h = shotBoxH(s, sub)
      if (!(h > 0)) continue
      const px = estFacePx(h, stillFrameH.value)
      if (px < FACE_PX_RED) rows.push({ shot: s.shot_no, sub, px })
    }
  }
  if (!rows.length) return true
  faceWarnRows.value = rows
  faceWarnShotNos.value = shotNos && shotNos.length ? [...shotNos] : null
  faceWarnOpen.value = true
  return false
}

/** 该主体在**本镜**的生效框高（归一化 0~1）：帧级 > 镜级 > 方案默认（与后端 applyLayoutRegions 同优先级） */
function shotBoxH(shot: DirectorShot, sub: string): number {
  const norm = (h: unknown): number => {
    const v = Number(h ?? 0)
    if (!(v > 0)) return 0
    return v > 1.5 ? v / 100 : v      // 兼容 0~1 与 0~100 两种写法
  }
  const kf = (shot.keyframes ?? [])[0] as unknown as { layout?: Array<{ subject?: string; h?: number }> } | undefined
  const fromKf = kf?.layout?.find((x) => x.subject === sub)
  if (fromKf) return norm(fromKf.h)
  const fromShot = (shot.layout ?? []).find((x) => x.subject === sub)
  if (fromShot) return norm(fromShot.h)
  const fromPlan = planSubjects().find((x) => x.name === sub)?.region
  return fromPlan ? norm(fromPlan.h) : 0
}

/** 用户选“仍然生成” */
function confirmFaceWarn(): void {
  faceWarnOpen.value = false
  const revId = genRevisionId()
  if (!revId) return
  void doStartGeneration(faceWarnShotNos.value, revId)
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
/** P13：已确认版本的方案（变更摘要的对比基线） */
const approvedDetail = useQuery({
  queryKey: computed(() => ['revision-approved', projectId.value, approvedRev.value?.id ?? '']),
  queryFn: () => getRevision(workspaceId.value, projectId.value, approvedRev.value!.id),
  enabled: computed(() => !!approvedRev.value?.id && workspaceId.value !== ''),
})
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
/**
 * motion 帧数区间**按引擎下发**（不再写死 32–96）：
 * 本机 GPU 上限由显存决定（3070Ti ≈ 96 帧）；云 API 上限由**模型**决定
 * （可用项目「模型上限(s) × fps」，如 5s@30fps = 150 帧）。
 */
const MOTION_MIN = ref(32)
const MOTION_MAX = ref(96)
/**
 * motion 原生帧率（与 worker `WEAVEORA_MOTION_NATIVE_FPS` 同口径，缺省 16）：
 * A14B 按 16fps 原生节奏生成，所以「需要多少帧」= 镜头时长 × 16（不是 30！）。
 * 踩过的坑（2026-09-15）：UI 选 121 帧 + 按成片 30fps 理解 → 运动快 1.875×、5s 只剩 4.13s。
 */
const MOTION_NATIVE_FPS = 16
/** 本镜时长（用于把帧数自动算出来 + 显本次帧数对应的视频长度） */
const motionShotSec = computed<number>(() => {
  const v = Number(project.data.value?.shotDurationSec ?? 0)
  return v > 0 ? v : 5
})
const motionNeedFrames = computed<number>(() => Math.round(motionShotSec.value * MOTION_NATIVE_FPS))
const motionFramesSec = computed<number>(() => (Number(motionFrames.value) || 0) / MOTION_NATIVE_FPS)
const motionTooFew = computed<boolean>(() => Number(motionFrames.value) < motionNeedFrames.value)
const motionLimitSource = ref('')
const motionOpen = ref(false)
const motionFrames = ref(48)

const motionLimits = useQuery({
  queryKey: computed(() => ['video-limits', workspaceId.value, projectId.value, selectedRevId.value ?? '']),
  queryFn: () => getVideoLimits(workspaceId.value, projectId.value, selectedRevId.value as string),
  enabled: computed(() => !!projectId.value && !!selectedRevId.value),
})

watch(
  () => motionLimits.data.value,
  (v) => {
    if (!v) return
    MOTION_MIN.value = v.minFrames
    MOTION_MAX.value = v.maxFrames
    motionLimitSource.value = v.source
    if (motionFrames.value > v.maxFrames) motionFrames.value = v.maxFrames
    // 自动把「模型上限(s)」写为后端算出的真实上限（min(配置, 模型 schema)）：
    // 否则用户会拿一个比模型大的值去校准 → 排出的段仍超过模型能力 → 配音被截断。
    const p = draft.value
    if (p && isVideoPlan(p) && v.maxClipSec > 0) {
      const cur = Number(p.edit_plan?.video_model_max_sec ?? 0)
      if (Math.abs(cur - v.maxClipSec) > 0.05) {
        if (p.edit_plan) p.edit_plan.video_model_max_sec = Math.round(v.maxClipSec * 100) / 100
        if (cur === 0) message.info(`已按模型自动设「模型上限」= ${v.maxClipSec}s（${v.source}）`)
      }
    }
  },
  { immediate: true },
)

function openMotionModal(): void {
  // ★ 2026-09-16 夜（用户要求）：**不再让用户自己算帧数** —— 按「镜头时长 × 原生 16fps」自动填，
  //   并夹到引擎下发区间内。帧数只当上限（worker 取 min(时长×16, 帧数)），所以自动填到位最安全。
  motionFrames.value = Math.min(Math.max(motionNeedFrames.value, MOTION_MIN.value), MOTION_MAX.value)
  void motionLimits.refetch()
  motionOpen.value = true
}
function confirmMotion(): void {
  focusJobTab('clip')
  const f = Number(motionFrames.value)
  if (!Number.isInteger(f) || f < MOTION_MIN.value || f > MOTION_MAX.value) {
    message.warning(`运动帧数需在 ${MOTION_MIN.value}–${MOTION_MAX.value} 之间`)
    return
  }
  motionOpen.value = false
  withShotPicker('运动(motion)', (shotNos) => startMotion(f, shotNos), 'clip')
}
const KIND_LABEL: Record<string, string> = { still: '关键帧', clip: '运动', voice: '配音', bgm: '配乐', lipsync: '对口型', portrait: '定妆' }

/** P7：逐镜配音（自托管 CosyVoice） */
async function startVoice(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await savePlanInPlace())) return
  focusJobTab('voice')
  // 注：这里**不能**再兜一手 handleSave()。savePlanInPlace 已把草稿就地写入生成基准稿；
  // 若它没清掉 dirty，handleSave 会在已确认稿上**另存 vN+1**（未确认）→ 反而要求重新确认。
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
  if (dirty.value && !(await savePlanInPlace())) return
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
  // 逐条调音：就地保存即可（不另存版本、不弹确认），否则改一条就要确认一次，根本没法逐条做
  if (dirty.value && !(await savePlanInPlace())) return
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
  if (dirty.value && !(await savePlanInPlace())) return
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
  if (dirty.value && !(await savePlanInPlace())) return
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
  if (dirty.value && !(await savePlanInPlace())) return
  focusJobTab('bgm')
  // 同上：禁止再兜 handleSave（会在已确认稿上另存未确认版本，导致又要重新确认）
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

/** P13 对口型：按「镜」生成 —— 该镜的 motion/关键帧 + 该镜配音 → 音频驱动嘴型（本机 ComfyUI 工作流）
 *  shotNos 为空 = 全部已确认镜。前置条件：该镜同时有画面与配音，否则后端会跳过。 */
async function startLipsync(shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  focusJobTab('lipsync')
  if (dirty.value && !(await savePlanInPlace())) return
  // 同上：禁止再兜 handleSave —— 那会在已确认稿上另存 vN+1（未确认），
  // 于是“对口型按钮总是提示保存/要求确认方案”。
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'lipsync',
      ...(shotNos && shotNos.length ? { shotNos } : {}),
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

/** A：切某镜的对口型底片（motion 片段 / 关键帧静帧）——写进方案并就地保存 */
async function onLipsyncBase(p: { shotNo: number; source: 'clip' | 'still' }): Promise<void> {
  const plan = draft.value
  if (!plan || !isVideoPlan(plan)) return
  const shot = (plan.shots ?? []).find((s) => s.shot_no === p.shotNo)
  if (!shot) return
  shot.lipsync_source = p.source
  await savePlanInPlace()
  message.success(
    `第 ${p.shotNo} 镜对口型底片 = ${p.source === 'still' ? '关键帧静帧' : 'motion 片段'}`
    + `（下一批「对口型」会用它驱动嘴型）`,
  )
}

/** 对口型按钮：**总是先弹分镜选择**（贵 + 按镜判断前置条件），再对该镜最新资产对口型 */
function openLipsyncPicker(): void {
  void withShotPicker(
    '对口型(lipsync)',
    (nos) => startLipsync(nos),
    'lipsync',
    {
      always: true,
      hint:
        '对口型按「镜」生成：只处理勾选的分镜，用该镜**底片**（默认 motion 片段；惊恐/喊叫等「底片里嘴本来就大张」的镜 + 只有一张干净的脸的**静帧**更稳，可在行内切「底片：片段/静帧」）+ 该镜全部配音。需同时具备「画面」与「配音」才能生成；底片体检查到「脸太小 / 嘴大张」会拒绝并告诉你怎么换。',
    },
  )
}

async function startMotion(frames?: number, shotNos?: number[] | null): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  // P5：出片同样要过主体闸门（首帧就是关键帧，主体错了一路错）
  if (!preflightCast()) return
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
    // P13：有关键帧的镜先跑，缺关键帧的镜由后端跳过 —— 这里把差额说明白
    const want = pickerShots.value.length || 0
    const skipped = Math.max(0, want - created.length)
    message.success(`已创建 ${created.length} 个运动任务（关键帧→motion · 基于确认稿 v${approvedRev.value?.revisionNo ?? '?'}）`
      + (skipped > 0 ? `；另有 ${skipped} 个镜尚无关键帧，已自动跳过（可先补关键帧再单独跑 motion）` : ''))
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

/**
 * 「按配音校准时长」：用每段配音的**实际时长**反推镜头时长（+余量），
 * 超过模型单次输出上限的镜头自动写 `segments`（分段生成、成片 cut 拼接）。
 *
 * 先弹差异预览（哪镜变长/变短、切几段），确认后**就地保存**（不另存版本、不动确认态）。
 */
async function calibrateAllDurations(): Promise<void> {
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
  const more = diffs.length > 12 ? '\n…另有 ' + (diffs.length - 12) + ' 个镜头' : ''
  const ok = window.confirm(
    '按配音校准（模型单次上限 ' + cap + 's、尾部余量 ' + tail + 's）：\n\n' +
      lines.join('\n') +
      more +
      '\n\n确认写入方案？',
  )
  if (!ok) return
  calibrateAllShots(p as never, durations.value, true)
  if (!(await savePlanInPlace())) {
    message.warning('已改时长，但方案保存失败，请稍后重试')
    return
  }
  message.success('已按配音校准 ' + diffs.length + ' 个镜头')
}

/** 渲染前音画体检：配音超镜/镜头空等/超上限未分段 等 */
function healthCheckBeforeRender(): boolean {
  const p = draft.value
  if (!p || !isVideoPlan(p)) return true
  const issues = audioVideoHealthCheck(p as never, durations.value)
  if (!issues.length) return true
  const head = issues.slice(0, 8).join('\n')
  const more = issues.length > 8 ? '\n…另有 ' + (issues.length - 8) + ' 条' : ''
  return window.confirm(
    '渲染前检查发现问题：\n\n' + head + more + '\n\n仍要渲染吗？（建议先点「按配音校准时长」）',
  )
}

/** 渲染前音画体检：配音超镜/镜头空等/超上限未分段 等 */
/**
 * 渲染前检查：方案里有台词/旁白、但**字幕开关是关的** → 先提醒。
 *
 * 坑过两次：开关默认 false（现为 true）、旧草稿回写 false → 渲染出来没字幕，用户以为“字幕坏了”。
 * 在用到的地方提醒（不靠人记），用户选“打开并渲染”就顺便把开关写回方案。
 */
async function ensureSubtitleForRender(): Promise<boolean> {
  const p0 = draft.value
  if (!p0 || !isVideoPlan(p0)) return true
  const p = p0
  const hasLine = (p.shots ?? []).some((s) => (s.narrations ?? []).some((l) => (l.text ?? '').trim()))
  if (!hasLine || p.edit_plan?.subtitle === true) return true
  const ok = window.confirm(
    '当前方案的「字幕」开关是关的，渲染出来的成片不会有台词/旁白字幕。\n\n要现在打开并渲染吗？',
  )
  if (!ok) return true // 尊重用户选择：仍旧渲染无字幕版
  if (p.edit_plan) p.edit_plan.subtitle = true
  const saved = await savePlanInPlace()
  if (!saved) {
    message.warning('字幕开关已改，但方案保存失败；请先「确认并使用此版本」再渲染')
  }
  return true
}

async function doRender(): Promise<void> {
  if (!selectedRevId.value) return
  if (!(await ensureSubtitleForRender())) return
  if (!healthCheckBeforeRender()) return
  
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
/** P5：AI 更新提示词的语言（用户要求：选中文则正/负词**含运镜关键帧**都用中文） */
const aiLang = ref<'zh' | 'en'>('zh')
/** 本次重写的运镜关键帧快照（应用时按序回写 keyframes[].positive_prompt） */
const aiFrames = ref<Array<{ label: string; composition: string }>>([])

// ---------- P5：生成前的主体闸门（用户要求：没指明主体要提示/弹框让用户勾选）----------
/** 待用户勾选主体的镜（列在弹框里） */
const castOpen = ref(false)
const castBusy = ref(false)
const castRows = ref<Array<{ shot: DirectorShot; names: string[]; picked: string[]; empty: boolean }>>([])

/** 参与锚定的主体名（方案顺序） */
const planSubjectsNow = computed<string[]>(() => {
  const d = draft.value
  return d && isVideoPlan(d) ? planSubjectNames(d) : []
})

/**
 * 生成前预检：把「镜文本没点到任何主体」的镜列出来让用户勾选。
 *
 * 为什么需要（用户实测）：镜文本（action/zh/positive_prompt）没提到主体时，后端会
 * 「回退为全部主体」——多角色时容易串脸；而个别帧看起来根本没拿到参考图。根因就是
 * **提示词里没点名主体**，所以生成前必须让用户显式选一次。
 *
 * @returns true = 可以继续生成；false = 需要用户先勾选（弹框已打开）
 */
/** “脸过小”闸门的弹框状态（生成前拦，不阻断：用户可强推） */
const faceWarnOpen = ref(false)
const faceWarnRows = ref<Array<{ shot: number; sub: string; px: number }>>([])
const faceWarnShotNos = ref<number[] | null>(null)

function preflightCast(): boolean {
  const d = draft.value
  if (!d || !isVideoPlan(d)) return true
  const names = planSubjectsNow.value
  if (names.length < 2) return true // 单主体没有歧义
  const rows = (d.shots ?? [])
    .filter((s) => shotCastInfo(s, names).ambiguous)
    .map((s) => ({ shot: s, names, picked: [...shotCastInfo(s, names).subjects], empty: false }))
  if (!rows.length) return true
  castRows.value = rows
  castOpen.value = true
  return false
}

function confirmCast(): void {
  for (const r of castRows.value) {
    if (r.empty) {
      r.shot.cast = [] // 明确的空镜（环境/道具镜）
      continue
    }
    const picked = r.names.filter((n) => r.picked.includes(n))
    if (!picked.length) {
      message.warning(`第 ${r.shot.shot_no} 镜还没选主体：请勾选主体，或点「空镜（无主体）」`)
      return
    }
    r.shot.cast = picked
  }
  castOpen.value = false
  message.success('已记录本镜主体，请再点一次生成')
}

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

/**
 * 单镜 AI 更新提示词（取代原来的「AI 同步提示词（全部）」——
 * 一次性跑全片会几十次 LLM 串行、前端卡死；现在按镜来，一次一镜）。
 *
 * 运镜关键帧（2–4 帧）必须一起重写：生成时每帧用的是**自己的** positive_prompt，
 * 不一起重写就会出现「主正词更新了、关键帧还是旧稿」的不一致（用户实测第 3 镜）。
 */
async function openAiRewrite(shot: DirectorShot): Promise<void> {
  const raw = ((shot.action ?? shot.zh) ?? '').trim()
  if (!raw) {
    message.info('本镜还没有「画面动作」中文描述 —— AI 是照它重写的，请先在镜卡里填写')
    return
  }
  aiImageMode.value = false
  aiShot.value = shot
  aiOpen.value = true
  aiBusy.value = true
  aiPreview.value = null
  aiFrames.value = (shot.keyframes ?? []).map((k) => ({
    label: k.label ?? '',
    composition: k.composition ?? '',
  }))
  try {
    aiPreview.value = await rewritePromptFromZh(
      workspaceId.value,
      projectId.value,
      raw,
      shot.positive_prompt,
      shot.negative_prompt,
      aiLang.value,
      (shot.keyframes ?? []).map((k) => ({
        label: k.label ?? '',
        composition: k.composition ?? '',
        positivePrompt: k.positive_prompt ?? '',
        negativePrompt: shot.negative_prompt ?? '',
      })),
    )
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成失败，请重试')
    aiOpen.value = false
  } finally {
    aiBusy.value = false
  }
}

/** 在弹框里切换中/英文 → 重新生成（用户要的是「选了就整条换语言」） */
async function changeAiLang(lang: 'zh' | 'en'): Promise<void> {
  if (aiLang.value === lang) return
  aiLang.value = lang
  if (aiImageMode.value) {
    await openAiImageRewrite()
  } else if (aiShot.value) {
    const s = aiShot.value
    await openAiRewrite(s)
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
  // ★ 运镜关键帧逐帧回写（漏了就会出现「帧提示词还是旧稿/旧语言」）
  const kfs = s.keyframes ?? []
  const got = p.keyframes ?? []
  let written = 0
  for (let i = 0; i < kfs.length; i++) {
    const one = got[i]
    if (!one) continue
    kfs[i].positive_prompt = one.positive_prompt
    written++
  }
  s.en_synced = true
  aiOpen.value = false
  message.success(
    written ? `已写入该镜提示词（含 ${written} 个运镜关键帧，记得保存方案）` : '已写入该镜提示词（记得保存方案）',
  )
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

/**
 * P13：生成前预检（解决「忘记保存+确认，于是生成还读旧稿」的坑）。
 *
 * 生成永远以**已确认稿**为准；如果界面上还有未保存的改动、或当前版本尚未确认，
 * 就在这里把差异摊给你看并让你决定：保存并确认后再生成 / 就用已确认稿生成。
 *
 * @return true = 可以继续生成；false = 用户取消
 */
/**
 * P13：**就地保存**当前草稿（不动版本、不需确认）—— 供「逐条重生成/试听配音」使用。
 * 返回是否成功。
 */
async function savePlanInPlace(): Promise<boolean> {
  const revId = genRevisionId()
  const plan = draft.value
  if (!revId || !plan) return false
  try {
    await patchPlanInPlace(workspaceId.value, projectId.value, revId, plan)
    pristineJson.value = canonicalJson(plan)    // 已落库 → 不再算脏（键序无关）
    dirty.value = false
    await queryClient.invalidateQueries({ queryKey: ['revision', workspaceId.value, projectId.value, revId] })
    return true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '就地保存失败')
    return false
  }
}

/* GEN_PREFLIGHT_DONE */
async function ensureApprovedForGenerate(action: string): Promise<boolean> {
  // 只在意“影响生成”的差异：纯元数据（别名/勾选/定妆照）已就地写到当前版本，不必打扰
  const diffs = changeSummary.value.filter((x) => !x.startsWith('（仅有'))
  const pending = !detApproved.value || diffs.length > 0
  if (!pending) return true
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

/** P13：是否需要「确认」（当前是未确认版本，或草稿有未保存的改动） */
const confirmBanner = computed(() => !detApproved.value || dirty.value)
const metaSyncedAt = ref(0)

/** P13：对比基线 —— 当前就是确认稿时对比“已保存的那份”（看未保存改动）；否则对比已确认稿 */
const baselinePlan = computed(() => (detApproved.value
  ? (detail.data.value?.plan ?? null)
  : (approvedDetail.data.value?.plan ?? null)))

/** P13：变更摘要 —— 与对比基线相比改了什么（让确认变成“看一眼差异”） */
const changeSummary = computed<string[]>(() => {
  const cur = draft.value
  const app = baselinePlan.value
  if (!cur) return []
  if (!app) return detApproved.value ? [] : ['首次确认']
  const out: string[] = []
  if (!isVideoPlan(cur)) return ['图片方案有改动']
  const ac = app as unknown as Record<string, unknown>
  const cc = cur as unknown as Record<string, unknown>
  const themeA = ((app as { script?: { theme?: string } }).script?.theme ?? '') as string
  const themeC = ((cur as unknown as { script?: { theme?: string } }).script?.theme ?? '') as string
  if (themeA !== themeC) out.push('主题')
  const shotsA = (app.shots ?? []) as Array<Record<string, unknown>>
  const shotsC = (cur.shots ?? []) as Array<Record<string, unknown>>
  let changedShots = 0
  let changedLines = 0
  const byNo = new Map(shotsA.map((x) => [Number(x.shot_no), x]))
  for (const cs of shotsC) {
    const as = byNo.get(Number(cs.shot_no))
    // canonicalJson：schema_json 是 jsonb，键序会被 PG 重排；普通 stringify 会把
    // 「纯键序差异」误报成「分镜有改动」→ 已确认版本上仍弹「保存并确认后生成」
    if (!as || canonicalJson(as) !== canonicalJson(cs)) changedShots++
    const la = canonicalJson(as?.narrations ?? [])
    const lc = canonicalJson(cs.narrations ?? [])
    if (la !== lc) changedLines++
  }
  if (shotsC.length !== shotsA.length) out.push(`分镜数量 ${shotsA.length}→${shotsC.length}`)
  if (changedShots) out.push(`分镜 ${changedShots} 镜有改动`)
  if (changedLines) out.push(`台词/旁白 ${changedLines} 镜有改动`)
  const vbA = canonicalJson((ac.audio as { voiceBindings?: unknown } | undefined)?.voiceBindings ?? [])
  const vbC = canonicalJson((cc.audio as { voiceBindings?: unknown } | undefined)?.voiceBindings ?? [])
  if (vbA !== vbC) out.push('角色音色绑定')
  const vA = JSON.stringify((ac.audio as { voice?: unknown } | undefined)?.voice ?? '')
  const vC = JSON.stringify((cc.audio as { voice?: unknown } | undefined)?.voice ?? '')
  if (vA !== vC) out.push('默认音色')
  const mA = JSON.stringify((ac.audio as { music?: unknown } | undefined)?.music ?? (ac.audio as { music_mood?: unknown } | undefined)?.music_mood ?? '')
  const mC = JSON.stringify((cc.audio as { music?: unknown } | undefined)?.music ?? (cc.audio as { music_mood?: unknown } | undefined)?.music_mood ?? '')
  if (mA !== mC) out.push('配乐')
  const rA = canonicalJson(ac.referenceAssets ?? [])
  const rC = canonicalJson(cc.referenceAssets ?? [])
  if (rA !== rC) out.push('参考图/区域')
  // ★ P14：设定年代与主体档案（都会影响出图，必须出现在差异里）
  if (canonicalJson((ac as { setting?: unknown }).setting ?? null)
      !== canonicalJson((cc as { setting?: unknown }).setting ?? null)) out.push('设定年代')
  if (canonicalJson((ac as { subjects?: unknown }).subjects ?? null)
      !== canonicalJson((cc as { subjects?: unknown }).subjects ?? null)) out.push('剧情主体设定')
  return out.length ? out : ['（仅有不影响生成的元数据变化）']
})

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

    <!-- 引擎不在线：**只提示不拦**（用户口径：仍可继续处理分镜动作/提示词，出图任务排队等节点） -->
    <NAlert
      v-if="engineNotice"
      type="warning"
      :bordered="false"
      class="engine-offline"
      data-testid="engine-offline-notice"
    >
      <div class="eo-row">
        <span>{{ engineNotice }}</span>
        <NButton size="tiny" quaternary :loading="engineStatus.isFetching.value" @click="engineStatus.refetch()">
          重新检测
        </NButton>
      </div>
    </NAlert>

    <!-- 场景切换提示：**只提示不拦**（用户口径：动作密不算问题，换场景过多才提示） -->
    <NAlert
      v-if="planNotices.length"
      type="warning"
      :bordered="false"
      class="plan-notice"
      data-testid="plan-notice"
    >
      <div class="pn-row">
        <span v-for="(n, i) in planNotices" :key="i" class="pn-line">{{ n }}</span>
      </div>
    </NAlert>

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
                <button type="button" class="op" :disabled="!canEdit"
                        data-testid="btn-add-subject" title="手动新增一个剧情主体（如 GLM 漏抽的人物/物件/场景）"
                        @click="openAddSubject">
                  + 新增主体
                </button>
                <label class="upload-link" :class="{ busy: uploadingRef }">
                  <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                  <span v-if="uploadingRef">上传中…</span>
                  <span v-else>+ 上传</span>
                </label>
              </div>
            </div>

            <!-- P13：剧情主体列表（勾选=参与锚定；定妆图=一致性锚定图） -->
            <p class="ref-group-title font-mono">
              剧情主体：
              <span class="text-secondary" style="letter-spacing: 0">
                （勾选=参与出图；锚定一律用<b>定妆图</b>；没有主体就点右上「+ 新增主体」）
              </span>
            </p>
            <div v-if="planSubjects().length" class="subj-wrap" data-testid="subject-list">
              <input
                v-model="subjectQuery"
                class="text subj-search"
                type="text"
                placeholder="搜索主体（名字 / 别名）"
                data-testid="subj-search"
              />
              <div class="subj-list">
              <div v-for="sub in filteredSubjects()" :key="sub.name" class="subj-row" :data-testid="`subj-${sub.name}`">
                <label class="subj-check" :title="sub.enabled === false ? '未勾选 = 不参与锚定' : '参与锚定'">
                  <input type="checkbox" :checked="sub.enabled !== false"
                         @change="toggleSubject(sub.name, ($event.target as HTMLInputElement).checked)" />
                </label>
                <span class="subj-name">{{ sub.name }}</span>
                <!-- ★ P14 主体设定（性别/年龄/体态）：点一下就能改；缺性别给红色提醒（「宝玉被当女性」的根因） -->
                <span
                  :class="['subj-traits', 'font-mono', { on: !!traitsSummary(sub), warn: traitsWarn(sub) }]"
                  :data-testid="`subj-traits-${sub.name}`"
                  :title="traitsWarn(sub)
                    ? '未填性别：模型只能靠名字猜性别（实测出现过把男性角色画成女性）。点这里补上'
                    : '主体设定：' + (traitsSummary(sub) || '未填') + '（点这里修改）'"
                  @click="openTraits(sub.name)"
                >
                  {{ traitsSummary(sub) || (traitsWarn(sub) ? '⚠ 未填性别' : '设定 ✗') }}
                </span>
                <span v-if="sub.aliases?.length" class="subj-alias font-mono" :title="sub.aliases?.join('、')">
                  {{ sub.aliases?.slice(0, 2).join('/') }}<template v-if="(sub.aliases?.length ?? 0) > 2">…</template>
                </span>
                <!-- ★ 2026-09-16 夜用户要求：体态单独一格显示（以前 build 被塞进“设定”里、常被身高顶掉） -->
                <span v-if="(sub.build ?? '').trim()" class="subj-build font-mono"
                      :title="`体态：${sub.build}（点左边的「设定」可改）`">
                  体态 {{ sub.build }}
                </span>
                <span :class="['subj-portrait', 'font-mono', { on: !!sub.portraitAssetId }]">
                  {{ sub.portraitAssetId ? `定妆图 v${sub.portraitVersion ?? 1}` : '定妆图 ✗' }}
                </span>
                <span class="subj-refs font-mono" :title="'该主体的素材参考图张数'"
                      :aria-label="'该主体的素材参考图张数'">
                  {{ (sub.refs ?? []).length }} 图
                </span>
                <!-- ★ 2026-09-16 夜用户要求：操作按钮固定在最右（以前换行后会跟着行首往左跑） -->
                <span class="subj-ops">
                  <NDropdown
                    trigger="click"
                    size="small"
                    :options="subjectActions(sub)"
                    :disabled="!canEdit"
                    :data-testid="`subj-ops-${sub.name}`"
                    @select="(k: string) => onSubjectAction(k, sub.name)"
                  >
                    <NButton size="tiny" secondary :loading="portraitBusy">操作 ▾</NButton>
                  </NDropdown>
                </span>
              </div>
              </div>
              <p class="subj-hint text-secondary">
                勾选 = 该主体参与出图（出图时身份锚定<b>一律用定妆图</b>，没有定妆图的主体不会注入）。
                <b>主体设定</b>（性别/年龄/身高/体态/性格/外貌）会同时喂给导演 LLM 与出图模型 ——
                不填性别时模型只能靠名字猜（实测把「宝玉」画成了女性）。
                改动后请<b>保存并重新确认</b>（主体设定是就地保存，不必重新确认），否则生成仍读旧稿。
              </p>
            </div>
            <!-- 参考图：标题（在剧情主体展示框下方、参考图格子之上） -->
            <p class="ref-group-title font-mono">
              参考图：
              <span class="text-secondary" style="letter-spacing: 0">
                （<b>只作生成/指定定妆照的依据，不参与出图锚定</b>）
              </span>
            </p>
            <div v-if="refLibrary.length" class="refs-grid">
              <div
                v-for="a in refLibrary.slice(0, 8)"
                :key="a.id"
                :class="['ref-thumb', { sel: refSelected.includes(a.id) && !refUnchecked.includes(a.id) }]"
                :title="refSelected.includes(a.id)
                  ? (refUnchecked.includes(a.id) ? '已加入但未勾选 —— 点一下勾选（它才会作为生成定妆照的参考）' : '已勾选：本次生成定妆照会用它 —— 点一下取消勾选')
                  : '点击加入并勾选（作为生成定妆照的参考图；出图锚定只认绑定的定妆照）'"
                @click="toggleRefTile(a.id)"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <!-- 参考图只留「勾选 / 删除」两态：勾选框是**唯一**的参与开关，缩略图不变暗 -->
                <label v-if="refSelected.includes(a.id)" class="ref-check"
                       title="☑ 勾选＝这次「生成定妆照」用它作参考｜☐ 取消＝留在列表但不参与"
                       @click.stop>
                  <input type="checkbox" :checked="!refUnchecked.includes(a.id)"
                         :data-testid="`ref-check-${a.id.slice(0, 8)}`"
                         @change="toggleRefChecked(a.id, ($event.target as HTMLInputElement).checked)" />
                </label>
                <i v-else class="ref-badge font-mono">REF</i>
                <span class="ref-time font-mono">{{ shortTime(a.createdAt) }}</span>
                <button
                  v-if="refSelected.includes(a.id)"
                  type="button"
                  class="ref-del"
                  :title="a.kind === 'reference' ? '删除这张参考图' : '移出参考图列表（原件保留）'"
                  @click.stop="removeRefAsset(a.id)"
                >
                  ×
                </button>
              </div>
            </div>

            <!-- P13：勾选的主体（显示定妆照，名字只读，只编区域；取消勾选即从显示中移除） -->

            <!-- P12：模型一次能收几张参考图（各模型不同）——超出会被丢弃，提前提示 -->
            <p v-if="refOverModelLimit" class="ref-conflict" data-testid="ref-model-limit">
              当前图片模型「{{ modelRefHint.name }}」最多接收 {{ modelRefHint.max }} 张参考图，
              本方案已绑定 {{ refSelected.length }} 张 —— 超出的会被自动丢弃（多主体镜头建议改用
              支持多图的模型，如 bytedance/seedream-4 / google/nano-banana：image_input）。
            </p>
          </div>

          <!--
            位置总控（右卡）：作用范围（方案默认 / 第N镜 / 第N镜·帧M）→ 预览框 → 主体区域表。
            2026-09-16 改造：以前这张卡按**参考图**分组（主体只有定妆照、没有素材图时就一个条目都没有，
            完全不可用），且与分镜卡里的「画面位置（本镜）」职责重叠。现在统一成按**主体**、
            并且能指定改到「帧」这一层（一个镜可能有多个运镜帧，一帧里可有一个或多个主体框）。
          -->
          <div ref="posPanelRef" class="pos-panel" data-testid="pos-panel">
            <div class="brief-head">
              <span class="font-mono eyebrow">位置总控（主体 / 区域%）</span>
              <button v-if="posSubjects.length > 1" type="button" class="link-btn" data-testid="btn-auto-layout"
                      :title="`按当前范围（${posScopeLabel}）的主体顺序自动摆位`" @click="autoLayoutRegions(false)">
                自动分配
              </button>
            </div>
            <!-- 作用范围：可搜索下拉（输入 4 / 帧2 / 定妆都能命中） -->
            <div class="pos-scope-row">
              <span class="pos-scope-label font-mono">作用范围</span>
              <NSelect
                :value="posScopeValue"
                :options="posScopeOptions"
                size="small"
                filterable
                clearable
                :filter="filterPosScope"
                class="pos-scope-select"
                data-testid="pos-scope"
                placeholder="搜索/选择：分镜（第 N 镜）或定妆照"
                @update:value="(v: string) => setPosScope(v ?? 'plan')"
              />
              <span class="pos-scope-hint text-secondary">
                {{ posScope.kind === 'plan'
                  ? '全片默认位置；某一镜/某一帧没单独设时就用它'
                  : posScope.kind === 'portrait'
                    ? `只看/只改「${posScope.subject}」的定妆照在全片默认画面里的位置`
                    : `帧级 > 镜级 > 方案默认；当前编辑：${posScopeLabel}` }}
              </span>
            </div>

            <div class="pos-frame" :style="{ aspectRatio: posAspectCss }">
              <div class="pos-third pos-third-v1" /><div class="pos-third pos-third-v2" />
              <div class="pos-third pos-third-h1" /><div class="pos-third pos-third-h2" />
              <!--
                ★ 2026-09-16（用户要求）：预览框里直接显示**主体定妆照**（而不是只有线框），
                并用图片来拖动 —— 看得到人脸/半身占多大、在哪儿，比空盒子直观得多。
              -->
              <div
                v-for="it in refPreviewItems.filter((i) => i.region)"
                :key="it.id"
                class="pos-box"
                :class="{ inherited: it.inherited }"
                :style="{
                  left: (it.region?.x ?? 0) + '%',
                  top: (it.region?.y ?? 0) + '%',
                  width: (it.region?.w ?? 100) + '%',
                  height: (it.region?.h ?? 100) + '%',
                  borderColor: it.color,
                  background: it.inherited ? 'transparent' : it.color + '22',
                }"
                @pointerdown="onBoxPointerDown($event, it, 'move')"
                @pointermove="onBoxPointerMove"
                @pointerup="onBoxPointerUp"
                @pointercancel="onBoxPointerUp"
              >
                <img v-if="it.thumb" class="pos-img" :src="it.thumb" alt="" draggable="false" />
                <span class="pos-label font-mono" :style="{ color: it.color }">
                  {{ it.label }}<template v-if="it.inherited"> · 继承</template>
                </span>
                <span class="pos-resize" @pointerdown.stop="onBoxPointerDown($event, it, 'resize')" />
              </div>
              <p v-if="!refPreviewItems.some((i) => i.region)" class="pos-empty text-secondary">
                {{ posScope.kind === 'portrait'
                  ? '该主体还没有定妆照（或未设位置）：先到上面「操作 ▾ → 生成定妆照」'
                  : '尚无位置：点右上「自动分配」，或拖预览框里的图调整' }}
              </p>
            </div>

            <p class="ref-hint text-secondary">
              直接<b>拖动定妆照图片</b>移动、右下角拖动缩放；也可在下方「区域%」直接填（x/y=左上角，w/h=宽高，0–100；w/h 越大=越近越大）。
              这里是唯一的位置总控：作用范围选「方案默认」=全片；选「第 N 镜」/「第 N 镜 · 帧 M」=只改那一镜/那一帧；
              选「定妆照 · 某主体」=只看/只改那个主体的全片默认位置。
              切到某一镜时，若该镜还没设过位置，会按该镜主体**自动均分**一次（改过就保留你的结果，不再自动抹平）。
              注入时每帧用自己那份：<span class="font-mono">keyframes[j].layout</span> 优先于 <span class="font-mono">shots[].layout</span>。
              GPU(Comfy) 会把位置与「第几张参考图（Picture N）是哪个主体」写进提示词（多角色同框防串脸、防错位）。
            </p>

            <!-- 主体区域表：按**主体**列出（不依赖有没有素材图），可直接填值 -->
            <div v-if="posSubjects.length" class="ref-subjects pos-subjects" data-testid="subject-ref-block">
              <p class="ref-subjects-title font-mono">
                主体位置<span class="text-secondary">（{{ posScopeLabel }}；共 {{ posSubjects.length }} 个主体）</span>
              </p>
              <div v-for="sub in posSubjects" :key="sub" class="ref-subject-block">
                <div class="ref-subject-row">
                  <img v-if="subjectThumbByName(sub)" :src="subjectThumbByName(sub)" class="ref-subject-thumb" alt="" />
                  <span v-else class="ref-subject-thumb empty font-mono">未定妆</span>
                  <span class="ref-subject-name">{{ sub }}</span>
                  <span class="ref-subject-tag font-mono" :class="{ off: subjectAnchorInfo(sub).warn }"
                        :title="subjectAnchorInfo(sub).title" :data-testid="'anchor-' + sub">
                    {{ subjectAnchorInfo(sub).label }}
                  </span>
                  <!--
                    ★ 2026-09-16 夜（用户要求）：脸太小提前预告。
                    位置框只影响构图（不会裁/缩参考图 —— 已 md5 逐字节验证），但框画得远、生成图里的脸就小，
                    小到一定程度定妆照的身份锤定就失效（实测：h=1.0 → 47~59px 掉身份；h=0.76 → 100px 相似度 0.609）。
                    阈值：<64px 红、64~96px 黄、≥96px 绿（详见 estFacePx 注释）。
                  -->
                  <span v-if="faceSizeHint(sub)" class="font-mono"
                        :class="['pos-face', faceSizeHint(sub)!.level]"
                        :data-testid="'face-hint-' + sub"
                        :title="faceSizeHint(sub)!.level === 'ok'
                          ? `估计生成图里这张脸约 ${faceSizeHint(sub)!.px}px —— 身份锤定够用（≥96px）`
                          : faceSizeHint(sub)!.level === 'warn'
                            ? `估计只有 ${faceSizeHint(sub)!.px}px（64~96px）：能用但脆弱，容易不像；把框改小（更近）会更稳`
                            : `估计只有 ${faceSizeHint(sub)!.px}px（<64px）：身份锤定基本失效，出图容易换脸/不像 —— 请把框改小（更近/半身）或拆镜`">
                    脸≈{{ faceSizeHint(sub)!.px }}px
                  </span>
                  <span v-if="posIsInherited(sub)" class="ref-subject-tag font-mono" title="本层未设定，继承上一层的框">
                    继承{{ posScope.kind === 'frame' ? '镜/方案' : '方案' }}默认
                  </span>
                  <button v-if="posOwnBox(sub)" type="button" class="link-btn pos-clear" title="清掉本层的位置，回到继承"
                          @click="clearPos(sub)">
                    清除本层
                  </button>
                </div>
                <div class="ref-region-row">
                  <span class="ref-region-label font-mono">区域%</span>
                  <input
                    v-for="k in (['x', 'y', 'w', 'h'] as const)"
                    :key="k"
                    class="text ref-region-input"
                    type="text"
                    inputmode="decimal"
                    :placeholder="k"
                    :data-testid="`pos-${k}-${sub}`"
                    :value="posPctStr(sub)[k]"
                    @change="setPosField(sub, k, ($event.target as HTMLInputElement).value)"
                    @keyup.enter="setPosField(sub, k, ($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>
            </div>

          <!-- 红色告警（不要计数） -->
          <div class="ref-meta">
            <p v-if="refOverModelLimit" class="ref-conflict" data-testid="ref-model-limit">
              当前图片模型「{{ modelRefHint.name }}」最多接收 {{ modelRefHint.max }} 张参考图，
              本方案已绑定 {{ refSelected.length }} 张 —— 超出的会被自动丢弃（多主体镜头建议改用
              支持多图的模型，如 bytedance/seedream-4 / google/nano-banana：image_input）。
            </p>
            <p v-if="cameraIntentWithRefs" class="ref-conflict">
              检测到「背影/过肩/机位」类构图诉求：若参考图里带进来一张场景/构图基准图，可能把构图拉回参考视角。
              建议把构图/机位写进「视角/前景/主体朝向」字段。（参考图不参与出图锚定 —— 关键帧只认绑定的定妆照）
            </p>
          </div>
          </div>
        </div>

        <!-- P13：未确认横幅（一键确认 + 变更摘要） -->
        <div v-if="confirmBanner" class="confirm-banner" data-testid="confirm-banner">
          <span class="cb-title font-mono">
            {{ detApproved ? '草稿有未保存改动' : `当前是未确认版本 v${approvedRev?.revisionNo != null ? approvedRev.revisionNo + 1 : '?'}` }}
          </span>
          <span class="cb-diff text-secondary">
            变更：{{ changeSummary.join('、') }}
          </span>
          <span class="cb-ops">
            <NButton v-if="dirty" size="tiny" secondary data-testid="cb-save" @click="handleSave">保存</NButton>
            <NButton size="tiny" type="primary" data-testid="cb-approve" @click="handleApprove">确认并使用此版本</NButton>
          </span>
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
                  :model-cap-disabled="gpuMotionLane"
                  :model-cap-hint="modelCapHint"
                  :busy-shot="shotBusy"
                  :preview-busy="previewBusy"
                  :audio-preview="audioPreview"
                  :durations="durations"
                  :locked-shots="lockedShots"
                  @toggle-lock="toggleShotLock"
                  @approve-shot="handleApproveShot"
                  @ai-prompt="openAiRewrite"
                  @calibrate-durations="calibrateAllDurations"
                  @edit-layout-on-top="editPosOnTop"
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
          <!--
            2026-09-16（用户要求）：按**下片流程**从前到后排列，不再按“重要性”乱序：
            关键帧(still) → 运动(motion) → 配音(voice) → 对口型(lipsync) → 配乐(bgm)。
            「按配音校准时长」已移回方案编辑器「镜头时长」标题旁（它改的是镜头时长，不是任务）。
            注意：**不能用 v-if 按状态隐藏** —— 否则未确认方案/有进行中任务时按钮会“消失”，
            用户找不到入口；改为常显 + :disabled + title 说明前置条件。
          -->
          <div class="jobs-actions" data-testid="job-gen-actions">
            <span v-if="activeJobCount" class="state-hint font-mono gen-live" data-testid="job-live-hint">
              {{ freshActiveCount || activeJobCount }} 个进行中…
            </span>
            <NButton
              size="small"
              type="primary"
              :loading="genBusy"
              data-testid="btn-gen-jobs"
              :title="isVideoNow ? '第一步：按确认稿逐镜出关键帧(still)' : '开始生成'"
              @click="withShotPicker(isVideoNow ? '生成关键帧(still)' : '开始生成', (nos) => startGeneration(nos), 'still')"
            >
              {{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}
            </NButton>
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
              :title="motionReady ? '第二步：把已确认的关键帧做成运动片段(motion)' : '先出关键帧(still)并确认后可用（两段式 §11.3）'"
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
              :title="detApproved ? '第三步：自托管 CosyVoice，逐镜台词 → 配音' : '需先确认方案（右上角「确认」）后生成配音；未确认的镜头不能配音'"
              @click="withShotPicker('生成配音(voice)', (nos) => startVoice(nos), 'voice')"
            >
              生成配音(voice)
            </NButton>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              :disabled="!detApproved"
              data-testid="btn-lipsync-jobs"
              :title="detApproved
                ? '第四步：对口型 —— 用该镜配音驱动嘴型（需本机已装口型工作流，见 docs/lipsync-setup.md）。'
                  + '会独占本机显存（~7.9/8GiB），跑的时候别同时排其它 GPU 任务；约 2.5 分钟/秒视频'
                : '需先确认方案'"
              @click="openLipsyncPicker()"
            >
              对口型
            </NButton>
            <NButton
              v-if="isVideoNow"
              size="small"
              secondary
              :loading="genBusy"
              data-testid="btn-bgm-jobs"
              :disabled="!detApproved"
              :title="detApproved ? '第五步：自托管音乐生成，按 music_mood 生成整片 BGM' : '需先确认方案（右上角「确认」）后生成配乐'"
              @click="startBgm"
            >
              生成配乐(bgm)
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
            <span class="job-kind font-mono">[{{ KIND_LABEL[j.kind] ?? j.kind }}{{ j.payload?.preview ? '·试听' : '' }}<template v-if="j.kind === 'still' || j.kind === 'clip' || j.kind === 'voice' || j.kind === 'lipsync'"> · 第{{ j.payload?.shot_no ?? '—' }}镜</template><template v-if="j.kind === 'voice' && j.payload?.line_index != null"> · 第{{ (j.payload.line_index ?? 0) + 1 }}段</template><template v-if="j.payload?.subject"> · {{ j.payload.subject }}</template><template v-if="j.kind === 'bgm' && j.payload?.mood"> · {{ j.payload.mood }}</template><template v-if="j.payload?.frame_label"> · {{ j.payload.frame_label }}</template>]</span>
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
          <div v-for="a in galleryForTab" :key="a.id"
               :class="['g-item', { manage: galManage, sel: galSel.includes(a.id), newest: galNewestHint === a.id }]"
               :data-testid="`asset-${a.id}`">
            <label v-if="galManage" class="g-sel">
              <input type="checkbox" :checked="galSel.includes(a.id)" @change="toggleGalSel(a.id)" />
            </label>
            <button v-if="galManage" type="button" class="g-del" :disabled="galBusy"
                    :title="'删除此' + a.kind" @click="removeAssetOne(a.id)">
              ×
            </button>
            <audio
              v-if="isAudioAsset(a) && galUrls[a.id]"
              :src="galUrls[a.id]"
              class="g-audio"
              controls
              preload="metadata"
            />
            <video
              v-else-if="isVideoAsset(a) && galUrls[a.id]"
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
              <!-- ★ 2026-09-16 夜：worker 的显存取舍说明（如“显存不够 → 分辨率自动降到 704x384”）要看得见 -->
              <span v-if="a.notes" class="g-note" :title="a.notes" data-testid="asset-note">⚠</span>
              <span class="g-actions">
                <button v-if="galUrls[a.id]" type="button" class="g-max" title="沉浸预览/播放"
                        @click.stop="openImmersive(a.id, a.mime ?? '')">
                  ⤢
                </button>
                <a v-if="galUrls[a.id]" :href="galUrls[a.id]" :download="'weaveora-' + a.id.slice(0, 8) + '.' + assetExt(a)" title="下载">↓</a>
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

      <!-- ★ 2026-09-16 夜（用户要求 a）：生成**前**的“脸会过小”确认闸门（不阻断，可强推） -->
      <NModal v-model:show="faceWarnOpen" preset="card" title="⚠ 这些镜的脸会太小（身份锚定会失效）"
              style="max-width: 560px" data-testid="face-warn-modal">
        <p class="text-secondary" style="margin: 0 0 10px; font-size: 13px;">
          按当前位置框估算，下面这些主体的脸在生成图里会小于 <b>{{ FACE_PX_RED }}px</b>
          （实测这一档只能检出 1 张脸甚至 0 张，与定妆照的相似度掉到 0.19~0.30 → 容易“换脸/不像”）。
          现在拦下来，是为了**不白烧这几分钟的 GPU**（而不是生成完再告警）。
        </p>
        <ul class="face-warn-list font-mono">
          <li v-for="r in faceWarnRows" :key="r.shot + '-' + r.sub">
            第 {{ r.shot }} 镜 · {{ r.sub }} —— 估计脸宽 <b>~{{ r.px }}px</b>
          </li>
        </ul>
        <p class="text-secondary" style="font-size: 12px; margin: 8px 0 0">
          怎么改：把该主体的框**改小（更近/半身）** —— 框高 h 越小脸越大（脸≈0.09×画幅高÷h）；或把 3 主体镜拆成 ≤2 主体。
        </p>
        <div class="ai-actions">
          <NButton size="small" @click="faceWarnOpen = false">先去调框</NButton>
          <NButton size="small" type="primary" :loading="genBusy" data-testid="face-warn-anyway"
                   @click="confirmFaceWarn">
            仍然生成
          </NButton>
        </div>
      </NModal>

      <!-- 沉浸式预览（大图/大视频） -->
      <NModal v-model:show="motionOpen" preset="card" :title="'生成运动(motion)'" style="max-width: 420px">
        <div class="motion-form">
          <p class="text-secondary">
            帧数不用自己算：按**镜头时长 × 原生 {{ MOTION_NATIVE_FPS }}fps** 自动填（本镜约 {{ motionShotSec }}s → {{ motionNeedFrames }} 帧）。
            它只当**上限**用（worker 实际取 min(时长×{{ MOTION_NATIVE_FPS }}, 帧数)，填大了无害，填小了会把动作压短）。
            <span v-if="motionLimitSource" class="state-hint font-mono">· 上限来源：{{ motionLimitSource }}</span>
          </p>
          <NInputNumber v-model:value="motionFrames" :min="MOTION_MIN" :max="MOTION_MAX" :step="4" style="width: 180px" />
          <p class="font-mono motion-sec" :class="{ warn: motionTooFew }">
            当前 {{ motionFrames }} 帧 ÷ {{ MOTION_NATIVE_FPS }}fps ≈ {{ motionFramesSec.toFixed(2) }}s
            <template v-if="motionTooFew">
              ⚠ 比本镜 {{ motionShotSec }}s 短 {{ (motionShotSec - motionFramesSec).toFixed(2) }}s
              → 整段动作会被压进这 {{ motionFramesSec.toFixed(2) }}s（看起来**快进**），尾部由补帧**静止**补齐。
              建议降到 480p 分辨率（保时长）而不是降帧。
            </template>
            <template v-else>✓ 够覆盖本镜 {{ motionShotSec }}s</template>
          </p>
          <div class="motion-ops">
            <NButton size="small" @click="motionOpen = false">取消</NButton>
            <NButton size="small" type="primary" @click="confirmMotion">开始生成</NButton>
          </div>
        </div>
      </NModal>

      <!--
        P14 主体设定（人物档案）：性别/年龄/身高/体态/性格/外貌。
        为什么必须让用户能填：定妆照只约束长相；LLM 与视觉模型还需要知道性别与年龄段——
        实测「宝玉」被画成女性，根因就是主体只有名字没属性，模型只能猜。
      -->
      <NModal v-model:show="traitsOpen" preset="card"
              :title="`主体设定 · ${traitsSubject}`" style="max-width: 620px" data-testid="traits-modal">
        <p class="text-secondary" style="margin: 0 0 12px; font-size: 12.5px">
          这份档案会写进：导演与你 AI 重写提示词的上下文、每一镜的正词（<span class="font-mono">Picture N = 主体[档案]</span>）、
          以及该主体的定妆照提示词 —— 三处口径一致，模型才不会自相矛盾。
        </p>
        <div class="ai-fields">
          <label class="ai-label">性别（最关键；决定 he/she、man/woman、五官与体型）</label>
          <NRadioGroup v-model:value="traitsDraft.gender" size="small" data-testid="traits-gender">
            <NRadioButton value="male">男 male</NRadioButton>
            <NRadioButton value="female">女 female</NRadioButton>
            <NRadioButton value="other">未定／不适合</NRadioButton>
          </NRadioGroup>
          <label class="ai-label">年龄（数字或年龄段，如 17 / 十六七岁 / 中年）</label>
          <NInput v-model:value="traitsDraft.age" :disabled="traitsKind !== 'person'"
                  placeholder="如：17" data-testid="traits-age" />
          <label class="ai-label">身高（如 178cm / 高挑；也可只写体态）</label>
          <NInput v-model:value="traitsDraft.height" placeholder="如：178cm" data-testid="traits-height" />
          <label class="ai-label">体态特征（清瘦 / 丰膾 / 嫗小 / 魁梧 / 肩宽背厚…）</label>
          <NInput v-model:value="traitsDraft.build" placeholder="如：清瘦、身形额长" data-testid="traits-build" />
          <label class="ai-label">性格（影响表情/眼神/动作的写法）</label>
          <NInput v-model:value="traitsDraft.personality" placeholder="如：多情敏感、外表温柔内心刚强"
                  data-testid="traits-personality" />
          <label class="ai-label">外貌 / 服饰要点（发式、服色、配饰 —— 跨镜一致性的关键）</label>
          <NInput v-model:value="traitsDraft.appearance" type="textarea"
                  :autosize="{ minRows: 2, maxRows: 5 }"
                  placeholder="如：面若中秋之月，大红箭袖，项上金螭璎珞，束发嵌宝紫金冠"
                  data-testid="traits-appearance" />
        </div>
        <div class="ai-actions">
          <NButton size="small" :disabled="traitsBusy" @click="traitsOpen = false">取消</NButton>
          <NButton size="small" type="primary" :loading="traitsBusy" data-testid="traits-save"
                   @click="confirmTraits">
            保存（就地生效）
          </NButton>
        </div>
      </NModal>

      <!--
        P13b 生成定妆图（用户要求：定妆图也要像分镜一样**弹正/负向提示词**，用参考图 + 提示词出新图）。
        为什么必须弹：定妆图是所有分镜的唯一身份锚定，以前只能用后端写死的模板，想控（背景色/角度/风格）只能改代码。
      -->
      <NModal v-model:show="portraitOpen" preset="card" title="生成定妆照（可改正/负向提示词）"
              style="max-width: 720px" data-testid="portrait-modal">
        <p class="text-secondary" style="margin: 0 0 10px; font-size: 13px;">
          主体：<b>{{ portraitSubject }}</b> ·
          <span :class="{ 'portrait-ref-warn': portraitRefIds.length === 0 }">{{ portraitRefHint }}</span>
          <span class="font-mono" v-if="portraitRefIds.length">{{ portraitRefIds.map((i) => '#' + i.slice(-4)).join(' ') }}</span>
          <template v-if="traitsSummary(planSubjects().find((x) => x.name === portraitSubject) ?? ({ name: '', } as PlanSubject))">
            · 主体设定：{{ traitsSummary(planSubjects().find((x) => x.name === portraitSubject) ?? ({ name: '' } as PlanSubject)) }}
          </template>
          <template v-else>
            · <button type="button" class="link-btn" @click="openTraits(portraitSubject)">补主体设定（性别/年龄…）</button>
          </template>
        </p>
        <template v-if="portraitPromptBusy">
          <div class="g-loading" style="padding: 20px 0">读取默认提示词…</div>
        </template>
        <template v-else>
          <div class="ai-fields">
            <label class="ai-label">正向提示词（定妆规范 + 你的要求；可改）</label>
            <NInput v-model:value="portraitPositive" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }"
                    data-testid="portrait-positive" />
            <label class="ai-label">负向提示词（可改）</label>
            <NInput v-model:value="portraitNegative" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }"
                    data-testid="portrait-negative" />
          </div>
          <p class="text-secondary" style="font-size: 11.5px; margin: 10px 0 0">
            提示：定妆照建议**纯色背景、正面半身、中性表情**（后续它会被当作锚定图喂进每一镜；
            带白底/影棚背景的原图容易把构图带偏，worker 会自动在负词里追加“白色背景/证件照”类词）。
            只改上面这两栏；确认后会**用这些参考图 + 这份提示词**生成新的一版定妆照。
          </p>
          <div class="ai-actions">
            <NButton size="small" :disabled="portraitGenBusy" @click="portraitOpen = false">取消</NButton>
            <NButton size="small" :disabled="portraitGenBusy" @click="loadPortraitPrompt">恢复默认词</NButton>
            <NButton size="small" type="primary" :loading="portraitGenBusy"
                     data-testid="portrait-generate"
                     :title="portraitNoRef ? '零参考：会退化成纯文生图，可能出噪声图（仍可继续，会二次确认）' : '用上面的参考图 + 提示词生成定妆照'"
                     @click="confirmPortrait">
              生成定妆照
            </NButton>
          </div>
        </template>
      </NModal>

      <!-- 新增剧情主体（用户要求：剧情主体要能手动新增，不能只靠「一键生成主体」抽取） -->
      <NModal v-model:show="addSubjOpen" preset="card" title="新增剧情主体"
              style="max-width: 560px" data-testid="add-subject-modal">
        <div class="ai-fields">
          <label class="ai-label">主体名（必填；后续在分镜/提示词里就用这个名字）</label>
          <NInput v-model:value="addSubjName" placeholder="如：宝玉 / 赤兔马 / 通灵宝玉 / 太虚幻境"
                  data-testid="add-subject-name" @keyup.enter="confirmAddSubject" />
          <label class="ai-label">类型（决定定妆图模板：人物 / 载具装备 / 物件 / 场景）</label>
          <NRadioGroup v-model:value="addSubjKind" size="small" data-testid="add-subject-kind">
            <NRadioButton value="person">人物</NRadioButton>
            <NRadioButton value="vehicle">载具/装备</NRadioButton>
            <NRadioButton value="object">物件</NRadioButton>
            <NRadioButton value="scene">场景</NRadioButton>
          </NRadioGroup>
          <label class="ai-label">别名（选填，用逗号/顿号/空格分隔；镜文里写别名也能自动绑定）</label>
          <NInput v-model:value="addSubjAliases" placeholder="如：贾宝玉、宝二爷" data-testid="add-subject-aliases" />
        </div>
        <p class="text-secondary" style="font-size: 11.5px; margin: 10px 0 0">
          新增后会**就地保存**（不需重新确认）。接着在列表里用「操作 ▾ → 生成定妆照」给它出定妆图，
          并「把最新定妆照设为锚定图」，这样分镜出图才会用它做身份锚定。
        </p>
        <div class="ai-actions">
          <NButton size="small" :disabled="addSubjBusy" @click="addSubjOpen = false">取消</NButton>
          <NButton size="small" type="primary" :loading="addSubjBusy" data-testid="add-subject-confirm"
                   @click="confirmAddSubject">
            新增主体
          </NButton>
        </div>
      </NModal>

      <!-- AI 提示词确认（①：可确认/取消/微调后应用） -->
      <NModal v-model:show="aiOpen" preset="card" title="AI 生成提示词（可确认或取消）" style="max-width: 760px">
        <p class="text-secondary" style="margin: 0 0 10px; font-size: 13px;">
          <template v-if="!aiImageMode">第 {{ aiShot?.shot_no ?? '' }} 镜 · </template>中文：{{ aiShot?.action ?? aiShot?.zh ?? '' }}
        </p>
        <!-- 语言：选中文则正/负词（含运镜关键帧）全部中文；切换即重新生成 -->
        <div class="ai-lang-row">
          <span class="ai-label" style="margin: 0">提示词语言</span>
          <NRadioGroup :value="aiLang" size="small" data-testid="ai-lang"
                       @update:value="(v) => changeAiLang(v as 'zh' | 'en')">
            <NRadioButton value="zh">中文</NRadioButton>
            <NRadioButton value="en">English</NRadioButton>
          </NRadioGroup>
          <span class="text-secondary" style="font-size: 11.5px">
            Qwen 系模型对中文理解好；选哪种，正/负词与运镜关键帧都用哪种
          </span>
        </div>
        <template v-if="aiBusy">
          <div class="g-loading" style="padding: 24px 0">AI 生成中…</div>
        </template>
        <template v-else-if="aiPreview">
          <div class="ai-fields">
            <label class="ai-label">本镜正向提示词（可微调）</label>
            <NInput v-model:value="aiPreview.positive_prompt" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" />
            <label class="ai-label">本镜负向提示词（可微调）</label>
            <NInput v-model:value="aiPreview.negative_prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" />
          </div>
          <!-- 运镜关键帧：每帧用自己的正词出图，必须逐帧重写（否则帧提示词还是旧稿/旧语言） -->
          <div v-if="aiPreview.keyframes?.length" class="ai-kf-block">
            <p class="ai-label" style="margin: 0 0 6px">
              运镜关键帧（{{ aiPreview.keyframes.length }} 帧 · 生成时逐帧出图、motion 用首/尾帧）
            </p>
            <div v-for="(kf, i) in aiPreview.keyframes" :key="i" class="ai-kf-item">
              <span class="ai-kf-tag font-mono">
                #{{ i + 1 }} {{ aiFrames[i]?.label || (i === 0 ? '起始帧' : (i === aiPreview.keyframes.length - 1 ? '结束帧' : '中段')) }}
              </span>
              <span v-if="aiFrames[i]?.composition" class="ai-kf-comp text-secondary">{{ aiFrames[i].composition }}</span>
              <NInput v-model:value="kf.positive_prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" />
            </div>
          </div>
          <div class="ai-actions">
            <NButton size="small" @click="aiOpen = false">取消</NButton>
            <NButton size="small" type="primary" data-testid="ai-apply" @click="applyAiPrompt">
              应用并写入{{ aiPreview?.keyframes?.length ? '（含关键帧）' : '' }}
            </NButton>
          </div>
        </template>
      </NModal>

      <!--
        P5 主体闸门：生成前发现「镜文本没点名主体」的镜 → 让用户勾选本镜主体（或标为空镜）。
        用户实测问题：不点名时后端会“全部主体注入”→串脸；个别帧看起来没拿到参考图。
      -->
      <NModal v-model:show="castOpen" preset="card" title="这些镜头没点名主体，请勾选本镜出镜主体" style="max-width: 720px"
              data-testid="cast-modal">
        <p class="text-secondary" style="margin: 0 0 10px; font-size: 12.5px">
          镜头的「画面动作 / 正向提示词」里没出现任何主体名，系统无法自动绑定参考图
          （会退化成把<strong>全部主体</strong>的参考图都注入 → 多角色容易串脸，或看起来没参考图）。
          请为下列镜头勾选本镜真正出镜的主体；确实是纯环境/道具镜请点「空镜（无主体）」。
        </p>
        <div v-for="r in castRows" :key="r.shot.shot_no" class="cast-row-card">
          <p class="cast-row-title">
            第 {{ r.shot.shot_no }} 镜 ·
            <span class="text-secondary">{{ (r.shot.action ?? '').slice(0, 60) }}</span>
          </p>
          <div class="cast-row-items">
            <label v-for="n in r.names" :key="n" class="cast-row-item">
              <input type="checkbox" :checked="r.picked.includes(n)" :data-testid="`cast-pick-${r.shot.shot_no}-${n}`"
                     @change="(e) => {
                       const on = (e.target as HTMLInputElement).checked
                       r.picked = on ? [...r.picked, n] : r.picked.filter((x) => x !== n)
                       if (on) r.empty = false
                     }" />
              <span>{{ n }}</span>
            </label>
            <label class="cast-row-item">
              <input type="checkbox" :checked="r.empty" :data-testid="`cast-empty-${r.shot.shot_no}`"
                     @change="(e) => { r.empty = (e.target as HTMLInputElement).checked; if (r.empty) r.picked = [] }" />
              <span>空镜（无主体）</span>
            </label>
          </div>
        </div>
        <div class="ai-actions">
          <NButton size="small" @click="castOpen = false">取消（不生成）</NButton>
          <NButton size="small" type="primary" :loading="castBusy" data-testid="cast-confirm" @click="confirmCast">
            记录选择
          </NButton>
        </div>
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
      <!-- P13：别名管理（LLM 可能把某个别名认错主体，需能删） -->
      <NModal
        v-model:show="aliasOpen"
        preset="card"
        :title="`「${aliasSubject}」的别名`"
        style="max-width: 460px"
        data-testid="alias-modal"
      >
        <p class="alias-tip text-secondary">
          这些别名来自「一键生成主体」，用于分镜里识别同一主体。若某个别名其实不是这个主体（例如把「浅蔷薇色纱衣女子」错认成秦可卿），删掉它即可。
        </p>
        <div class="alias-list">
          <div
            v-for="a in (planSubjects().find((x) => x.name === aliasSubject)?.aliases ?? [])"
            :key="a"
            class="alias-row"
          >
            <span class="alias-name">{{ a }}</span>
            <NButton size="tiny" quaternary type="error" @click="removeAlias(aliasSubject, a)">删除</NButton>
          </div>
          <p v-if="!(planSubjects().find((x) => x.name === aliasSubject)?.aliases ?? []).length" class="text-secondary alias-tip">
            暂无别名
          </p>
        </div>
        <div class="alias-add">
          <NInput v-model:value="aliasDraft" size="small" placeholder="补一个别名，回车添加" @keyup.enter="addAlias(aliasSubject)" />
          <NButton size="small" secondary @click="addAlias(aliasSubject)">添加</NButton>
        </div>
      </NModal>

      <!-- P12：分镜勾选弹窗（>3 镜时生成前先选镜 + 可顺手封版） -->
      <ShotPickerDialog
        v-model:show="pickOpen"
        :title="pickCtx?.title ?? '选择分镜'"
        :hint="pickHint"
        :shots="pickerShots"
        :locked="lockedShots"
        :busy="pickBusy"
        :kind="pickerKind"
        @confirm="onShotPicked"
        @pick-face="openFacePicker"
        @set-base="onLipsyncBase"
      />

      <!-- P13：对口型「谁在哪张脸」点选（多人同框必需） -->
      <LipsyncFacePickerDialog
        v-model:show="facePickOpen"
        :shot-no="facePickShotNo ?? 0"
        :speakers="facePickSpeakers"
        :targets="facePickTargets"
        :media-url="facePickMedia"
        :is-video="facePickNewest?.kind === 'clip'"
        @pick="onFacePick"
        @clear="onFaceClear"
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

.confirm-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 12px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
  background: color-mix(in srgb, var(--wv-accent) 10%, transparent);
  border-radius: 10px;
  font-size: 12.5px;
}
.cb-title { color: var(--wv-accent-text); }
.cb-diff { flex: 1 1 auto; }
.cb-ops { display: inline-flex; gap: 6px; }

.subj-wrap { display: flex; flex-direction: column; gap: 6px; margin: 6px 0 8px; }
.subj-search {
  width: 100%;
  padding: 4px 8px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface);
  color: inherit;
  font-size: 12.5px;
}
.subj-list { max-height: 240px; overflow: auto; }
.alias-tip { margin: 0 0 8px; font-size: 12px; line-height: 1.8; }
.alias-list { display: flex; flex-direction: column; gap: 4px; max-height: 220px; overflow: auto; }
.alias-row {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 4px 8px; border: 1px solid var(--wv-line); border-radius: 8px;
  background: var(--wv-surface-sunken); font-size: 12.5px;
}
.alias-add { display: flex; gap: 8px; margin-top: 10px; }

.ref-subject-name { font-size: 13px; }
.ref-subject-tag { font-size: 10.5px; color: var(--wv-success, #7BC47F); }
.ref-subject-tag.off { color: var(--wv-text-4); }
.ref-subject-thumb.empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9.5px;
  color: var(--wv-text-4);
  background: var(--wv-surface-sunken);
}
.ref-meta { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.ref-group-title {
  margin: 10px 0 2px;
  font-size: 11.5px;
  color: var(--wv-text-3);
  letter-spacing: 0.06em;
}

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
/* 体态单独一格（用户要求：列表里要看得见体态） */
.subj-build {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--wv-accent) 12%, transparent);
  color: var(--wv-text-3, var(--wv-text-2));
  white-space: nowrap;
}
.subj-refs { font-size: 10.5px; color: var(--wv-text-4); }
/* 操作按钮恒定贴右（flex-wrap 换行后也靠右，不再跟着内容左跑） */
.subj-ops { margin-left: auto; display: inline-flex; align-items: center; }
/* ★ P14 主体设定（性别/年龄/体态）：点一下就能改；缺性别时用警示色 ——
   因为「没填性别」就是实测「宝玉被画成女性」的根因，必须在列表上看得见。 */
.subj-traits {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px dashed var(--wv-line);
  color: var(--wv-text-4);
  cursor: pointer;
  white-space: nowrap;
}
.subj-traits:hover { border-color: var(--wv-accent); color: var(--wv-accent-text); }
.subj-traits.on { border-style: solid; color: var(--wv-text-2); }
.subj-traits.warn {
  border-style: solid;
  border-color: color-mix(in srgb, var(--wv-danger, #c45c4a) 60%, var(--wv-line));
  color: var(--wv-danger, #c45c4a);
}
.subj-hint { margin: 2px 0 0; font-size: 11px; line-height: 1.6; }
.portrait-ref-warn { color: var(--wv-danger, #c45c4a); }
/* “脸过小”生成前闸门 + motion 帧数↔时长提示（2026-09-16 夜） */
.face-warn-list { margin: 0; padding-left: 18px; font-size: 12.5px; line-height: 1.9; color: var(--wv-danger, #c45c4a); }
.motion-sec { font-size: 12px; margin: 8px 0 0; color: var(--wv-text-3, var(--wv-text-2)); }
.motion-sec.warn { color: var(--wv-danger, #c45c4a); }
/* 资产卡上的 ⚠（worker 因显存做的取舍说明，如自动降分辨率）：悬停看全文 */
.g-note { margin-left: 4px; color: var(--wv-warn, #C8A25E); cursor: help; }
/* 位置总控：估计脸宽告警（<64px 红 / 64~96px 黄 / ≥96px 绿） */
.pos-face { font-size: 10.5px; white-space: nowrap; }
.pos-face.ok { color: var(--wv-success, #7BC47F); }
.pos-face.warn { color: var(--wv-warn, #C8A25E); }
.pos-face.bad { color: var(--wv-danger, #c45c4a); font-weight: 600; }
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
/* 引擎不在线提示条（黄色、仅提示不拦）：见模板 data-testid="engine-offline-notice" */
.engine-offline { margin-bottom: 4px; }
.eo-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; line-height: 1.7; }
/* 场景切换提示条（黄色、仅提示不拦）：见模板 data-testid="plan-notice" */
.plan-notice { margin-bottom: 4px; }
.pn-row { display: flex; flex-direction: column; gap: 4px; line-height: 1.7; }
.pn-line { font-size: 13px; }
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
.ref-row {
  display: grid;
  grid-template-columns: minmax(0, 360px) minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 16px;
  /* P13：两卡并排且**始终等高**（以前左卡高右卡矮，页面看着不齐） */
  align-items: stretch;
}
.ref-row > .refs-panel,
.ref-row > .pos-panel {
  height: 100%;
}
.refs-panel { width: auto; overflow: auto; }
.pos-panel {
  display: flex; flex-direction: column; gap: 8px; min-width: 0;
  /* 等高：内容多时自己滚，不把另一张卡撑变形 */
  overflow: auto;
  padding: 14px; background: var(--wv-surface);
  border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m);
}
.pos-scope-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 6px 0 8px;
}
.pos-scope-label {
  font-size: 10.5px;
  letter-spacing: 0.14em;
  color: var(--wv-text-4);
}
.pos-scope-select {
  width: 250px;
  max-width: 60vw;
}
.pos-scope-hint {
  font-size: 11px;
}
.pos-box.inherited {
  border-style: dashed;
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
  overflow: hidden;
}
/* ★ 2026-09-16（用户要求）：预览框里显示**定妆照图片**，拖动图片就是拖位置（不再是空线框）。
   pointer-events:none 让图片不抢指针事件（否则浏览器原生图片拖拽/选中会干扰 pointerdown）。*/
.pos-img {
  position: absolute; inset: 0; width: 100%; height: 100%;
  object-fit: cover; object-position: top center;
  pointer-events: none; user-select: none; -webkit-user-drag: none;
  opacity: .92;
}
.pos-label {
  font-size: 10px; padding: 2px 5px; letter-spacing: .06em; white-space: nowrap;
  position: relative; z-index: 1;
  background: color-mix(in srgb, var(--wv-surface) 72%, transparent);
  border-radius: 4px;
}
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
/* ---------- P5：AI 更新提示词弹框（语言 + 运镜关键帧）与主体闸门 ---------- */
.ai-label {
  display: block;
  font-size: 11.5px;
  color: var(--wv-text-4);
  margin: 8px 0 4px;
  letter-spacing: 0.04em;
}
.ai-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
.ai-lang-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--wv-line);
}
.ai-kf-block {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px dashed var(--wv-line);
}
.ai-kf-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}
.ai-kf-tag {
  font-size: 11px;
  color: var(--wv-accent, #d0a24e);
}
.ai-kf-comp {
  font-size: 11px;
  line-height: 1.5;
}
/* 主体闸门 */
.cast-row-card {
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: var(--wv-surface-sunken);
}
.cast-row-title {
  margin: 0 0 8px;
  font-size: 12.5px;
}
.cast-row-items {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}
.cast-row-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: var(--wv-text-2);
  cursor: pointer;
}
</style>
