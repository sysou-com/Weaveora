<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ArrowLeft, Sparkles, Wand2 } from 'lucide-vue-next'
import { NButton, NForm, NFormItem, NIcon, NInput, NSelect, useMessage } from 'naive-ui'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { aiScriptFieldPreview, createScript } from '@/api/scripts'
import type { ScriptFieldKey } from '@/api/types'
import ScriptAiDiffDialog, { type AiDiffItem } from '@/components/script/ScriptAiDiffDialog.vue'
import ScriptFieldCard from '@/components/script/ScriptFieldCard.vue'
import { useAuthStore } from '@/stores/auth'
import { SCRIPT_FIELDS, SCRIPT_GENRES } from '@/utils/script'

/** 新建剧本（「我的项目 → 新建项目」的同构页面）：标题 + 类型 + 6 个可 AI 生成的要素。 */
const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const queryClient = useQueryClient()

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')

const title = ref('')
const genre = ref('')
const fields = reactive<Record<ScriptFieldKey, string>>({
  characters: '',
  story: '',
  conflict: '',
  plotStructure: '',
  language: '',
  stageDirections: '',
})

/** Q8：建议 10 项 + 允许自定义（tag） */
const genreOptions = SCRIPT_GENRES.map((g) => ({ label: g, value: g }))

const elements = computed<Record<string, string>>(() => ({ ...fields }))

// ---------- 一键生成全部（批量 diff 确认，Q3） ----------
const batchShow = ref(false)
const batchBusy = ref(false)
const batchProgress = ref('')
const batchItems = ref<AiDiffItem[]>([])

/**
 * 一键生成全部：**并发 3 路**。
 * 每个字段要分段续写到 ≥4000 字（实测 1–2 分钟），6 个字段串行就是十几分钟 → 不可接受。
 */
async function generateAll(): Promise<void> {
  if (!title.value.trim() || !genre.value.trim()) {
    message.warning('请先填写「剧本标题」并选择「剧本类型」')
    return
  }
  batchBusy.value = true
  batchProgress.value = '准备中…'
  const queue = [...SCRIPT_FIELDS]
  const items: AiDiffItem[] = []
  let done = 0
  const worker = async (): Promise<void> => {
    for (;;) {
      const f = queue.shift()
      if (!f) return
      try {
        const r = await aiScriptFieldPreview({
          title: title.value,
          genre: genre.value,
          field: f.key,
          mode: 'from_title',
          currentValue: fields[f.key],
          elements: elements.value,
        })
        if (r.value) {
          items.push({ key: f.key, label: f.label, before: fields[f.key], after: r.value, note: r.note })
        }
      } catch (e) {
        message.error(`${f.label}：${e instanceof Error ? e.message : '生成失败'}`)
      }
      done++
      batchProgress.value = `已生成 ${done} / ${SCRIPT_FIELDS.length}`
    }
  }
  await Promise.all([worker(), worker(), worker()])
  batchBusy.value = false
  batchProgress.value = ''
  if (!items.length) {
    message.warning('AI 没有返回内容，请逐个字段重试')
    return
  }
  // 保持字段原本顺序（并发完成顺序是乱的）
  const order = new Map(SCRIPT_FIELDS.map((f, i) => [f.key as string, i]))
  items.sort((a, b) => (order.get(a.key) ?? 0) - (order.get(b.key) ?? 0))
  batchItems.value = items
  batchShow.value = true
}

function applyBatch(keys: string[]): void {
  for (const k of keys) {
    const item = batchItems.value.find((i) => i.key === k)
    if (item) fields[k as ScriptFieldKey] = item.after
  }
  batchShow.value = false
  message.success(`已应用 ${keys.length} 个字段`)
}

// ---------- 保存 ----------
const mutation = useMutation({
  mutationFn: () =>
    createScript(workspaceId.value, {
      title: title.value.trim(),
      genre: genre.value.trim(),
      ...fields,
    }),
  onSuccess: (script) => {
    void queryClient.invalidateQueries({ queryKey: ['own-scripts'] })
    message.success(`已创建《${script.title}》，进入剧情详情`)
    void router.push({ name: 'script-detail', params: { scriptId: script.id } })
  },
  onError: (e) => {
    message.error(e instanceof Error ? e.message : '创建失败，请稍后再试')
  },
})

