<script setup lang="ts">
/**
 * 配音音色选择条（2026-09-19 用户要求 ③：从「声音」卡搬到「参考图」下方）。
 *
 * 与旧位置的差别：
 *   1) **不再显示「录好的音色列表」**（原来那排 vc-lib 列表已删）—— 只用下拉搜索框选一个，
 *      选中克隆音色后「重录 / 改名 / 删除」才可用（问题就在下拉里解决）；
 *   2) 住在「参考图」卡片里 —— 音色与参考图同属「生成素材」，放一起更顺手。
 *
 * 为什么改名用 window.prompt：与旧实现一致（旧的是列表内联编辑，列表删掉后没有落点了），
 * 改动最小；后面要做成 NModal 也不影响这里的接口。
 */
import { NButton, NSelect } from 'naive-ui'
import { computed } from 'vue'

import type { VideoPlan } from '@/api/types'
import { VOICE_PRESETS } from '@/utils/audio'

const props = withDefaults(defineProps<{
  plan: VideoPlan
  disabled?: boolean
  previewBusy?: boolean
  audioPreview?: { slot: string; label: string; url: string } | null
}>(), { disabled: false, previewBusy: false, audioPreview: null })

const emit = defineEmits<{
  previewVoice: []
  cloneVoice: [ctx: { mode: 'preset'; replaceId?: string; name?: string }]
  removePreset: [id: string]
  closePreview: []
  /** 改了 plan 里的字段（音色 / 音色名）→ 父级可据此打脏标记 */
  changed: []
}>()

const clonePresets = computed(() => props.plan.audio?.voicePresets ?? [])
const voiceChoices = computed(() => [
  ...VOICE_PRESETS.map((v) => ({ label: v, value: v })),
  ...clonePresets.value.map((p) => ({ label: `🎙 ${p.name}（克隆）`, value: `clone:${p.id}` })),
])

/** 当前下拉选中的是不是一个克隆音色（决定「重录 / 改名 / 删除」是否可用） */
const selectedPreset = computed(() => {
  const v = props.plan.audio?.voice ?? ''
  if (!v.startsWith('clone:')) return undefined
  const id = v.slice('clone:'.length)
  return clonePresets.value.find((p) => p.id === id)
})

function rerecord(): void {
  const p = selectedPreset.value
  if (p) emit('cloneVoice', { mode: 'preset', replaceId: p.id, name: p.name })
}
function rename(): void {
  const p = selectedPreset.value
  if (!p) return
  const nm = window.prompt('新的音色名', p.name)
  if (nm === null) return
  const t = nm.trim()
  if (!t) return
  p.name = t          // draft 是响应式对象 → 自动进 dirty（与旧实现一致）
  emit('changed')
}
function del(): void {
  const p = selectedPreset.value
  if (p) emit('removePreset', p.id)
}
</script>

<template>
  <div class="voice-picker" data-testid="voice-picker">
    <span class="key font-mono">配音音色 voice</span>
    <NSelect
      v-model:value="props.plan.audio.voice"
      :options="voiceChoices"
      size="small"
      filterable
      tag
      :disabled="disabled"
      placeholder="中文女"
      style="min-width: 180px"
      @update:value="emit('changed')"
    />
    <NButton
      size="tiny"
      secondary
      :loading="previewBusy"
      :disabled="disabled"
      data-testid="btn-preview-voice"
      title="用当前音色念一句试听"
      @click="emit('previewVoice')"
    >
      音色试听
    </NButton>
    <NButton
      size="tiny"
      quaternary
      :disabled="disabled || !selectedPreset"
      data-testid="preset-rerecord-selected"
      title="重新录一段替换当前克隆音色"
      @click="rerecord"
    >
      重录
    </NButton>
    <NButton size="tiny" quaternary :disabled="disabled || !selectedPreset" @click="rename">
      改名
    </NButton>
    <NButton
      size="tiny"
      quaternary
      type="error"
      :disabled="disabled || !selectedPreset"
      data-testid="preset-delete-selected"
      @click="del"
    >
      删除
    </NButton>
    <NButton
      size="tiny"
      secondary
      :disabled="disabled"
      data-testid="btn-clone-voice"
      title="录一段声音或上传样本，处理成可复用的音色"
      @click="emit('cloneVoice', { mode: 'preset' })"
    >
      🎙 克隆音色
    </NButton>
    <p v-if="!selectedPreset" class="hint-line text-secondary">
      下拉里选一个「克隆」音色后，重录 / 改名 / 删除才可用
    </p>
    <div
      v-if="audioPreview && audioPreview.slot === 'voice'"
      class="voice-preview inline"
      data-testid="audio-preview-voice"
    >
      <span class="font-mono vp-label">🎙 试听 · {{ audioPreview.label }}</span>
      <audio :src="audioPreview.url" class="vp-audio" controls autoplay preload="auto" />
      <NButton size="tiny" quaternary @click="emit('closePreview')">关闭</NButton>
    </div>
  </div>
</template>

<style scoped>
.voice-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--wv-line);
}
.voice-picker .key {
  font-size: 12px;
  color: var(--wv-text-2);
}
.hint-line {
  flex-basis: 100%;
  margin: 2px 0 0;
  font-size: 11.5px;
}
.voice-preview.inline {
  flex-basis: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
}
.vp-audio {
  height: 30px;
  flex: 1;
  max-width: 320px;
}
.vp-label {
  font-size: 12px;
  color: var(--wv-text-2);
}
</style>
