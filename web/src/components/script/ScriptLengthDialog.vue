<script setup lang="ts">
import { NButton, NInputNumber, NModal, NSlider } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import { SCRIPT_FIELD_MAX, SCRIPT_TARGET_MIN, clampTarget } from '@/utils/script'

/**
 * 「AI 生成字数」设定弹窗（用户 2026-09-17 要求：每个要素在点 AI 生成时由用户设定长度，上限 8000）。
 *
 * 说明给用户看的两个事实：
 * ① 上限 8000 是接口与字段硬上限；
 * ② AI 是**分段续写**的（每段 ≤2600 字），字数越多耗时越长（约每 2000 字 30–40 秒）。
 */
const props = defineProps<{
  show: boolean
  /** 当前值（父层持有，并负责记忆） */
  value: number
  /** 说明这是哪个字段/哪些字段 */
  subject?: string
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  confirm: [value: number]
}>()

const draft = ref(props.value)
const PRESETS = [1000, 2000, 4000, 6000, 8000]

watch(
  () => [props.show, props.value] as const,
  () => {
    if (props.show) draft.value = props.value
  },
  { immediate: true },
)

/** 估算耗时（分段续写：每段约 2200 字、实测约 30–40s） */
const eta = computed(() => {
  const passes = Math.max(1, Math.min(5, Math.ceil(draft.value / 2200)))
  const lo = passes * 30
  const hi = passes * 45
  return `${passes} 段 · 约 ${Math.round(lo / 60)}–${Math.round(hi / 60)} 分钟`
})

function safe(n: number | null): number {
  return clampTarget(n ?? 4000)
}
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
      上限 <b>{{ SCRIPT_FIELD_MAX }}</b> 字；AI 会分段续写（每段 ≤2600 字），字数越多越慢。
    </p>

    <div class="row">
      <span class="lbl">目标字数</span>
      <NInputNumber
        v-model:value="draft"
        :min="SCRIPT_TARGET_MIN"
        :max="SCRIPT_FIELD_MAX"
        :step="100"
        size="large"
        style="width: 150px"
        @blur="draft = safe(draft)"
      />
      <span class="eta font-mono text-secondary">{{ eta }}</span>
    </div>

    <NSlider
      v-model:value="draft"
      :min="SCRIPT_TARGET_MIN"
      :max="SCRIPT_FIELD_MAX"
      :step="100"
      :marks="{ 500: '500', 2000: '2000', 4000: '4000', 6000: '6000', 8000: '8000' }"
    />

    <div class="presets">
      <button
        v-for="p in PRESETS"
        :key="p"
        type="button"
        class="preset"
        :class="{ on: draft === p }"
        @click="draft = p"
      >
        {{ p === 8000 ? '8000（上限）' : p }}
      </button>
    </div>

    <template #footer>
      <div class="foot">
        <NButton quaternary @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" @click="emit('confirm', safe(draft))">开始生成</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lead { margin: 0 0 16px; font-size: 13px; line-height: 1.8; }
.row { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.lbl { font-size: 13px; color: var(--wv-text-3); }
.eta { margin-left: auto; font-size: 12px; }
.presets { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 20px; }
.preset {
  appearance: none; border: 1px solid var(--wv-line-strong); background: transparent;
  color: var(--wv-text-3); font-size: 12px; line-height: 1; padding: 7px 12px;
  border-radius: 6px; cursor: pointer;
}
.preset:hover { color: var(--wv-text); background: var(--wv-surface-raised); }
.preset.on { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); background: var(--wv-accent-soft); }
.foot { display: flex; justify-content: flex-end; gap: 10px; }
</style>
