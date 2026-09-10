<script setup lang="ts">
import { NButton, NInput, NInputNumber } from 'naive-ui'

import type { ImagePlan } from '@/api/types'

const props = defineProps<{ plan: ImagePlan; disabled?: boolean }>()
const emit = defineEmits<{ aiPromptZh: [] }>()

const sizeOptions = ['extreme-wide', 'wide', 'medium', 'close-up', 'extreme-close-up'].map((v) => ({
  label: v,
  value: v,
}))
</script>

<template>
  <div class="editor-stack" data-testid="image-plan-editor">
    <section class="block">
      <p class="block-label font-mono">一句话与中文解释</p>
      <label class="row">
        <span class="key">标题 title</span>
        <NInput v-model:value="props.plan.title" size="small" :disabled="disabled" />
      </label>
      <label class="row">
        <span class="key">一句话 logline</span>
        <NInput v-model:value="props.plan.logline" size="small" :disabled="disabled" />
      </label>
      <label class="row">
        <span class="key">中文解释 prompt_zh</span>
        <NInput
          v-model:value="props.plan.prompt_zh"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          :disabled="disabled"
        />
      </label>
      <div class="zh-head">
        <NButton
          size="small"
          type="primary"
          secondary
          :disabled="!!disabled || !(props.plan.prompt_zh || '').trim()"
          data-testid="img-ai-sync"
          @click="emit('aiPromptZh')"
        >
          AI 同步提示词（按中文更新正/负向）
        </NButton>
        <span class="text-secondary" style="font-size: 12px">修改中文后点此用 LLM 重写 EN 正负提示词（可确认/取消）</span>
      </div>
    </section>

    <section class="block">
      <p class="block-label font-mono">提示词（EN · 给生成引擎）</p>
      <label class="row">
        <span class="key">正向 positive_prompt</span>
        <NInput
          v-model:value="props.plan.positive_prompt"
          type="textarea"
          :autosize="{ minRows: 5, maxRows: 10 }"
          :disabled="disabled"
          data-testid="img-positive"
        />
      </label>
      <label class="row">
        <span class="key">负向 negative_prompt</span>
        <NInput
          v-model:value="props.plan.negative_prompt"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
          :disabled="disabled"
        />
      </label>
      <label class="row">
        <span class="key">光 lighting</span>
        <NInput v-model:value="props.plan.lighting" size="small" :disabled="disabled" />
      </label>
    </section>

    <section class="block">
      <p class="block-label font-mono">镜头建议（导演可解释项）</p>
      <div class="grid3">
        <label class="row">
          <span class="key">焦距 mm</span>
          <NInputNumber v-model:value="props.plan.camera.focal_mm" size="small" :min="8" :max="200" :disabled="disabled" />
        </label>
        <label class="row">
          <span class="key">景别</span>
          <select v-model="props.plan.camera.shot_size" class="select" :disabled="disabled">
            <option v-for="o in sizeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label class="row">
          <span class="key">机位角</span>
          <select v-model="props.plan.camera.angle" class="select" :disabled="disabled">
            <option value="eye">eye</option>
            <option value="low">low</option>
            <option value="high">high</option>
          </select>
        </label>
      </div>
      <!-- P2 机位原子字段：写明视角/遮挡/朝向/对焦，防“背影穿过看脸”类机位丢失 -->
      <div class="grid2">
        <label class="row">
          <span class="key">视角 viewpoint</span>
          <select v-model="props.plan.camera.viewpoint" class="select" :disabled="disabled">
            <option value="">（未指定）</option>
            <option value="behind">behind（在主体背后）</option>
            <option value="from-front">from-front（正面）</option>
            <option value="over-shoulder">over-shoulder（过肩）</option>
            <option value="profile">profile（侧面）</option>
            <option value="three-quarter">three-quarter（三四分）</option>
            <option value="top-down">top-down（俯视）</option>
          </select>
        </label>
        <label class="row">
          <span class="key">对焦主体 focus_subject</span>
          <NInput v-model:value="props.plan.camera.focus_subject" size="small" placeholder="the queen's face" :disabled="disabled" />
        </label>
        <label class="row">
          <span class="key">前景遮挡 foreground</span>
          <NInput v-model:value="props.plan.camera.foreground" size="small" placeholder="the monk's back fills the foreground, blurred" :disabled="disabled" />
        </label>
        <label class="row">
          <span class="key">主体朝向 subject_axis</span>
          <NInput v-model:value="props.plan.camera.subject_axis" size="small" placeholder="monk back to camera; queen faces camera" :disabled="disabled" />
        </label>
        <label class="row span2">
          <span class="key">构图 composition</span>
          <NInput v-model:value="props.plan.camera.composition" size="small" placeholder="over-the-shoulder framing past the monk toward the queen" :disabled="disabled" />
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
.editor-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
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
.grid2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}
.grid2 .span2 {
  grid-column: 1 / -1;
}
@media (max-width: 760px) {
  .grid2 {
    grid-template-columns: 1fr;
  }
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

<style scoped>
.zh-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}
</style>