function submit(): void {
  if (!title.value.trim()) {
    message.error('请填写剧本标题')
    return
  }
  if (!genre.value.trim()) {
    message.error('请选择剧本类型')
    return
  }
  mutation.mutate()
}
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="router.push({ name: 'scripts' })">
      <NIcon size="15"><ArrowLeft /></NIcon>
      <span>返回我的剧本</span>
    </button>

    <div class="new-layout">
      <section class="panel">
        <header class="panel-head">
          <p class="eyebrow font-mono">NEW SCRIPT</p>
          <h1 class="panel-title">新建剧本</h1>
          <p class="panel-desc text-secondary">
            先定标题与类型，其余六个要素可交给 AI 起草，也可以自己写。保存后进入「剧情详情」逐集展开。
          </p>
        </header>

        <NForm class="form" :show-feedback="false">
          <NFormItem label="剧本标题">
            <NInput
              v-model:value="title"
              size="large"
              placeholder="例如：雨夜纸船 / 长安十二时 / 那宝玉恍恍惚惚"
              :maxlength="100"
              show-count
              data-testid="new-script-title"
            />
          </NFormItem>

          <NFormItem label="剧本类型">
            <NSelect
              v-model:value="genre"
              :options="genreOptions"
              size="large"
              filterable
              tag
              placeholder="选择或输入（电影 / 电视剧 / 短剧 / 舞台剧 / 戏曲…）"
              data-testid="new-script-genre"
            />
          </NFormItem>

          <div class="fields">
            <ScriptFieldCard
              v-for="f in SCRIPT_FIELDS"
              :key="f.key"
              v-model="fields[f.key]"
              :field-key="f.key"
              :label="f.label"
              :help="f.help"
              :placeholder="f.placeholder"
              :title="title"
              :genre="genre"
              :elements="elements"
            />
          </div>

          <div class="actions">
            <NButton size="large" :loading="batchBusy" data-testid="ai-gen-all" @click="generateAll">
              <template #icon><NIcon><Wand2 :size="16" /></NIcon></template>
              {{ batchBusy ? batchProgress || '生成中…' : 'AI 一键生成全部要素' }}
            </NButton>
            <NButton
              type="primary"
              size="large"
              class="submit"
              :loading="mutation.isPending.value"
              :disabled="!workspaceId"
              data-testid="create-script-submit"
              @click="submit"
            >
              <template #icon><NIcon><Sparkles :size="16" /></NIcon></template>
              保存并进入剧情详情
            </NButton>
          </div>
        </NForm>
      </section>

      <aside class="side">
        <div class="side-card">
          <p class="side-title font-mono">创作顺序建议</p>
          <ol class="steps">
            <li>标题 + 类型定基调</li>
            <li>人物 → 故事 → 冲突（人物驱动故事）</li>
            <li>情节结构定节奏，语言定腔调</li>
            <li>舞台说明补画面与调度</li>
          </ol>
        </div>

        <div class="side-card dim">
          <p class="side-title font-mono">保存之后</p>
          <ul class="steps">
            <li>「剧情详情」用折叠列表管理每一集</li>
            <li>「开始下一集」可选择是否 AI 润色</li>
            <li>AI 持续维护「精简的故事」，越写越连贯</li>
            <li>每集可一键「转成项目」出分镜与提示词</li>
          </ul>
          <p class="warn text-secondary">
            AI 写长文（每段约 2200 字，拼到 4000+ 字）需要 1–2 分钟；
            点「AI 生成」后请勿关闭页面。
          </p>
        </div>
      </aside>
    </div>

    <ScriptAiDiffDialog
      v-model:show="batchShow"
      :items="batchItems"
      title="AI 一键生成 · 请确认要应用的字段"
      @apply="applyBatch"
    />
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 22px; max-width: 980px; }
.back {
  display: inline-flex; align-items: center; gap: 6px; align-self: flex-start;
  padding: 6px 10px; margin-left: -10px; background: none; border: none;
  border-radius: var(--wv-radius-s); color: var(--wv-text-3); font-size: 13.5px; cursor: pointer;
  transition: color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.back:hover { color: var(--wv-text); background: var(--wv-surface); }
.new-layout { display: grid; grid-template-columns: 1fr 250px; gap: 26px; align-items: start; }
.panel {
  background: var(--wv-surface); border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m); padding: 26px 28px 30px;
}
.panel-head { margin-bottom: 22px; }
.eyebrow { margin: 0 0 6px; font-size: 11px; letter-spacing: 0.34em; color: var(--wv-text-4); }
.panel-title { font-size: 26px; }
.panel-desc { margin: 8px 0 0; font-size: 13.5px; line-height: 1.7; }
.form { display: flex; flex-direction: column; gap: 18px; }
.fields { display: flex; flex-direction: column; gap: 16px; }
.actions { display: flex; gap: 12px; margin-top: 4px; }
.submit { margin-left: auto; }
.side { display: flex; flex-direction: column; gap: 16px; }
.side-card {
  background: var(--wv-surface); border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m); padding: 18px 18px 16px;
}
.side-card.dim { background: transparent; }
.side-title { margin: 0 0 12px; font-size: 11px; letter-spacing: 0.2em; color: var(--wv-text-4); }
.steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.steps li { font-size: 12.5px; color: var(--wv-text-3); line-height: 1.6; padding-left: 14px; position: relative; }
.steps li::before { content: '·'; position: absolute; left: 4px; color: var(--wv-accent); }
.steps li { counter-increment: s; }
.warn { margin: 12px 0 0; font-size: 12px; line-height: 1.7; border-top: 1px solid var(--wv-divider); padding-top: 10px; }
@media (max-width: 900px) {
  .new-layout { grid-template-columns: 1fr; }
  .side { display: none; }
}
</style>
