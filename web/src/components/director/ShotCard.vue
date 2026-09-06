<script setup lang="ts">
import { Check, ChevronDown } from 'lucide-vue-next'
import { NButton, NIcon, NInput } from 'naive-ui'
import { computed, ref } from 'vue'

import type { DirectorShot } from '@/api/types'

const props = withDefaults(
  defineProps<{
    shot: DirectorShot
    /** 落库状态（approved 等），用于显示确认态 */
    status?: string
    /** 已整版确认或生成中：锁编辑 */
    disabled?: boolean
    busy?: boolean
  }>(),
  { status: 'draft', disabled: false, busy: false },
)

const emit = defineEmits<{ approve: [shotNo: number] }>()

const approved = computed(() => props.status === 'approved')

/** 分镜折叠板：默认收起，只展示“画面动作”摘要，展开才显示全部字段 */
const open = ref(false)
const summaryText = computed(() => {
  const a = props.shot.action?.trim()
  if (a) return a
  const p = props.shot.positive_prompt?.trim() ?? ''
  return p.length > 160 ? `${p.slice(0, 160)}…` : p || '（暂无动作描述，展开编辑）'
})

const sizeOptions = [
  { label: '远景 EW', value: 'extreme-wide' },
  { label: '全景 W', value: 'wide' },
  { label: '中景 M', value: 'medium' },
  { label: '近景 CU', value: 'close-up' },
  { label: '特写 ECU', value: 'extreme-close-up' },
]
</script>

<template>
  <article :class="['shot-card', { approved, locked: disabled }]" :data-testid="`shot-card-${shot.shot_no}`">
    <header class="shot-head">
      <div class="shot-tag font-mono">
        <span class="dot" :class="{ on: approved }" />
        SHOT {{ shot.shot_no }}
        <span class="dur">{{ Number(shot.duration_sec).toFixed(2) }}s</span>
      </div>
      <NButton
        v-if="!disabled && !approved"
        size="tiny"
        secondary
        type="primary"
        :loading="busy"
        :data-testid="`shot-approve-${shot.shot_no}`"
        @click="emit('approve', shot.shot_no)"
      >
        <template #icon><NIcon><Check :size="13" /></NIcon></template>
        确认此镜
      </NButton>
      <span v-else-if="approved" class="approved-chip font-mono">✓ APPROVED</span>
      <span v-else-if="disabled" class="approved-chip dim font-mono">整版已确认</span>
    </header>

    <!-- 折叠摘要：默认只展示画面动作 -->
    <button
      type="button"
      class="shot-summary"
      :class="{ on: open }"
      :aria-expanded="open"
      data-testid="shot-toggle"
      @click="open = !open"
    >
      <span class="sum-label font-mono">画面动作</span>
      <span class="sum-text">{{ summaryText }}</span>
      <NIcon size="14" class="sum-chev"><ChevronDown /></NIcon>
    </button>

    <div v-show="open" class="shot-grid">
      <label class="field">
        <span class="fl">景别</span>
        <select v-model="shot.shot_size" class="select" :disabled="disabled">
          <option v-for="o in sizeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
      </label>
      <label class="field">
        <span class="fl">运镜</span>
        <input v-model="shot.camera_move" class="text" type="text" placeholder="slow dolly in" :disabled="disabled" />
      </label>
      <label class="field wide">
        <span class="fl">画面动作（中文描述，供确认/剪辑用）</span>
        <NInput
          v-model:value="shot.action"
          size="small"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 3 }"
          :disabled="disabled"
          placeholder="本镜发生什么"
        />
      </label>
      <label class="field wide">
        <span class="fl">正向提示词（EN，20–1200）</span>
        <NInput
          v-model:value="shot.positive_prompt"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
          :disabled="disabled"
          data-testid="shot-positive"
        />
      </label>
      <label class="field wide">
        <span class="fl">负向提示词</span>
        <NInput
          v-model:value="shot.negative_prompt"
          size="small"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          :disabled="disabled"
        />
      </label>
    </div>
  </article>
</template>

<style scoped>
.shot-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  transition: border-color var(--wv-dur) var(--wv-ease);
}
.shot-card.approved {
  border-color: color-mix(in srgb, var(--wv-success) 45%, var(--wv-line));
}
.shot-card.locked {
  opacity: 0.85;
}

.shot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.shot-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 0.14em;
  color: var(--wv-text-3);
}
.shot-tag .dur {
  color: var(--wv-text-4);
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 4px;
  padding: 1px 6px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wv-line-strong);
}
.dot.on {
  background: var(--wv-success);
}
.approved-chip {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-success);
}
.approved-chip.dim {
  color: var(--wv-text-4);
}

/* 折叠摘要条：默认收起只展示画面动作 */
.shot-summary {
  appearance: none;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  border-radius: 8px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  color: var(--wv-text-2);
  cursor: pointer;
  transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.shot-summary:hover {
  border-color: color-mix(in srgb, var(--wv-accent) 45%, var(--wv-line));
  background: var(--wv-surface-raised);
}
.shot-summary .sum-label {
  flex: none;
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-accent-text);
  background: var(--wv-accent-soft);
  padding: 3px 8px;
  border-radius: 5px;
}
.shot-summary .sum-text {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--wv-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.shot-summary .sum-chev {
  flex: none;
  color: var(--wv-text-4);
  transition: transform var(--wv-dur) var(--wv-ease);
}
.shot-summary.on .sum-chev {
  transform: rotate(180deg);
}

.shot-grid {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 10px 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.field.wide {
  grid-column: 1 / -1;
}
.fl {
  font-size: 11px;
  color: var(--wv-text-4);
  letter-spacing: 0.04em;
}
.select,
.text {
  width: 100%;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 6px;
  color: var(--wv-text-2);
  font-family: var(--wv-font-mono);
  font-size: 12px;
  padding: 5px 8px;
  outline: none;
}
.select:focus,
.text:focus {
  border-color: color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
}
.select:disabled,
.text:disabled {
  opacity: 0.6;
}
</style>
