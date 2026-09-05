<script setup lang="ts">
import { computed } from 'vue'

import type { RevisionSummary } from '@/api/types'

const props = defineProps<{
  revisions: RevisionSummary[]
  activeId: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [revisionId: string] }>()

const latest = computed(() => props.revisions[0] ?? null)
</script>

<template>
  <div class="rail">
    <div class="rail-left">
      <span class="rail-label font-mono">版本</span>
      <div class="versions">
        <button
          v-for="r in props.revisions"
          :key="r.id"
          type="button"
          :class="['v', { active: r.id === props.activeId, approved: r.approved }]"
          :title="`${r.revisionNo} · ${r.source}${r.approved ? ' · 已确认' : ''}`"
          @click="emit('select', r.id)"
        >
          v{{ r.revisionNo }}<span v-if="r.approved" class="tick">✓</span>
        </button>
        <span v-if="!props.revisions.length" class="empty">尚无版本</span>
      </div>
    </div>
    <div class="rail-right">
      <slot />
    </div>
    <div v-if="latest && !latest.approved && latest.id === props.activeId" class="unsaved-dot" title="当前版本未确认" />
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
}
.rail-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.rail-label {
  font-size: 10px;
  letter-spacing: 0.24em;
  color: var(--wv-text-4);
  flex: none;
}
.versions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
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
</style>
