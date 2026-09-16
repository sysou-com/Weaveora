<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  BookOpen,
  Clapperboard,
  FileClock,
  Info,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Sparkles,
  Trash2,
  Wand2,
} from 'lucide-vue-next'
import {
  NAlert,
  NButton,
  NCollapse,
  NCollapseItem,
  NDrawer,
  NDrawerContent,
  NIcon,
  NInput,
  NModal,
  NSkeleton,
  useMessage,
} from 'naive-ui'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  aiCondensed,
  aiGuide,
  aiNextEpisode,
  applyScriptSync,
  convertEpisodeToProject,
  createScriptEpisode,
  deleteScriptEpisode,
  getScript,
  listScriptChanges,
  listScriptEpisodes,
  patchScript,
  updateScriptEpisode,
} from '@/api/scripts'
import type { AiGuideResult, ScriptConflict, ScriptFieldKey } from '@/api/types'
import ConvertToProjectDialog, {
  type ConvertPayload,
} from '@/components/script/ConvertToProjectDialog.vue'
import NextEpisodeDialog from '@/components/script/NextEpisodeDialog.vue'
import PagedTextarea from '@/components/script/PagedTextarea.vue'
import ScriptChangeList from '@/components/script/ScriptChangeList.vue'
import ScriptFieldCard from '@/components/script/ScriptFieldCard.vue'
import SyncConfirmDialog from '@/components/script/SyncConfirmDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateShort } from '@/utils/format'
import { SCRIPT_FIELDS, formatChars } from '@/utils/script'

/**
 * 剧情详情：折叠框列表展示各集；顶部「开始下一集」；
 * 每集可编辑 / 转成项目；AI 维护「精简的故事」并给出一致性建议（Q4：确认后才改历史章节）。
 */
const route = useRoute()
const router = useRouter()
const message = useMessage()
const queryClient = useQueryClient()
const auth = useAuthStore()

const scriptId = computed(() => String(route.params.scriptId ?? ''))
const workspaceId = computed(() => auth.activeWorkspaceId ?? '')

// ---------------------------------------------------------------- 数据
const { data: script, isPending: scriptPending } = useQuery({
  queryKey: computed(() => ['script', scriptId.value]),
  queryFn: () => getScript(workspaceId.value, scriptId.value),
  enabled: computed(() => scriptId.value !== '' && workspaceId.value !== ''),
})
const { data: episodes, isPending: epsPending } = useQuery({
  queryKey: computed(() => ['script-episodes', scriptId.value]),
  queryFn: () => listScriptEpisodes(workspaceId.value, scriptId.value),
  enabled: computed(() => scriptId.value !== '' && workspaceId.value !== ''),
})
const changesQuery = useQuery({
  queryKey: computed(() => ['script-changes', scriptId.value]),
  queryFn: () => listScriptChanges(workspaceId.value, scriptId.value, 50),
  enabled: computed(() => scriptId.value !== '' && workspaceId.value !== ''),
})

const nextNo = computed(() => (episodes.value ?? []).reduce((m, e) => Math.max(m, e.episodeNo), 0) + 1)

async function refresh(): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['script', scriptId.value] }),
    queryClient.invalidateQueries({ queryKey: ['script-episodes', scriptId.value] }),
    queryClient.invalidateQueries({ queryKey: ['script-changes', scriptId.value] }),
  ])
}

// ---------------------------------------------------------------- 要素编辑抽屉
const fieldsDrawer = ref(false)
const draftFields = reactive<Record<ScriptFieldKey, string>>({
  characters: '',
  story: '',
  conflict: '',
  plotStructure: '',
  language: '',
  stageDirections: '',
})
const savingFields = ref(false)

function openFields(): void {
  for (const f of SCRIPT_FIELDS) {
    draftFields[f.key] = (script.value as unknown as Record<string, string> | undefined)?.[f.key] ?? ''
  }
  fieldsDrawer.value = true
}

const draftElements = computed<Record<string, string>>(() => ({ ...draftFields }))

async function saveFields(): Promise<void> {
  savingFields.value = true
  try {
    await patchScript(workspaceId.value, scriptId.value, { ...draftFields })
    await refresh()
    changesQuery.refetch()
    message.success('剧本要素已保存')
    fieldsDrawer.value = false
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingFields.value = false
  }
}

