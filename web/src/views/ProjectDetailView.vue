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
import { NAlert, NButton, NIcon, NSkeleton, NTag, useMessage } from 'naive-ui'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  approveRevision,
  approveShot,
  generateDirector,
  getRevision,
  listRevisions,
  patchRevision,
} from '@/api/director'
import { createBrief, listBriefs } from '@/api/briefs'
import { createJobs, listJobs, cancelJob, JOB_STATE_LABEL } from '@/api/jobs'
import { listAssets, uploadReference, fetchAssetBlob } from '@/api/assets'
import { getProject } from '@/api/projects'
import type { DirectorPlan } from '@/api/types'
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
    pristineJson.value = JSON.stringify(draft.value)
    dirty.value = false
  },
  { immediate: true },
)

watch(draft, () => {
  dirty.value = draft.value !== null && JSON.stringify(draft.value) !== pristineJson.value
}, { deep: true })

const detApproved = computed(() => detail.data.value?.approved === true)
const canEdit = computed(() => !!draft.value && !detApproved.value)
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
const refSelected = ref<string[]>([])
const uploadingRef = ref(false)
const thumbUrls = ref<Record<string, string>>({})

async function refreshThumbs(): Promise<void> {
  const picks = refAssets.value
  const next: Record<string, string> = {}
  await Promise.all(
    picks.slice(0, 8).map(async (a) => {
      if (!thumbUrls.value[a.id]) {
        const blob = await fetchAssetBlob(workspaceId.value, a.id)
        if (blob) thumbUrls.value[a.id] = URL.createObjectURL(blob)
      }
      next[a.id] = thumbUrls.value[a.id]
    }),
  )
}
watch(() => refAssets.value.map((a) => a.id).join(','), () => { void refreshThumbs() }, { immediate: true })

// ---------- W4 资产库 ----------
const outputAssets = computed(() => (assets.data.value ?? []).filter((a) => a.kind === 'still' || a.kind === 'clip'))
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

function setRefFromAsset(id: string): void {
  if (refSelected.value.includes(id)) return
  if (refSelected.value.length >= 4) {
    message.warning('参考图最多 4 张（§7.2）')
    return
  }
  refSelected.value.push(id)
  message.success('已加入参考图（下次导演/生成生效）')
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
  }
}

