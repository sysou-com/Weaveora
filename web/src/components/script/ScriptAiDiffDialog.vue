<script setup lang="ts">
import { ChevronDown, ChevronRight } from 'lucide-vue-next'
import { NButton, NIcon, NModal } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import { diffLines } from '@/utils/script'

/** 一处待确认的 AI 改动（单字段或批量共用一个弹层，Q3：先弹 diff 再写入） */
export interface AiDiffItem {
  key: string
  label: string
  before: string
  after: string
  note?: string
  /** 【B】本次生成的分段提纲（每段一行） */
  outline?: string[]
}

const props = defineProps<{
  show: boolean
  items: AiDiffItem[]
  title?: string
  /** 应用后是否清空（父层控制） */
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  apply: [keys: string[]]
}>()

const checked = ref<string[]>([])
const open = ref<string[]>([])

watch(
  () => [props.show, props.items] as const,
  () => {
    if (!props.show) return
    checked.value = props.items.map((i) => i.key)
    open.value = props.items.length === 1 ? [props.items[0].key] : []
  },
  { immediate: true },
)

const visible = computed({
  get: () => props.show,
  set: (v: boolean) => emit('update:show', v),
})

function toggle(key: string): void {
  checked.value = checked.value.includes(key)
    ? checked.value.filter((k) => k !== key)
    : [...checked.value, key]
}

function toggleOpen(key: string): void {
  open.value = open.value.includes(key)
    ? open.value.filter((k) => k !== key)
    : [...open.value, key]
}

function lines(item: AiDiffItem) {
  return diffLines(item.before, item.after)
}

function addedCount(item: AiDiffItem): number {
  return lines(item).filter((l) => l.type === 'add').length
}

function delCount(item: AiDiffItem): number {
  return lines(item).filter((l) => l.type === 'del').length
}
</script>

<template>
  <NModal v-model:show="visible" preset="card" :title="title ?? 'AI 生成结果 · 请确认'" style="max-width: 880px">
    <p class="hint text-secondary">
      AI 只生成内容，<b>不会自动覆盖</b>——下面是改动对比（<span class="add">绿=新增</span> /
      <span class="del">红=移除</span>）。确认后才会写入对应字段。
    </p>

    <div class="list">
      <div v-for="item in items" :key="item.key" class="row">
        <label class="row-head">
          <input type="checkbox" :checked="checked.includes(item.key)" @change="toggle(item.key)" />
          <span class="row-label">{{ item.label }}</span>
          <span class="row-stat font-mono">+{{ addedCount(item) }} / -{{ delCount(item) }}</span>
          <button type="button" class="toggle" @click.prevent="toggleOpen(item.key)">
            <NIcon size="14"><component :is="open.includes(item.key) ? ChevronDown : ChevronRight" /></NIcon>
            对比
          </button>
        </label>
        <p v-if="item.note" class="row-note text-secondary">{{ item.note }}</p>
        <details v-if="item.outline?.length" class="outline" open>
          <summary>本次写作提纲（{{ item.outline.length }} 段）</summary>
          <p v-for="(seg, i) in item.outline" :key="i" class="seg">{{ seg }}</p>
        </details>
        <div v-if="open.includes(item.key)" class="diff">
          <p v-for="(l, i) in lines(item)" :key="i" :class="['dl', l.type]">{{ l.text }}</p>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="foot">
        <span class="text-secondary foot-hint">已选 {{ checked.length }} / {{ items.length }} 项</span>
        <NButton quaternary @click="visible = false">取消</NButton>
        <NButton type="primary" :disabled="!checked.length || busy" @click="emit('apply', [...checked])">
          应用所选
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.hint { margin: 0 0 12px; font-size: 12.5px; }
.hint .add { color: var(--wv-success); }
.hint .del { color: var(--wv-danger); }
.list { display: flex; flex-direction: column; gap: 10px; max-height: 56vh; overflow: auto; padding-right: 4px; }
.row { border: 1px solid var(--wv-line); border-radius: var(--wv-radius-s); background: var(--wv-surface-sunken); }
.row-head { display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer; }
.row-head input { accent-color: var(--wv-accent); }
.row-label { font-size: 14px; color: var(--wv-text); }
.row-stat { font-size: 11px; color: var(--wv-text-4); margin-left: auto; }
.toggle {
  display: inline-flex; align-items: center; gap: 4px;
  appearance: none; border: 1px solid var(--wv-line-strong); background: transparent;
  color: var(--wv-text-3); font-size: 11px; padding: 2px 8px; border-radius: 6px; cursor: pointer;
}
.toggle:hover { color: var(--wv-text); }
.row-note { margin: 0; padding: 0 12px 8px; font-size: 12px; }
.outline { padding: 0 12px 10px; font-size: 12px; color: var(--wv-text-2); }
.outline summary { cursor: pointer; color: var(--wv-accent-text); margin-bottom: 4px; }
.outline .seg { margin: 0 0 2px; line-height: 1.7; }
.diff {
  max-height: 260px; overflow: auto; padding: 8px 12px 12px;
  border-top: 1px solid var(--wv-divider);
  font-size: 12.5px; line-height: 1.7; white-space: pre-wrap; word-break: break-word;
}
.dl { margin: 0; }
.dl.add { color: var(--wv-success); background: color-mix(in srgb, var(--wv-success) 10%, transparent); }
.dl.del { color: var(--wv-danger); background: color-mix(in srgb, var(--wv-danger) 10%, transparent); text-decoration: line-through; }
.foot { display: flex; align-items: center; gap: 10px; }
.foot-hint { margin-right: auto; font-size: 12px; }
</style>