// ---------------------------------------------------------------- 集编辑抽屉
const epDrawer = reactive({
  show: false,
  mode: 'create' as 'create' | 'edit',
  id: '',
  no: 1,
  title: '',
  content: '',
  summary: '',
  aiPolished: false,
})
const savingEp = ref(false)

function openEpisodeDrawer(p: Partial<typeof epDrawer>): void {
  epDrawer.show = true
  epDrawer.id = p.id ?? ''
  epDrawer.mode = p.id ? 'edit' : 'create'
  epDrawer.no = p.no ?? nextNo.value
  epDrawer.title = p.title ?? `第 ${p.no ?? nextNo.value} 集`
  epDrawer.content = p.content ?? ''
  epDrawer.summary = p.summary ?? ''
  epDrawer.aiPolished = p.aiPolished ?? false
}

function editEpisode(e: { id: string; episodeNo: number; title: string; content: string; summary: string; aiPolished: boolean }): void {
  openEpisodeDrawer({
    id: e.id,
    no: e.episodeNo,
    title: e.title,
    content: e.content,
    summary: e.summary,
    aiPolished: e.aiPolished,
  })
}

// 保存后：若 AI 有一致性建议 → 弹确认（Q4）
const syncShow = ref(false)
const syncList = ref<ScriptConflict[]>([])
const syncAnchor = ref('')
const syncBusy = ref(false)

async function saveEpisode(): Promise<void> {
  if (!epDrawer.title.trim()) epDrawer.title = `第 ${epDrawer.no} 集`
  const ok = window.confirm(
    '保存后 AI 将自动更新《精简的故事》，并检查前面章节是否需要同步修改。\n' +
      '（历史章节只有在你确认后才会被改写，且会记入变更记录。）\n\n是否继续？',
  )
  if (!ok) return
  savingEp.value = true
  try {
    const input = {
      title: epDrawer.title,
      content: epDrawer.content,
      summary: epDrawer.summary,
      aiPolished: epDrawer.aiPolished,
      syncPrevious: true,
    }
    const res =
      epDrawer.mode === 'create'
        ? await createScriptEpisode(workspaceId.value, scriptId.value, input)
        : await updateScriptEpisode(workspaceId.value, scriptId.value, epDrawer.id, input)
    await refresh()
    epDrawer.show = false
    const conflicts = res.conflicts ?? []
    if (conflicts.length) {
      syncList.value = conflicts
      syncAnchor.value = res.episode.id
      syncShow.value = true
      message.warning(`AI 检查到 ${conflicts.length} 处历史章节需同步，请确认`)
    } else {
      message.success(`第 ${res.episode.episodeNo} 集已保存，精简的故事已更新`)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingEp.value = false
  }
}

async function applySync(items: ScriptConflict[]): Promise<void> {
  if (!items.length) {
    syncShow.value = false
    return
  }
  syncBusy.value = true
  try {
    const res = await applyScriptSync(workspaceId.value, scriptId.value, syncAnchor.value, items)
    message.success(`已同步改写 ${res.episodes?.length ?? 0} 集`)
    syncShow.value = false
    await refresh()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '改写失败')
  } finally {
    syncBusy.value = false
  }
}

async function removeEpisode(e: { id: string; episodeNo: number }): Promise<void> {
  if (!window.confirm(`删除第 ${e.episodeNo} 集？后面的集号会自动前移，不可恢复。`)) return
  try {
    await deleteScriptEpisode(workspaceId.value, scriptId.value, e.id)
    message.success('已删除')
    await refresh()
  } catch (err) {
    message.error(err instanceof Error ? err.message : '删除失败')
  }
}

// ---------------------------------------------------------------- 开始下一集
const nextShow = ref(false)
const nextBusy = ref(false)

