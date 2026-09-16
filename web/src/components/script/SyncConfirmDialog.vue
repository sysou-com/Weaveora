<script setup lang="ts">
import { NButton, NModal } from 'naive-ui'
import { ref, watch } from 'vue'

import type { ScriptConflict } from '@/api/types'

/**
 * Q4「先确认再改」：AI 一致性检查发现**历史章节**需要改动时，先让用户确认。
 *
 * 未确认前后端不改任何历史章节，只把建议写入变更记录（kind=consistency_proposal）以备查。
 */
const props = defineProps<{
  show: boolean
  conflicts: ScriptConflict[]
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  apply: [items: ScriptConflict[]]
}>()

const checked = ref<number[]>([])

watch(
  () => [props.show, props.conflicts] as const,
  () => {
    if (props.show) checked.value = props.conflicts.map((c) => c.episodeNo ?? 0).filter((n) => n > 0)
  },
  { immediate: true },
)

function toggle(no: number | null): void {
  const n = no ?? 0
  checked.value = checked.value.includes(n) ? checked.value.filter((x) => x !== n) : [...checked.value, n]
}

function submit(): void {
  emit('apply', props.conflicts.filter((c) => checked.value.includes(c.episodeNo ?? 0)))
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="AI 建议同步修改以下历史章节"
    style="max-width: 720px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lead text-secondary">
      新写的内容与前面章节出现了不一致。下面是 AI 的检查结果——<b>只有你勾选并确认后才会改写</b>，
      改写会记入变更记录。也可以全部取消，自己去改。
    </p>

    <div class="list">
      <label v-for="c in conflicts" :key="c.episodeNo ?? 0" class="row">
        <input type="checkbox" :checked="checked.includes(c.episodeNo ?? 0)" @change="toggle(c.episodeNo)" />
        <div class="row-body">
          <p class="row-title">第 {{ c.episodeNo }} 集 · {{ c.title || '（未命名）' }}</p>
          <p v-if="c.issue" class="row-issue text-secondary">问题：{{ c.issue }}</p>
          <p v-if="c.fix" class="row-fix">建议改法：{{ c.fix }}</p>
        </div>
      </label>
    </div>

    <template #footer>
      <div class="foot">
        <span class="text-secondary hint">已选 {{ checked.length }} / {{ conflicts.length }} 集</span>
        <NButton quaternary :disabled="busy" @click="emit('update:show', false)">暂不改</NButton>
        <NButton type="primary" :disabled="busy || !checked.length" @click="submit">
          {{ busy ? '改写中…' : '确认并改写所选' }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lead { margin: 0 0 14px; font-size: 13px; line-height: 1.8; }
.list { display: flex; flex-direction: column; gap: 10px; max-height: 52vh; overflow: auto; }
.row {
  display: flex; gap: 10px; padding: 12px 14px; cursor: pointer;
  border: 1px solid var(--wv-line); border-radius: var(--wv-radius-s);
  background: var(--wv-surface-sunken);
}
.row input { accent-color: var(--wv-accent); margin-top: 3px; }
.row-body { display: flex; flex-direction: column; gap: 4px; }
.row-title { margin: 0; font-size: 14px; }
.row-issue { margin: 0; font-size: 12.5px; line-height: 1.7; }
.row-fix { margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--wv-accent-text); }
.foot { display: flex; align-items: center; gap: 10px; }
.hint { margin-right: auto; font-size: 12px; }
</style>
