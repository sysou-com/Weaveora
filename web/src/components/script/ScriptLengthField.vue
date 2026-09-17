<script setup lang="ts">
import { NInputNumber, NSlider } from 'naive-ui'
import { computed } from 'vue'

import { SCRIPT_FIELD_MAX, SCRIPT_TARGET_MIN, clampTarget } from '@/utils/script'

/**
 * 「目标字数」控件（可复用）：滑块 + 输入框 + 预设 + 预估耗时。
 *
 * 被两处使用：① 要素/批量生成的字数弹窗（ScriptLengthDialog）；② 开始下一集的分集字数（NextEpisodeDialog 内嵌）。
 * 说明给用户的两个事实：上限 8000 是接口硬上限；AI 是**分段续写**的（每段 ≤2600 字），字数越多越慢。
 */
const props = defineProps<{
  modelValue: number
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: number] }>()

const PRESETS = [1000, 2000, 4000, 6000, 8000]

const value = computed({
  get: () => props.modelValue,
  set: (v: number | null) => emit('update:modelValue', clampTarget(v ?? 4000)),
})

/** 预估耗时（分段续写：每段约 2200 字、实测约 30–45s） */
const eta = computed(() => {
  const passes = Math.max(1, Math.min(5, Math.ceil(value.value / 2200)))
  return `${passes} 段 · 约 ${Math.round((passes * 30) / 60)}–${Math.round((passes * 45) / 60)} 分钟`
})
</script>

<template>
  <div class="len">
    <div class="row">
      <span class="lbl">{{ label ?? '目标字数' }}</span>
      <NInputNumber
        v-model:value="value"
        :min="SCRIPT_TARGET_MIN"
        :max="SCRIPT_FIELD_MAX"
        :step="100"
        size="large"
        style="width: 150px"
      />
      <span class="eta font-mono text-secondary">{{ eta }}</span>
    </div>

    <NSlider
      v-model:value="value"
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
        :class="{ on: value === p }"
        @click="value = p"
      >
        {{ p === 8000 ? '8000（上限）' : p }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.len { display: flex; flex-direction: column; }
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
</style>
