<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import type { RevisionSummary } from '@/api/types'

const props = defineProps<{
  revisions: RevisionSummary[]
  activeId: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [revisionId: string] }>()

const latest = computed(() => props.revisions[0] ?? null)
const active = computed(
  () => props.revisions.find((r) => r.id === props.activeId) ?? props.revisions[0] ?? null,
)
const activeNo = computed(() => active.value?.revisionNo ?? null)

// 移动端折叠：版本列表收起为「vN · 共N版」胶囊，展开才显示全部版本按钮（不占宽度、不挤按钮）
const BREAKPOINT = 760
const compact = ref(false)
const expanded = ref(false)
let mq: MediaQueryList | null = null
function syncCompact(): void {
  const now = mq ? mq.matches : window.innerWidth < BREAKPOINT
  compact.value = now
  if (!now) expanded.value = false
}
onMounted(() => {
  mq = window.matchMedia(`(max-width: ${BREAKPOINT - 1}px)`)
  syncCompact()
  mq.addEventListener('change', syncCompact)
})
onBeforeUnmount(() => {
  mq?.removeEventListener('change', syncCompact)
  mq = null
})

function pick(id: string): void {
  expanded.value = false
  emit('select', id)
}
</script>

<template>
  <div class="rail" :class="{ compact: compact && revisions.length }">
    <div class="rail-left">
      <span class="rail-label font-mono">版本</span>
      <!-- 移动端折叠胶囊：只露当前版本号 + 版本数 -->
      <button
        v-if="compact && revisions.length"
        type="button"
        class="v-summary"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        <span class="vs-no font-mono">v{{ activeNo ?? '—' }}</span>
        <span v-if="active?.approved" class="vs-tick">✓</span>
        <span class="vs-meta">共 {{ revisions.length }} 版</span>
        <span class="vs-caret font-mono">{{ expanded ? '▴' : '▾' }}</span>
      </button>
      <span v-else-if="!revisions.length" class="empty">尚无版本</span>
    </div>

    <!-- 版本按钮（桌面常显 / 移动端展开才显） -->
    <div v-if="revisions.length" class="versions" :class="{ open: expanded }">
      <button
        v-for="r in revisions"
        :key="r.id"
        type="button"
        :class="['v', { active: r.id === props.activeId, approved: r.approved }]"
        :title="`${r.revisionNo} · ${r.source}${r.approved ? ' · 已确认' : ''}`"
        @click="pick(r.id)"
      >
        v{{ r.revisionNo }}<span v-if="r.approved" class="tick">✓</span>
      </button>
    </div>

    <div class="rail-right">
      <slot />
    </div>

    <div
      v-if="latest && !latest.approved && latest.id === props.activeId"
      class="unsaved-dot"
      title="当前版本未确认"
    />
  </div>
</template>

<style scoped>
.rail {
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
  padding: 10px 14px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  min-width: 0;
}
.rail-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
  min-width: 0;
}
.rail-label {
  font-size: 10px;
  letter-spacing: 0.24em;
  color: var(--wv-text-4);
  flex: none;
}
.versions {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.v {
  appearance: none;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  color: var(--wv-text-2);
  font-family: var(--wv-font-mono);
  font-size: 12px;
  border-radius: 999px;
  padding: 3px 12px;
  cursor: pointer;
  transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.v:hover {
  border-color: var(--wv-line-strong);
}
.v.active {
  border-color: var(--wv-accent);
  color: var(--wv-text);
  background: var(--wv-accent-soft);
}
.v.approved .tick {
  color: var(--wv-success);
  margin-left: 3px;
}
.empty {
  font-size: 12px;
  color: var(--wv-text-4);
}
.rail-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  min-width: 0;
}
.unsaved-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--wv-accent);
  position: absolute;
  right: 8px;
  top: -4px;
  box-shadow: 0 0 0 3px var(--wv-bg);
}

/* ===== 移动端：整卡取消吸底锚定；版本折叠；操作按钮换行不再挤出卡片 ===== */
.rail.compact {
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
}
.rail.compact .rail-left {
  justify-content: space-between;
}
.v-summary {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--wv-line-strong);
  background: var(--wv-surface-sunken);
  color: var(--wv-text-2);
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
  min-width: 0;
}
.v-summary:hover {
  border-color: var(--wv-accent);
}
.vs-no {
  font-size: 12px;
  color: var(--wv-text);
  font-weight: 600;
}
.vs-tick {
  color: var(--wv-success);
  font-size: 12px;
}
.vs-meta {
  font-size: 11px;
  color: var(--wv-text-4);
  white-space: nowrap;
}
.vs-caret {
  font-size: 10px;
  color: var(--wv-text-4);
}
.rail.compact .versions {
  display: none;
  width: 100%;
  flex: none;
  padding-top: 10px;
  border-top: 1px dashed var(--wv-line);
}
.rail.compact .versions.open {
  display: flex;
  max-height: 168px;
  overflow-y: auto;
}
.rail.compact .rail-right {
  flex-wrap: wrap;
  justify-content: flex-start;
  width: 100%;
}
.rail.compact .rail-right :deep(button) {
  flex: 0 1 auto;
}
</style>
