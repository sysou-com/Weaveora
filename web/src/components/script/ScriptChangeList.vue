<script setup lang="ts">
import { NModal, NSkeleton } from 'naive-ui'
import { computed } from 'vue'

import type { ScriptChange } from '@/api/types'
import { formatDate } from '@/utils/format'

/** 变更记录：谁在什么时候改了什么，含「被一并更新的历史章节」（用户原话要求可追溯）。 */
const props = defineProps<{
  show: boolean
  changes: ScriptChange[]
  loading?: boolean
}>()

const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const KIND_LABEL: Record<string, string> = {
  episode_create: '新增一集',
  episode_update: '更新一集',
  consistency_proposal: '一致性检查（建议）',
  consistency_applied: '一致性改写（已应用）',
  condensed_refresh: '刷新精简故事',
  field_update: '更新剧本要素',
}

const rows = computed(() => props.changes ?? [])

function kindLabel(k: string): string {
  return KIND_LABEL[k] ?? k
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="变更记录"
    style="max-width: 720px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div v-if="loading" class="list">
      <div v-for="i in 4" :key="i" class="row"><NSkeleton text /><NSkeleton text style="margin-top:8px" /></div>
    </div>
    <div v-else-if="rows.length" class="list">
      <div v-for="c in rows" :key="c.id" class="row">
        <div class="row-top">
          <span class="kind font-mono" :class="{ ai: c.actor === 'ai' }">{{ kindLabel(c.kind) }}</span>
          <span v-if="c.episodeNo" class="ep font-mono">第 {{ c.episodeNo }} 集</span>
          <span class="time font-mono">{{ formatDate(c.createdAt) }}</span>
        </div>
        <p v-if="c.note" class="note text-secondary">{{ c.note }}</p>
        <div v-if="c.changedEpisodes?.length" class="chips">
          <span class="chip-hint text-secondary">涉及章节：</span>
          <span v-for="e in c.changedEpisodes" :key="`${e.episodeNo}-${e.what}`" class="chip">
            第 {{ e.episodeNo }} 集 · {{ e.what || e.title }}
          </span>
        </div>
      </div>
    </div>
    <p v-else class="text-secondary empty">还没有变更记录。</p>
  </NModal>
</template>

<style scoped>
.list { display: flex; flex-direction: column; gap: 10px; max-height: 60vh; overflow: auto; }
.row { padding: 12px 14px; border: 1px solid var(--wv-line); border-radius: var(--wv-radius-s); background: var(--wv-surface-sunken); }
.row-top { display: flex; align-items: center; gap: 10px; }
.kind { font-size: 11px; letter-spacing: 0.08em; color: var(--wv-accent-text); }
.kind.ai { color: #c8a86d; }
.ep { font-size: 11px; color: var(--wv-text-3); }
.time { margin-left: auto; font-size: 11px; color: var(--wv-text-4); }
.note { margin: 6px 0 0; font-size: 12.5px; line-height: 1.7; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
.chip-hint { font-size: 11px; }
.chip { font-size: 11px; padding: 3px 8px; border-radius: 999px; background: var(--wv-surface-raised); border: 1px solid var(--wv-line); color: var(--wv-text-2); }
.empty { margin: 0; font-size: 13px; }
</style>
