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
import { NAlert, NButton, NIcon, NInputNumber, NModal, NSkeleton, NTag, useMessage } from 'naive-ui'
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
import { listAssets, uploadReference, fetchAssetBlob, deleteAssets } from '@/api/assets'
import { createExport, fetchExportBlob, renderMaster, timecode } from '@/api/export'
import { getProject, updateProjectDuration } from '@/api/projects'
import type { AssetRef, DirectorPlan, DirectorShot, JobRecord } from '@/api/types'
import BriefComposer from '@/components/director/BriefComposer.vue'
import ImagePlanEditor from '@/components/director/ImagePlanEditor.vue'
import RevisionRail from '@/components/director/RevisionRail.vue'
import VideoPlanEditor from '@/components/director/VideoPlanEditor.vue'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, modeLabel } from '@/utils/format'
import {
  SOURCE_LABEL,
  clonePlan,
  isVideoPlan,
  normalizePlan,
  planProblems,
  round2,
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
  ;(draft.value as unknown as { referenceAssets?: unknown }).referenceAssets = buildRefAssets()
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

// ---------- W4 资产库 ----------
const outputAssets = computed(() => (assets.data.value ?? []).filter((a) => ['still','clip','master'].includes(a.kind)))
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
  if (refSelected.value.length >= 4) {
    message.warning('参考图最多 4 张（§7.2）')
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
      if (refSelected.value.length >= 4) message.warning('参考图最多 4 张')
      else refSelected.value.push(a.id)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '上传失败')
    } finally {
      uploadingRef.value = false
      input.value = ''
    }
  })()
}
function toggleRef(id: string, on: boolean): void {
  if (on) {
    if (refSelected.value.length >= 4) {
      message.warning('参考图最多 4 张（§7.2）')
      return
    }
    if (!refSelected.value.includes(id)) refSelected.value.push(id)
  } else {
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]
  }
  syncReferenceAssets()
}

