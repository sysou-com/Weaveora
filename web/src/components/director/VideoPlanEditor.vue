<script setup lang="ts">
import { Film } from 'lucide-vue-next'
import { NIcon, NInput, NInputNumber, NSwitch } from 'naive-ui'
import { computed } from 'vue'

import ShotCard from '@/components/director/ShotCard.vue'
import type { ShotRecord, VideoPlan } from '@/api/types'

const props = defineProps<{
  plan: VideoPlan
  /** 落库镜头状态（shotNo → status） */
  records?: ShotRecord[]
  /** 整版已确认：锁编辑 */
  disabled?: boolean
  busyShot?: number | null
}>()

const emit = defineEmits<{ approveShot: [shotNo: number] }>()

const statusOf = (no: number): string =>
  props.records?.find((r) => r.shotNo === no)?.status ?? 'draft'

const approvedAll = computed(() => props.disabled)

const transitions = ['cut', 'dissolve', 'fade', 'wipe'].map((v) => ({ label: v, value: v }))
</script>

<template>
  <div class="editor-stack" data-testid="video-plan-editor">
    <section class="block">
      <p class="block-label font-mono">主题</p>
      <label class="row">
        <span class="key">一句话 logline</span>
        <NInput v-model:value="props.plan.logline" size="small" :disabled="disabled" />
      </label>
      <label class="row">
        <span class="key">主题 theme（中文）</span>
        <NInput v-model:value="props.plan.script.theme" size="small" :disabled="disabled" />
      </label>
      <label class="row">
        <span class="key">BGM 情绪 music_mood</span>
        <NInput v-model:value="props.plan.audio.music_mood" size="small" :disabled="disabled" />
      </label>
    </section>

    <section class="block">
      <p class="block-label font-mono">
        <NIcon size="12" style="vertical-align: -1px"><Film /></NIcon>&nbsp;分镜 / 镜头表
        <span class="hint">（逐镜可改可单镜确认；先确认关键帧，运动在 W3）</span>
      </p>
      <div class="shot-list">
        <ShotCard
          v-for="shot in props.plan.shots"
          :key="shot.shot_no"
          :shot="shot"
          :status="statusOf(shot.shot_no)"
          :disabled="!!disabled || approvedAll"
          :busy="busyShot === shot.shot_no"
          @approve="emit('approveShot', $event)"
        />
      </div>
    </section>

    <section class="block">
      <p class="block-label font-mono">编辑设定 edit_plan</p>
      <div class="grid3">
        <label class="row">
          <span class="key">fps</span>
          <NInputNumber v-model:value="props.plan.edit_plan.fps" size="small" :min="24" :max="60" :disabled="disabled" />
        </label>
        <label class="row">
          <span class="key">默认转场</span>
          <select v-model="props.plan.edit_plan.transition_default" class="select" :disabled="disabled">
            <option v-for="t in transitions" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </label>
        <label class="row switch-row">
          <span class="key">字幕</span>
          <NSwitch v-model:value="props.plan.edit_plan.subtitle" size="small" :disabled="disabled" />
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
.editor-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.block-label {
  margin: 0;
  font-size: 10px;
  letter-spacing: 0.22em;
  color: var(--wv-text-4);
}
.hint {
  letter-spacing: 0;
  font-family: var(--wv-font-sans);
  color: var(--wv-text-4);
  font-size: 11px;
}
.row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.key {
  font-size: 11.5px;
  color: var(--wv-text-3);
  font-family: var(--wv-font-mono);
}
.grid3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.switch-row {
  justify-content: center;
}
.shot-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.select {
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 6px;
  color: var(--wv-text-2);
  font-family: var(--wv-font-mono);
  font-size: 12px;
  padding: 6px 8px;
  outline: none;
}
.select:focus {
  border-color: color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
}
</style>