async function chooseNext(payload: { polished: boolean; titleHint: string; instruction: string }): Promise<void> {
  nextBusy.value = true
  try {
    const r = await aiNextEpisode(workspaceId.value, scriptId.value, {
      polished: payload.polished,
      titleHint: payload.titleHint,
      instruction: payload.instruction,
    })
    nextShow.value = false
    openEpisodeDrawer({
      no: r.episodeNo,
      title: r.title || `第 ${r.episodeNo} 集`,
      content: r.content,
      summary: r.summary,
      aiPolished: payload.polished,
    })
    if (payload.polished) message.success('AI 已按「精简的故事」草拟本集，请检查修改后保存')
    if (r.source === 'stub') message.info('当前未接 LLM（离线示例）；配置 WEAVEORA_LLM_* 后为正式内容')
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'AI 生成失败')
  } finally {
    nextBusy.value = false
  }
}

// ---------------------------------------------------------------- 精简故事 / AI 引导 / 变更记录
const condensing = ref(false)
async function refreshCondensed(): Promise<void> {
  condensing.value = true
  try {
    const r = await aiCondensed(workspaceId.value, scriptId.value)
    await refresh()
    if ((r.conflicts ?? []).length) {
      message.warning('AI 检查到历史章节需要同步，请在保存下一集时确认或手动修改')
    } else {
      message.success('已刷新《精简的故事》')
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '刷新失败')
  } finally {
    condensing.value = false
  }
}

const guideShow = ref(false)
const guideBusy = ref(false)
const guide = ref<AiGuideResult | null>(null)
async function runGuide(): Promise<void> {
  guideShow.value = true
  guideBusy.value = true
  try {
    guide.value = await aiGuide(workspaceId.value, scriptId.value)
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'AI 引导不可用')
    guideShow.value = false
  } finally {
    guideBusy.value = false
  }
}

const changesShow = ref(false)

// ---------------------------------------------------------------- 转成项目
const convertShow = ref(false)
const convertBusy = ref(false)
const convertTarget = ref<{ id: string; episodeNo: number; title: string } | null>(null)

function openConvert(e: { id: string; episodeNo: number; title: string }): void {
  convertTarget.value = e
  convertShow.value = true
}

async function doConvert(payload: ConvertPayload): Promise<void> {
  const target = convertTarget.value
  if (!target) return
  convertBusy.value = true
  try {
    const r = await convertEpisodeToProject(workspaceId.value, scriptId.value, target.id, payload)
    void queryClient.invalidateQueries({ queryKey: ['own-projects'] })
    convertShow.value = false
    if (r.note) {
      message.warning(r.note)
    } else {
      message.success(`已创建项目并生成 ${r.shotCount} 个分镜（动作 + 提示词）`)
    }
    void router.push({ name: 'project-detail', params: { projectId: r.projectId } })
  } catch (e) {
    message.error(e instanceof Error ? e.message : '转成项目失败')
  } finally {
    convertBusy.value = false
  }
}

const convertLabel = computed(() => {
  const t = convertTarget.value
  return t ? `第 ${t.episodeNo} 集 · ${t.title}` : ''
})

