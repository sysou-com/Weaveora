<script setup lang="ts">
import { Film } from 'lucide-vue-next'
import { NButton, NIcon, NInput, NInputNumber, NSwitch } from 'naive-ui'
import { computed, watch } from 'vue'

import ShotCard from '@/components/director/ShotCard.vue'
import type { DirectorShot, ShotRecord, VideoPlan } from '@/api/types'

const props = defineProps<{
  plan: VideoPlan
  /** 落库镜头状态（shotNo → status） */
  records?: ShotRecord[]
  /** 整版已确认：锁编辑 */
  disabled?: boolean
  busyShot?: number | null
}>()

const emit = defineEmits<{ approveShot: [shotNo: number]; aiPrompt: [shot: DirectorShot]; aiSyncAll: [] }>()

const hasZh = computed(() =>
  (props.plan.shots ?? []).some((s) => (s.zh ?? '').trim().length > 0))

/** 成片总时长 = 各镜时长之和；修改镜头时长后同步 plan.duration_sec。 */
const totalDur = computed(() =>
  (props.plan.shots ?? []).reduce((a, s) => a + (Number(s.duration_sec) || 0), 0))
watch(
  () => (props.plan.shots ?? []).map((s) => s.duration_sec).join(','),
  () => {
    props.plan.duration_sec = Math.round(totalDur.value * 100) / 100
  },
)

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

    <section class="block">
      <div class="zh-head">
        <p class="block-label font-mono" style="margin: 0">镜头文案 / AI 提示词</p>
        <NButton
          size="small"
          type="primary"
          secondary
          :disabled="!!disabled || !hasZh"
          data-testid="ai-sync-all"
          @click="emit('aiSyncAll')"
        >
          AI 同步全部提示词
        </NButton>
      </div>
      <div class="shot-prompt-list">
        <div v-for="shot in props.plan.shots" :key="shot.shot_no" class="shot-prompt">
          <div class="sp-title">第 {{ shot.shot_no }} 镜 · EN 提示词</div>
          <label class="ai-label">positive_prompt</label>
          <NInput v-model:value="shot.positive_prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="!!disabled" />
          <label class="ai-label">negative_prompt</label>
          <NInput v-model:value="shot.negative_prompt" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }" :disabled="!!disabled" />
        </div>
      </div>
      <div v-for="shot in props.plan.shots" :key="shot.shot_no" class="zh-row">
        <span class="key narration-key">第 {{ shot.shot_no }} 镜</span>
        <NInput
          v-model:value="shot.zh"
          size="small"
          :disabled="!!disabled"
          maxlength="200"
          placeholder="本镜中文描述（可留空；填写后可用 AI 生成正/负提示词）"
        />
        <NButton
          size="small"
          secondary
          type="primary"
          :disabled="!!disabled || !shot.zh || !shot.zh.trim()"
          data-testid="ai-prompt"
          @click="emit('aiPrompt', shot)"
        >
          AI 生成提示词
        </NButton>
      </div>
    </section>

    <section class="block">
      <p class="block-label font-mono">镜头时长（成片总长 {{ totalDur.toFixed(2) }}s）</p>
      <p class="hint-line text-secondary">
        逐镜调节时长后「保存方案」即生效；云端按镜头计费，短镜更省。留空镜将跳过字幕，时长需 ≥1s。
      </p>
      <div
        v-for="shot in props.plan.shots"
        :key="shot.shot_no"
        class="narration-row"
      >
        <span class="key narration-key">第 {{ shot.shot_no }} 镜</span>
        <NInputNumber
          v-model:value="shot.duration_sec"
          :min="1"
          :max="10"
          :step="0.5"
          size="small"
          style="width: 120px"
          :disabled="!!disabled"
        />
        <span class="hint-line text-secondary">秒</span>
      </div>
    </section>

    <section v-if="props.plan.edit_plan.subtitle" class="block">
      <p class="block-label font-mono">旁白 / 字幕（每镜一句，渲染时烧录到成片）</p>
      <div
        v-for="shot in props.plan.shots"
        :key="shot.shot_no"
        class="narration-row"
      >
        <span class="key narration-key">第 {{ shot.shot_no }} 镜</span>
        <NInput
          v-model:value="shot.narration"
          size="small"
          :disabled="!!disabled"
          maxlength="80"
          show-count
          placeholder="输入本镜旁白（留空则本镜不烧字幕）"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.narration-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.ai-label {
  display: block;
  font-size: 12px;
  color: var(--wv-text-3);
  margin: 6px 0 4px;
}
.shot-prompt {
  border: 1px solid var(--wv-divider);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.sp-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}
.zh-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.zh-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.zh-row .n-input {
  flex: 1;
}
.hint-line {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
}
.narration-key {
  flex: none;
  width: 60px;
}
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
