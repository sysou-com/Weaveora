<script setup lang="ts">
/**
 * 版本栏（方案侧栏底部）。
 *
 * 2026-09-16 改版（用户要求）：版本号从「一排 v1 v2 v3 按钮」改成**可搜索的下拉框**，
 * 默认就是**最新版本**（`revisions[0]`，列表按 revisionNo 倒序）。
 *
 * 为什么：版本一多（改一版存一版），一排按钮既占宽度又会换行挤掉右侧的保存/确认按钮；
 * 下拉框宽度固定、可输入 `v7`/`3` 直接搜，也不再需要移动端折叠胶囊那套逻辑。
 */
import { NSelect } from 'naive-ui'
import { computed } from 'vue'

import type { RevisionSummary } from '@/api/types'
import { SOURCE_LABEL } from '@/utils/plan'

const props = defineProps<{
  revisions: RevisionSummary[]
  activeId: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [revisionId: string] }>()

const active = computed(
  () => props.revisions.find((r) => r.id === props.activeId) ?? props.revisions[0] ?? null,
)

/** 下拉选项：最新在最前；label 带「已确认」标记，方便认基准稿 */
const options = computed(() =>
  props.revisions.map((r) => ({
    value: r.id,
    label: `v${r.revisionNo} · ${SOURCE_LABEL[r.source] ?? r.source}${r.approved ? ' ✓已确认' : ''}`,
    /** 供 filterable 搜索用（输入 v7 / 7 / 手工 都能命中） */
    keywords: `v${r.revisionNo} ${r.revisionNo} ${r.source} ${r.approved ? '已确认 confirmed' : ''}`,
  })),
)

/** 当前选中值：activeId 为空/失效时回落到最新版本 */
const value = computed(() => active.value?.id ?? null)

function pick(id: string | null): void {
  if (id && id !== props.activeId) {
    emit('select', id)
  }
}
</script>

<template>
  <div class="rail">
    <div class="rail-left">
      <span class="rail-label font-mono">版本</span>
      <span v-if="!revisions.length" class="empty">尚无版本</span>
      <NSelect
        v-else
        class="rev-select"
        :value="value"
        :options="options"
        size="small"
        filterable
        :disabled="disabled"
        :consistent-menu-width="false"
        placeholder="选择版本"
        data-testid="revision-select"
        @update:value="pick"
      />
      <span v-if="active?.approved" class="rev-approved font-mono">基准稿</span>
      <span
        v-else-if="active"
        class="rev-unapproved font-mono"
        title="当前版本还未确认（确认后才作为生成/出片的基准稿）"
      >未确认</span>
    </div>

    <div class="rail-right">
      <slot />
    </div>
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
  gap: 10px;
  flex: none;
  min-width: 0;
}
.rail-label {
  font-size: 10.5px;
  letter-spacing: 0.16em;
  color: var(--wv-text-4);
}
.rev-select {
  width: 240px;
  max-width: 46vw;
}
.rev-approved {
  font-size: 10.5px;
  color: var(--wv-success, #7aa87a);
  letter-spacing: 0.08em;
}
.rev-unapproved {
  font-size: 10.5px;
  color: var(--wv-text-4);
  letter-spacing: 0.08em;
}
.empty {
  font-size: 12px;
  color: var(--wv-text-4);
}
.rail-right {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  flex-wrap: wrap;
  min-width: 0;
}
</style>