function fieldValue(key: ScriptFieldKey): string {
  return (script.value as unknown as Record<string, string> | undefined)?.[key] ?? ''
}
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="router.push({ name: 'scripts' })">
      <NIcon size="15"><ArrowLeft /></NIcon>
      <span>返回我的剧本</span>
    </button>

    <div v-if="scriptPending" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>

    <template v-else-if="script">
      <header class="head">
        <div class="head-copy">
          <p class="eyebrow font-mono">{{ script.genre }} · {{ script.status }}</p>
          <h1 class="title">{{ script.title }}</h1>
          <p class="meta font-mono text-secondary">
            {{ script.episodeCount }} 集 · 约 {{ formatChars(script.charCount) }} · 更新
            {{ formatDateShort(script.updatedAt) }}
          </p>
        </div>
        <div class="head-actions">
          <NButton size="small" @click="openFields">
            <template #icon><NIcon><Pencil :size="14" /></NIcon></template>
            编辑要素
          </NButton>
          <NButton size="small" @click="runGuide">
            <template #icon><NIcon><Info :size="14" /></NIcon></template>
            AI 引导
          </NButton>
          <NButton size="small" @click="changesShow = true">
            <template #icon><NIcon><FileClock :size="14" /></NIcon></template>
            变更记录
          </NButton>
          <NButton type="primary" data-testid="btn-next-episode" @click="nextShow = true">
            <template #icon><NIcon><Plus :size="15" /></NIcon></template>
            开始下一集
          </NButton>
        </div>
      </header>

      <!-- 剧本设定（展开可看全部要素，可读可编辑） -->
      <section class="block">
        <NCollapse>
          <NCollapseItem name="setting" :title="`剧本设定 · ${script.genre}`">
            <div class="fields-view">
              <div v-for="f in SCRIPT_FIELDS" :key="f.key" class="fv">
                <h4 class="fv-title">{{ f.label }}</h4>
                <p class="fv-body">{{ fieldValue(f.key) || '（未填写）' }}</p>
              </div>
            </div>
            <NButton size="small" class="fv-edit" @click="openFields">
              <template #icon><NIcon><Pencil :size="13" /></NIcon></template>
              编辑要素
            </NButton>
          </NCollapseItem>
        </NCollapse>
      </section>

      <!-- 精简的故事：AI 的连续记忆 -->
      <section class="block memory">
        <div class="memory-head">
          <h2 class="block-title">精简的故事 <span class="tag font-mono">AI 连续记忆</span></h2>
          <NButton size="small" quaternary :loading="condensing" @click="refreshCondensed">
            <template #icon><NIcon><RefreshCw :size="13" /></NIcon></template>
            AI 刷新
          </NButton>
        </div>
        <p class="memory-body text-secondary">
          {{ script.condensedStory || '（还没有——写完第一集后，AI 会自动维护这段「精简的故事」，作为后续每一集的唯一依据。）' }}
        </p>
      </section>

      <!-- 分集折叠列表 -->
      <section class="block">
        <div class="eps-head">
          <h2 class="block-title">分集</h2>
          <span class="zone-hint font-mono">{{ episodes?.length ?? 0 }} 集</span>
          <NButton size="small" class="eps-add" @click="nextShow = true">
            <template #icon><NIcon><Sparkles :size="13" /></NIcon></template>
            开始下一集
          </NButton>
        </div>

        <div v-if="epsPending" class="skel"><NSkeleton text /></div>
        <NCollapse v-else-if="(episodes ?? []).length" :default-expanded-names="[(episodes ?? [])[0]?.id ?? '']">
          <NCollapseItem
            v-for="e in episodes ?? []"
            :key="e.id"
            :name="e.id"
          >
            <template #header>
              <div class="ep-head">
                <span class="ep-no font-mono">第 {{ e.episodeNo }} 集</span>
                <span class="ep-title">{{ e.title }}</span>
                <span v-if="e.aiPolished" class="ep-tag font-mono">AI 润色</span>
                <span class="ep-chars font-mono">{{ formatChars(e.content.length) }}</span>
              </div>
            </template>
            <div class="ep-body">
              <p v-if="e.summary" class="ep-summary text-secondary">摘要：{{ e.summary }}</p>
              <p class="ep-content">{{ e.content || '（本集暂无正文）' }}</p>
              <div class="ep-ops">
                <NButton size="tiny" @click="editEpisode(e)">
                  <template #icon><NIcon><Pencil :size="12" /></NIcon></template>
                  编辑
                </NButton>
                <NButton size="tiny" @click="openConvert(e)">
                  <template #icon><NIcon><Clapperboard :size="12" /></NIcon></template>
                  转成项目
                </NButton>
                <NButton size="tiny" quaternary type="error" @click="removeEpisode(e)">
                  <template #icon><NIcon><Trash2 :size="12" /></NIcon></template>
                  删除
                </NButton>
              </div>
            </div>
          </NCollapseItem>
        </NCollapse>
        <div v-else class="empty-state">
          <p class="text-secondary">还没有任何一集。点「开始下一集」，让 AI 读着「精简的故事」起个头，或自己写。</p>
          <NButton type="primary" @click="nextShow = true">
            <template #icon><NIcon><Plus :size="15" /></NIcon></template>
            开始下一集
          </NButton>
        </div>
      </section>
    </template>

    <div v-else class="empty-state"><p class="text-secondary">剧本不存在或不在当前工作区。</p></div>

    <!-- ============ 要素编辑抽屉 ============ -->
    <NDrawer v-model:show="fieldsDrawer" :width="760" placement="right">
      <NDrawerContent title="编辑剧本要素" closable>
        <p class="drawer-lead text-secondary">
          每项都可以「AI 生成」（凭标题与类型）或「AI 更新」（读全部要素与已写集数后重写）——改完先弹对比，确认后才写入。
        </p>
        <div class="fields-edit">
          <ScriptFieldCard
            v-for="f in SCRIPT_FIELDS"
            :key="f.key"
            v-model="draftFields[f.key]"
            :field-key="f.key"
            :label="f.label"
            :help="f.help"
            :placeholder="f.placeholder"
            :title="script?.title ?? ''"
            :genre="script?.genre ?? ''"
            :script-id="scriptId"
            :workspace-id="workspaceId"
            :elements="draftElements"
            :page-rows="25"
          />
        </div>
        <template #footer>
          <NButton type="primary" :loading="savingFields" @click="saveFields">
            <template #icon><NIcon><Save :size="14" /></NIcon></template>
            保存要素
          </NButton>
        </template>
      </NDrawerContent>
    </NDrawer>

    <!-- ============ 集编辑抽屉 ============ -->
    <NDrawer v-model:show="epDrawer.show" :width="760" placement="right">
      <NDrawerContent :title="`${epDrawer.mode === 'create' ? '新增' : '编辑'}第 ${epDrawer.no} 集`" closable>
        <div class="ep-edit">
          <label class="lbl">本集标题</label>
          <NInput v-model:value="epDrawer.title" :maxlength="200" placeholder="例如：雨夜重逢" />

          <label class="lbl">本集摘要（可选，供 AI 记忆与后续生成）</label>
          <NInput
            v-model:value="epDrawer.summary"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 3 }"
            :maxlength="2000"
            placeholder="一句话概括本集发生了什么"
          />

          <label class="lbl">本集正文（每页 25 行，可左右滑动换页）</label>
          <PagedTextarea
            v-model="epDrawer.content"
            :page-rows="25"
            :maxlength="40000"
            placeholder="对话、动作、舞台说明……建议 ≥4000 字"
            test-id="episode-content"
          />

          <NAlert type="info" :bordered="false" class="hint">
            保存时 AI 会刷新《精简的故事》（后续每一集的唯一依据），并检查前面章节是否需要同步修改；
            <b>历史章节只有在你确认后才会被改写</b>。
          </NAlert>
        </div>
        <template #footer>
          <NButton quaternary @click="epDrawer.show = false">取消</NButton>
          <NButton type="primary" :loading="savingEp" @click="saveEpisode">
            <template #icon><NIcon><Save :size="14" /></NIcon></template>
            保存本集
          </NButton>
        </template>
      </NDrawerContent>
    </NDrawer>

    <!-- ============ 对话/弹层 ============ -->
    <NextEpisodeDialog
      v-model:show="nextShow"
      :next-no="nextNo"
      :busy="nextBusy"
      @choose="chooseNext"
    />

    <SyncConfirmDialog v-model:show="syncShow" :conflicts="syncList" :busy="syncBusy" @apply="applySync" />

    <ConvertToProjectDialog
      v-model:show="convertShow"
      :episode-label="convertLabel"
      :busy="convertBusy"
      @submit="doConvert"
    />

    <ScriptChangeList
      v-model:show="changesShow"
      :changes="changesQuery.data.value ?? []"
      :loading="changesQuery.isPending.value"
    />

    <NModal
      :show="guideShow"
      preset="card"
      title="AI 引导 · 剧本推进建议"
      style="max-width: 620px"
      @update:show="(v: boolean) => (guideShow = v)"
    >
      <div v-if="guideBusy" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      <div v-else-if="guide" class="guide">
        <p class="guide-stage">
          <span class="font-mono text-secondary">当前阶段</span>
          <b>{{ guide.stage || '—' }}</b>
        </p>
        <p v-if="guide.missingBeats?.length" class="guide-line">
          <span class="font-mono text-secondary">还缺</span>
          <span>{{ guide.missingBeats.join(' · ') }}</span>
        </p>
        <p v-if="guide.estimatedRemainingEpisodes" class="guide-line">
          <span class="font-mono text-secondary">预计还需</span>
          <span>约 {{ guide.estimatedRemainingEpisodes }} 集</span>
        </p>
        <ul class="guide-list">
          <li v-for="(s, i) in guide.suggestions" :key="i">
            <NIcon size="13"><Wand2 /></NIcon>
            <span>{{ s }}</span>
          </li>
        </ul>
        <p class="guide-note text-secondary">
          <NIcon size="13"><BookOpen /></NIcon>
          引导基于 AI 持续维护的《精简的故事》与「情节结构」。
        </p>
      </div>
      <template #footer>
        <div class="guide-foot">
          <NButton quaternary @click="guideShow = false">关闭</NButton>
          <NButton type="primary" @click="guideShow = false; nextShow = true">
            <template #icon><NIcon><Plus :size="14" /></NIcon></template>
            开始下一集
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 20px; }
.back {
  display: inline-flex; align-items: center; gap: 6px; align-self: flex-start;
  padding: 6px 10px; margin-left: -10px; background: none; border: none;
  border-radius: var(--wv-radius-s); color: var(--wv-text-3); font-size: 13.5px; cursor: pointer;
}
.back:hover { color: var(--wv-text); background: var(--wv-surface); }
.head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: 0.28em; color: var(--wv-text-4); }
.title { margin: 0; font-size: 28px; line-height: 1.25; }
.meta { margin: 8px 0 0; font-size: 12px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.block { border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); background: var(--wv-surface); padding: 16px 18px; }
.block-title { margin: 0; font-size: 17px; font-family: var(--wv-font-display); }
.tag { margin-left: 8px; font-size: 10px; letter-spacing: 0.12em; color: var(--wv-accent-text); background: var(--wv-accent-soft); padding: 3px 7px; border-radius: 5px; }

