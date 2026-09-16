<script setup lang="ts">
import { NButton, NIcon, useMessage } from 'naive-ui'
import { Sparkles, RefreshCw, Info } from 'lucide-vue-next'
import { ref } from 'vue'

import PagedTextarea from './PagedTextarea.vue'
import ScriptAiDiffDialog, { type AiDiffItem } from './ScriptAiDiffDialog.vue'
import { aiScriptField, aiScriptFieldPreview } from '@/api/scripts'
import type { ScriptFieldKey } from '@/api/types'
import { SCRIPT_FIELD_MAX } from '@/utils/script'

/**
 * 单个剧本要素字段卡：字段说明 + **分页**输入 + AI 生成 / AI 更新（Q3：先弹 diff 再写入）。
 *
 * 分页：新建页 15 行/页（默认），详情抽屉传 25 行/页；手机可左右滑动换页。
 */
const props = defineProps<{
  fieldKey: ScriptFieldKey
  label: string
  help: string
  placeholder?: string
  modelValue: string
  title: string
  genre: string
  scriptId?: string | null
  workspaceId?: string | null
  /** 页面上已填的全部要素（AI 更新时的上下文） */
  elements?: Record<string, string>
  disabled?: boolean
  /** 每页行数：新建页 15，详情抽屉 25 */
  pageRows?: number
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const message = useMessage()
const busy = ref(false)
const diffShow = ref(false)
const diffItems = ref<AiDiffItem[]>([])

function applyValue(value: string): void {
  emit('update:modelValue', value)
}

async function ask(mode: 'from_title' | 'from_content'): Promise<void> {
  if (props.disabled) return
  if (!props.title.trim()) {
    message.warning('先填写「剧本标题」，AI 依据标题与类型生成内容')
    return
  }
  if (!props.genre.trim()) {
    message.warning('先选择「剧本类型」')
    return
  }
  busy.value = true
  try {
    const res = props.scriptId
      ? await aiScriptField(props.workspaceId ?? '', props.scriptId, {
          field: props.fieldKey,
          mode,
          currentValue: props.modelValue,
        })
      : await aiScriptFieldPreview({
          title: props.title,
          genre: props.genre,
          field: props.fieldKey,
          mode,
          currentValue: props.modelValue,
          elements: props.elements,
        })
    if (!res.value) {
      message.warning('AI 没返回内容，请重试')
      return
    }
    diffItems.value = [
      {
        key: props.fieldKey,
        label: props.label,
        before: props.modelValue,
        after: res.value,
        note: res.note,
      },
    ]
    diffShow.value = true
    if (res.source === 'stub') {
      message.info('当前未接 LLM（离线示例）；配置 WEAVEORA_LLM_* 后为正式内容')
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'AI 生成失败')
  } finally {
    busy.value = false
  }
}

function onApply(): void {
  const item = diffItems.value[0]
  if (item) applyValue(item.after)
  diffShow.value = false
  message.success(`已更新「${props.label}」`)
}
</script>

<template>
  <section class="field">
    <header class="field-head">
      <div class="field-title">
        <h3>{{ label }}</h3>
        <span class="count font-mono">{{ modelValue.length }} / {{ SCRIPT_FIELD_MAX }}</span>
      </div>
      <div class="field-actions">
        <NButton
          size="small"
          :loading="busy"
          :disabled="disabled"
          :data-testid="`ai-gen-${fieldKey}`"
          @click="ask('from_title')"
        >
          <template #icon><NIcon><Sparkles :size="14" /></NIcon></template>
          AI 生成
        </NButton>
        <NButton
          size="small"
          quaternary
          :loading="busy"
          :disabled="disabled"
          :data-testid="`ai-update-${fieldKey}`"
          @click="ask('from_content')"
        >
          <template #icon><NIcon><RefreshCw :size="14" /></NIcon></template>
          AI 更新
        </NButton>
      </div>
    </header>

    <p class="help text-secondary">
      <NIcon size="13" class="help-ic"><Info /></NIcon>
      {{ help }}
    </p>

    <PagedTextarea
      :model-value="modelValue"
      :page-rows="pageRows ?? 15"
      :maxlength="SCRIPT_FIELD_MAX"
      :placeholder="placeholder"
      :disabled="disabled"
      :test-id="`field-${fieldKey}`"
      @update:model-value="applyValue"
    />

    <p class="ai-hint font-mono text-secondary">
      「AI 生成」仅凭标题与类型 ·「AI 更新」读取全部要素与已写集数后重写
    </p>

    <ScriptAiDiffDialog v-model:show="diffShow" :items="diffItems" @apply="onApply" />
  </section>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px 18px;
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  background: var(--wv-surface);
}
.field-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.field-title { display: flex; align-items: baseline; gap: 10px; }
.field-title h3 { margin: 0; font-size: 16px; }
.count { font-size: 11px; color: var(--wv-text-4); }
.field-actions { display: flex; gap: 8px; }
.help {
  display: flex;
  gap: 6px;
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
}
.help-ic { margin-top: 4px; flex: none; color: var(--wv-text-4); }
.ai-hint { margin: 0; font-size: 11px; letter-spacing: 0.02em; }
</style>