// ---------- W3 任务 ----------
const jobs = useQuery({
  queryKey: computed(() => ['jobs', workspaceId.value, projectId.value]),
  queryFn: () => listJobs(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})
const activeJobCount = computed(() => (jobs.data.value ?? []).filter((j) =>
  ['queued', 'running'].includes(j.state)).length)

// 有进行中任务时 3s 轮询（避免查询配置自引用）
let jobsTimer: ReturnType<typeof setInterval> | undefined
watch(
  activeJobCount,
  (n) => {
    if (n > 0 && !jobsTimer) {
      jobsTimer = setInterval(() => {
        void jobs.refetch()
      }, 3000)
    } else if (n === 0 && jobsTimer) {
      clearInterval(jobsTimer)
      jobsTimer = undefined
    }
  },
  { immediate: true },
)
const imgCount = ref(1)
const genBusy = ref(false)
const cancelBusy = ref<string | null>(null)

async function startGeneration(): Promise<void> {
  if (!selectedRevId.value) return
  genBusy.value = true
  try {
    const isVideo = draft.value?.mode === 'video'
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: selectedRevId.value,
      kind: 'still',
      count: isVideo ? undefined : imgCount.value,
    })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    message.success(`已创建 ${created.length} 个任务（关键帧）`)
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

/** 首次：写 Brief 并立即导演 */
async function handleFirstBrief(payload: { rawText: string; dirMode: 'image' | 'video' }): Promise<void> {
  creating.value = true
  try {
    const b = await createBrief(workspaceId.value, projectId.value, {
      rawText: payload.rawText,
      mode: payload.dirMode,
      ...(refSelected.value.length ? { referenceAssetIds: [...refSelected.value] } : {}),
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

async function handleSave(): Promise<void> {
  if (!draft.value || !selectedRevId.value) return
  saving.value = true
  try {
    await patchRevision(workspaceId.value, projectId.value, selectedRevId.value, draft.value)
    // 以服务端回读为准重建草稿（负词合并/尺寸补齐等归一化），并刷新版本摘要（source→user）
    initKey.value = ''
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['revision', workspaceId.value, projectId.value, selectedRevId.value] }),
      queryClient.invalidateQueries({ queryKey: ['revisions'] }),
    ])
    message.success('已保存修改（手改版）')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleApprove(): Promise<void> {
  if (!selectedRevId.value) return
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
        <!-- 左：Brief 常驻 -->
        <aside class="col-brief">
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

          <div class="refs-panel" data-testid="refs-panel">
            <div class="brief-head">
              <span class="font-mono eyebrow">参考图</span>
              <label class="upload-link" :class="{ busy: uploadingRef }">
                <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploadingRef" @change="onPickFile" />
                <span v-if="uploadingRef">上传中…</span>
                <span v-else>+ 上传</span>
              </label>
            </div>
            <div v-if="refAssets.length" class="refs-grid">
              <div
                v-for="a in refAssets.slice(0, 8)"
                :key="a.id"
                :class="['ref-thumb', { sel: refSelected.includes(a.id) }]"
                :title="refSelected.includes(a.id) ? '点击取消' : '点击用作参考'"
                @click="toggleRef(a.id, !refSelected.includes(a.id))"
              >
                <img v-if="thumbUrls[a.id]" :src="thumbUrls[a.id]" alt="参考图" loading="lazy" />
                <span v-else class="ref-empty">…</span>
                <i v-if="refSelected.includes(a.id)" class="ref-badge font-mono">REF</i>
              </div>
            </div>
            <p v-else class="ref-hint text-secondary">
              上传参考图（png/jpg/webp ≤4 张）做一致性锚定；选中的图会随下次 Brief 一并交给导演层。
            </p>
            <p v-if="refSelected.length" class="ref-count font-mono">{{ refSelected.length }}/4 已选</p>
          </div>
        </aside>

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
                <ImagePlanEditor :plan="imgPlanForEdit" :disabled="!canEdit" />
              </template>
              <template v-else-if="isVideoNow">
                <VideoPlanEditor
                  :plan="vidPlanForEdit"
                  :records="detail.data.value?.shots ?? []"
                  :disabled="!canEdit"
                  :busy-shot="shotBusy"
                  @approve-shot="handleApproveShot"
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
              <NButton size="small" type="primary" :loading="genBusy" data-testid="btn-gen-jobs" @click="startGeneration">
                {{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}
              </NButton>
            </template>
            <span v-else class="state-hint font-mono">{{ activeJobCount }} 个进行中，实时刷新…</span>
          </div>
        </div>

        <div v-if="(jobs.data.value ?? []).length" class="job-list">
          <div v-for="j in jobs.data.value ?? []" :key="j.id" class="job-row" :data-testid="'job-' + j.id.slice(0, 8)">
            <span class="job-kind font-mono">[{{ j.kind }}]</span>
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
          </div>
        </div>
        <p v-else-if="detApproved" class="job-empty text-secondary">
          方案已确认 —— 点「{{ isVideoNow ? '生成关键帧(still)' : '开始生成' }}」发起（先出静帧关键帧，确认后再运动）。
        </p>
      </div>

      <!-- 资产库（W4） -->
      <div v-if="outputAssets.length" class="gallery-panel" data-testid="gallery-panel">
        <div class="jobs-head">
          <span class="font-mono eyebrow">资产库</span>
          <span class="state-hint font-mono">{{ outputAssets.length }} 个产物</span>
        </div>
        <div class="gallery-grid">
          <div v-for="a in outputAssets" :key="a.id" class="g-item">
            <img v-if="galUrls[a.id]" :src="galUrls[a.id]" :alt="a.kind" loading="lazy" />
            <div v-else class="g-loading">…</div>
            <div class="g-meta">
              <span class="g-kind font-mono">{{ a.kind }}<template v-if="a.width"> · {{ a.width }}×{{ a.height }}</template></span>
              <span class="g-actions">
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
}
.studio-head {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
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
  align-items: flex-end;
  justify-content: space-between;
}
.eyebrow-row {
  display: flex;
  align-items: center;
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

/* 三块导演台 */
.studio-grid {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
.col-brief {
  position: sticky;
  top: 76px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.brief-panel {
  padding: 16px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
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
  max-height: 46vh;
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
.link-btn:hover,
.ghost-line:hover {
  color: var(--wv-accent-text);
  background: var(--wv-surface-raised);
}
.brief-spinner {
  display: flex;
  justify-content: center;
}

.col-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
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

/* ---------- W2C 参考图 ---------- */
.refs-panel {
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
  grid-template-columns: repeat(4, 1fr);
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
.ref-empty { color: var(--wv-text-4); display:flex; align-items:center; justify-content:center; height:100%; font-size: 12px; }
.ref-hint { margin: 0; font-size: 11.5px; line-height: 1.7; }
.ref-count { margin: 0; font-size: 10px; color: var(--wv-accent-text); letter-spacing: .12em; }

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
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}
.g-item {
  border: 1px solid var(--wv-line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--wv-surface-sunken);
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

</style>