.fields-view { display: flex; flex-direction: column; gap: 16px; }
.fv-title { margin: 0 0 6px; font-size: 14px; color: var(--wv-accent-text); }
.fv-body { margin: 0; font-size: 13.5px; line-height: 1.85; white-space: pre-wrap; word-break: break-word; }
.fv-edit { margin-top: 14px; }

.memory { border-left: 2px solid var(--wv-accent); }
.memory-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.memory-body { margin: 10px 0 0; font-size: 13px; line-height: 1.85; white-space: pre-wrap; word-break: break-word; }

.eps-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.zone-hint { font-size: 11px; color: var(--wv-text-4); }
.eps-add { margin-left: auto; }
.ep-head { display: flex; align-items: center; gap: 10px; }
.ep-no { font-size: 11px; color: var(--wv-text-4); }
.ep-title { font-size: 14.5px; }
.ep-tag { font-size: 10px; color: var(--wv-accent-text); background: var(--wv-accent-soft); padding: 2px 6px; border-radius: 5px; }
.ep-chars { margin-left: auto; font-size: 11px; color: var(--wv-text-4); }
.ep-body { display: flex; flex-direction: column; gap: 10px; }
.ep-summary { margin: 0; font-size: 12.5px; }
.ep-content { margin: 0; font-size: 14px; line-height: 1.95; white-space: pre-wrap; word-break: break-word; }
.ep-ops { display: flex; gap: 8px; }

.empty-state {
  display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 34px 20px;
  border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m);
}
.skel { padding: 20px; background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); }

.drawer-lead { margin: 0 0 14px; font-size: 12.5px; line-height: 1.8; }
.fields-edit { display: flex; flex-direction: column; gap: 16px; }
.ep-edit { display: flex; flex-direction: column; gap: 6px; }
.lbl { margin-top: 8px; font-size: 12px; color: var(--wv-text-3); }
.hint { margin-top: 14px; }

.guide { display: flex; flex-direction: column; gap: 10px; }
.guide-stage { display: flex; gap: 10px; align-items: baseline; margin: 0; font-size: 14px; }
.guide-line { display: flex; gap: 10px; align-items: baseline; margin: 0; font-size: 13px; }
.guide-list { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.guide-list li { display: flex; gap: 8px; font-size: 13px; line-height: 1.7; color: var(--wv-text-2); }
.guide-list svg { margin-top: 4px; color: var(--wv-accent); flex: none; }
.guide-note { display: flex; gap: 6px; margin: 8px 0 0; font-size: 12px; }
.guide-foot { display: flex; justify-content: flex-end; gap: 10px; }
</style>
