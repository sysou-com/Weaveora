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

/** 首次：写 Brief 并立即导演 */
async function handleFirstBrief(payload: { rawText: string; dirMode: 'image' | 'video' }): Promise<void> {
  creating.value = true
  try {
    const b = await createBrief(workspaceId.value, projectId.value, {
      rawText: payload.rawText,
      mode: payload.dirMode,
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
</style>
