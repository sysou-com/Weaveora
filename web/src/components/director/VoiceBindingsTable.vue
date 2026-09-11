<script setup lang="ts">
/**
 * P8 角色 → 音色绑定。说话人名字与分镜里的「说话人 subject」对齐即自动关联；
 * 未绑定的角色回落到 plan.audio.voice（默认音色）。
 */
import { Delete, Plus } from 'lucide-vue-next'
import { NButton, NIcon, NInputNumber, NSelect } from 'naive-ui'
import { computed } from 'vue'

import type { VideoPlan, VoiceBinding } from '@/api/types'

const props = withDefaults(
  defineProps<{
    plan: VideoPlan
    disabled?: boolean
    /** 音色选项（内置名 + 克隆音色 clone:<id>） */
    voices?: { label: string; value: string }[]
    /** 已知角色名（来自参考图主体 + 分镜里的说话人），供下拉 */
    knownSubjects?: string[]
  }>(),
  { disabled: false, voices: () => [], knownSubjects: () => [] },
)

const emit = defineEmits<{ 'update:plan': [VideoPlan] }>()

const rows = computed<VoiceBinding[]>(() => {
  if (!props.plan.audio) return []
  if (!Array.isArray(props.plan.audio.voiceBindings)) props.plan.audio.voiceBindings = []
  return props.plan.audio.voiceBindings
})

function commit(): void {
  emit('update:plan', props.plan)
}

function add(): void {
  rows.value.push({ subject: '', voice: props.voices[0]?.value ?? '中文女', speed: 1 })
  commit()
}

function remove(i: number): void {
  rows.value.splice(i, 1)
  commit()
}

const subjectOpts = computed(() => props.knownSubjects.filter(Boolean).map((s) => ({ label: s, value: s })))
const voiceOpts = computed(() => props.voices ?? [])

/** 该角色在分镜里被引用了几次（提示绑定是否生效） */
function usage(subject: string): number {
  if (!subject) return 0
  let n = 0
  for (const sh of props.plan.shots ?? []) {
    for (const l of sh.narrations ?? []) {
      if ((l.subject ?? '').trim() === subject.trim()) n++
    }
  }
  return n
}
</script>

<template>
  <div class="vb">
    <div v-for="(b, i) in rows" :key="i" class="vb-row">
      <NSelect
        v-model:value="b.subject"
        size="small"
        filterable
        tag
        :options="subjectOpts"
        :disabled="disabled"
        placeholder="角色名，如 关羽"
        style="width: 150px"
        :data-testid="`binding-subject-${i}`"
        @update:value="commit"
      />
      <span class="arrow">→</span>
      <NSelect
        v-model:value="b.voice"
        size="small"
        filterable
        tag
        :options="voiceOpts"
        :disabled="disabled"
        placeholder="音色"
        style="width: 150px"
        :data-testid="`binding-voice-${i}`"
        @update:value="commit"
      />
      <NInputNumber
        v-model:value="b.speed"
        size="small"
        :min="0.5"
        :max="2"
        :step="0.05"
        :disabled="disabled"
        placeholder="1.0"
        style="width: 96px"
        @update:value="commit"
      />
      <span class="usage text-secondary font-mono">
        {{ usage(b.subject) ? `用于 ${usage(b.subject)} 段台词` : '分镜里还没用到' }}
      </span>
      <NButton size="tiny" quaternary type="error" :disabled="disabled" @click="remove(i)">
        <template #icon><NIcon><Delete :size="12" /></NIcon></template>
      </NButton>
    </div>

    <div class="vb-bar">
      <NButton size="tiny" secondary :disabled="disabled" data-testid="binding-add" @click="add">
        <template #icon><NIcon><Plus :size="12" /></NIcon></template>
        加一个角色
      </NButton>
      <span class="text-secondary" style="font-size: 12px">
        角色名要和分镜里的「说话人」一致才会关联；未绑定的走默认音色
        <template v-if="plan.audio?.voice">（当前默认：{{ plan.audio.voice }}）</template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.vb {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.vb-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.arrow {
  opacity: 0.6;
}
.usage {
  font-size: 11px;
  min-width: 110px;
}
.vb-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
