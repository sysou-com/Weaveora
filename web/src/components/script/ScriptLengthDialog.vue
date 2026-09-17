<script setup lang="ts">
import { NButton, NCheckbox, NModal } from 'naive-ui'
import { ref, watch } from 'vue'

import ScriptLengthField from './ScriptLengthField.vue'

/**
 * 「AI 生成字数」设定弹窗（用户 2026-09-17 要求：每个要素在点 AI 生成时由用户设定长度，上限 8000）。
 *
 * 已存过提纲时额外给出「重新生成提纲」开关：默认**复用上次提纲**（段数一致时）。
 */
const props = defineProps<{
  show: boolean
  /** 当前值（父层持有，并负责记忆） */
  value: number
  /** 说明这是哪个字段/哪些字段 */
  subject?: string
  /** 是否已存过该要素的提纲（决定是否显示「重新生成提纲」） */
  hasStoredOutline?: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  confirm: [value: number, refreshOutline: boolean]
}>()

const draft = ref(props.value)
const refresh = ref(false)

watch(
  () => [props.show, props.value] as const,
  () => {
    if (props.show) {
      draft.value = props.value
      refresh.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="设定 AI 生成字数"
    style="max-width: 520px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lead text-secondary">
      {{ subject ? `为「${subject}」设定目标字数。` : '为本轮生成设定目标字数。' }}
      上限 <b>8000</b> 字；AI 会分段续写（每段 ≤2600 字），字数越多越慢。
    </p>

    <ScriptLengthField v-model="draft" />

    <NCheckbox v-if="hasStoredOutline" v-model:checked="refresh" class="refresh">
      重新生成写作提纲（默认复用上次——段数一致时直接沿用，省一次调用）
    </NCheckbox>

    <template #footer>
      <div class="foot">
        <NButton quaternary @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" @click="emit('confirm', draft, refresh)">开始生成</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lead { margin: 0 0 16px; font-size: 13px; line-height: 1.8; }
.refresh { margin-top: 16px; font-size: 12.5px; }
.foot { display: flex; justify-content: flex-end; gap: 10px; }
</style>