// ---------- W3 任务 ----------
const jobs = useQuery({
  queryKey: computed(() => ['jobs', workspaceId.value, projectId.value]),
  queryFn: () => listJobs(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
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

// 任务默认展示 10 条，点“查看更多”逐次再展示 10 条
const jobLimit = ref(10)
const filterLatest = ref(true)
/** 只显示“每个分镜每个类型最近一条任务”（still/clip 分开、关键帧按帧；不分状态，便于看重跑/重跑失败） */
const latestJobs = computed(() => {
  const all = jobs.data.value ?? []
  if (!filterLatest.value) return all
  const newest = new Map<string, JobRecord>()
  for (const j of all) {
    const key = `shot:${j.payload?.shot_no ?? 'x'}:${j.kind}:${j.payload?.keyframe_index ?? ''}`
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
const visibleJobs = computed(() => latestJobs.value.slice(0, jobLimit.value))
function showMoreJobs(): void {
  jobLimit.value += 10
}

async function startGeneration(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  // P4：先把当前草稿（含参考图/主体标注/提示词改动）落库，再发起生成
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const isVideo = draft.value?.mode === 'video'
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'still',
      count: isVideo ? undefined : imgCount.value,
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
const eligibleJobs = computed(() => (jobs.data.value ?? []).filter(
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
  if (!p || !p.positive_prompt) return undefined
  const r = revOfJob(j)
  const no = p.revision_no ?? r?.no ?? '?'
  const prompt = p.positive_prompt.length > 120 ? `${p.positive_prompt.slice(0, 120)}…` : p.positive_prompt
  const hist = p.keyframeHistorical
    ? `\n关键帧：沿用历史版本第${p.shot_no ?? '?'}镜的关键帧${p.keyframeHistoricalRevisionNo ? `（v${p.keyframeHistoricalRevisionNo}）` : ''}`
    : ''
  return `版本 v${no}${r?.stale ? '（旧版）' : ''} · md5 ${(p.prompt_md5 ?? '-').slice(0, 16)}${hist}\n提示词：${prompt}`
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
    jobSel.value = []
    message.success(`已删除 ${r.deleted} 条记录`)
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
  const f = Number(motionFrames.value)
  if (!Number.isInteger(f) || f < MOTION_MIN || f > MOTION_MAX) {
    message.warning(`运动帧数需在 ${MOTION_MIN}–${MOTION_MAX} 之间`)
    return
  }
  motionOpen.value = false
  void startMotion(f)
}
async function startMotion(frames?: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  genBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'clip',
      ...(frames ? { frames } : {}),
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
    const row = { rec, start: cursor, dur, hasMedia: as.some((a) => a.shotId === rec.id) }
    cursor += dur
    return row
  })
})
const exportTotalSec = computed(() => {
  const last = exportRows.value[exportRows.value.length - 1]
  return last ? last.start + last.dur : 0
})
const exportTotal = computed(() => timecode(exportTotalSec.value))
// 成片时间线同样默认 10 条 + 查看更多
const tlLimit = ref(10)
const visibleExportRows = computed(() => exportRows.value.slice(0, tlLimit.value))
function showMoreTl(): void {
  tlLimit.value += 10
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
              <span class="font-mono eyebrow">参考图</span>
              <label class="upload-link" :class="{ busy: uploadingRef }">
                <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                <span v-if="uploadingRef">上传中…</span>
                <span v-else>+ 上传</span>
              </label>
            </div>
            <div v-if="refLibrary.length" class="refs-grid">
              <div
                v-for="a in refLibrary.slice(0, 8)"
                :key="a.id"
                :class="['ref-thumb', { sel: refSelected.includes(a.id) }]"
                :title="refSelected.includes(a.id) ? '点击取消' : '点击用作参考'"
                @click="toggleRef(a.id, !refSelected.includes(a.id))"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <i v-if="refSelected.includes(a.id)" class="ref-badge font-mono">REF</i>
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
              上传参考图（png/jpg/webp ≤4 张）或从下方资产库点「参考」；给选中的图标主体名（如「唐僧」）后，系统只会在文案提到该主体的镜头里使用它，并把「形象以参考图为准」写入提示词。
            </p>
            <p v-if="refSelected.length" class="ref-count font-mono">{{ refSelected.length }}/4 已选</p>
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
                  @approve-shot="handleApproveShot"
                  @ai-prompt="openAiRewrite"
                  @ai-sync-all="aiSyncAll"
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

      <!-- 任务区（W3）：确认后发起生成，展示状态/进度 -->
      <div v-if="detApproved || (jobs.data.value ?? []).length" class="jobs-panel" data-testid="jobs-panel">
        <div class="jobs-head">
          <span class="font-mono eyebrow">任务 / 生成</span>
          <label class="filter-latest">
            <input type="checkbox" v-model="filterLatest" />
            只看最近一轮
          </label>
          <div class="jobs-actions">
            <template v-if="!activeJobCount">
              <span v-if="!isVideoNow" class="count-inline">
                张数
                <select v-model="imgCount" class="mini-select">
                  <option :value="1">1</option>
                  <option :value="2">2</option>
                  <option :value="4">4</option>
                </select>
              </span>
              <NButton v-if="isVideoNow" size="small" secondary :loading="genBusy" data-testid="btn-motion-jobs"
                       :disabled="!motionReady" title="先出关键帧(still)后可用（两段式 §11.3）" @click="openMotionModal">
                运动(motion)
              </NButton>
              <NButton size="small" type="primary" :loading="genBusy" data-testid="btn-gen-jobs" @click="startGeneration">
                {{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}
              </NButton>
            </template>
            <span v-else class="state-hint font-mono">{{ freshActiveCount || activeJobCount }} 个进行中，实时刷新…</span>
          </div>
        </div>

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
            <span class="job-kind font-mono">[{{ j.kind === 'still' ? '关键帧' : '运动' }} · 第{{ j.payload?.shot_no ?? '—' }}镜<template v-if="j.payload?.frame_label"> · {{ j.payload.frame_label }}</template>]</span>
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
          <span class="state-hint font-mono">{{ outputAssets.length }} 个产物</span>
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
        <div class="gallery-grid">
          <div v-for="a in outputAssets" :key="a.id" :class="['g-item', { manage: galManage, sel: galSel.includes(a.id) }]">
            <label v-if="galManage" class="g-sel">
              <input type="checkbox" :checked="galSel.includes(a.id)" @change="toggleGalSel(a.id)" />
            </label>
            <button v-if="galManage" type="button" class="g-del" :disabled="galBusy"
                    :title="'删除此' + a.kind" @click="removeAssetOne(a.id)">
              ×
            </button>
            <video
              v-if="(a.kind === 'clip' || a.kind === 'master') && (a.mime ?? '').startsWith('video/') && galUrls[a.id]"
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

.studio-rail {
  position: sticky;
  bottom: 12px;
  z-index: 5;
}

/* 移动端：版本条不再吸底，避免锚定盖住正文/操作区；按钮由 RevisionRail 内部换行收起 */
@media (max-width: 760px) {
  .studio-rail {
    position: static;
    margin-top: 18px;
  }
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
  justify-content: space-between;
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
.jobs-actions { display: flex; align-items: center; gap: 8px; }
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

</style>
