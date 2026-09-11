<script setup lang="ts">
/**
 * P8 镜内语音时间轴：一镜多段旁白/台词，块可横向拖拽定位。
 *
 * 数据：优先读写 shot.narrations；若不存在但 shot.narration 有内容，显示为「单段」并提供
 * 「转为多段」把它迁进来（保留旧字段不再写，向后兼容）。
 */
import { Delete, Plus } from 'lucide-vue-next'
import { NButton, NIcon, NInput, NInputNumber, NSelect, NTooltip } from 'naive-ui'
import { computed, ref } from 'vue'

import type { DirectorShot, NarrationLine } from '@/api/types'

const props = withDefaults(
  defineProps<{
    shot: DirectorShot
    disabled?: boolean
    /** 该镜有语音任务在跑（禁用按钮） */
    busy?: boolean
    /** 角色建议列表（来自 voiceBindings + referenceAssets） */
    subjects?: string[]
    /** 音色预设 */
    voices?: string[]
  }>(),
  { disabled: false, busy: false, subjects: () => [], voices: () => [] },
)

const emit = defineEmits<{
  'update:shot': [DirectorShot]
  /** 单条重新生成配音 */
  genLine: [shotNo: number, lineIndex: number]
  /** 试听某一条（本地导入的或已生成的） */
  previewLine: [shotNo: number, lineIndex: number]
  /** 导入自己配好的声音 */
  importLine: [shotNo: number, lineIndex: number, file: File, atSec: number, subject: string]
}>()

/** 中文配音大致语速（字/秒）——仅用于估算块宽与时长，不是真实合成结果 */
const CHARS_PER_SEC = 4.5

const lines = computed<NarrationLine[]>(() => {
  const ns = props.shot.narrations
  if (Array.isArray(ns) && ns.length) {
    return [...ns].sort((a, b) => (a.at_sec ?? 0) - (b.at_sec ?? 0))
  }
  return []
})

/** 旧单段旁白（未迁移时显示一条只读提示） */
const legacyNarration = computed(() => {
  if (lines.value.length) return ''
  return (props.shot.narration ?? '').trim()
})

const dur = computed(() => Math.max(1, Number(props.shot.duration_sec) || 3))

const selected = ref(-1)

function estimateSec(text: string): number {
  const n = (text ?? '').replace(/\s/g, '').length
  return Math.max(0.8, n / CHARS_PER_SEC)
}

/** 按 at_sec 排序后的“显示索引”与真实数组索引一致（lines 已排序并写回前先规整） */
function sortInPlace(): void {
  const ns = props.shot.narrations
  if (Array.isArray(ns)) ns.sort((a, b) => (a.at_sec ?? 0) - (b.at_sec ?? 0))
}

function commit(): void {
  sortInPlace()
  emit('update:shot', props.shot)
}

function addLine(): void {
  if (!Array.isArray(props.shot.narrations)) props.shot.narrations = []
  // 陷阱：后端优先读 narrations，一旦它非空、shot.narration 就被忽略。
  // 所以先把还未迁移的旧单段旁白作为第一段（避免内容静默丢失）。
  const legacy = (props.shot.narration ?? '').trim()
  if (legacy && props.shot.narrations.length === 0) {
    props.shot.narrations.push({ at_sec: 0, text: legacy, kind: 'narration' })
    props.shot.narration = ''
  }
  // 新段放在已有段之后，但不超出镜头
  const last = lines.value.length ? lines.value[lines.value.length - 1] : undefined
  const at = last ? Math.min(dur.value - 0.3, (last.at_sec ?? 0) + estimateSec(last.text) + 0.1) : 0
  props.shot.narrations.push({ at_sec: Math.max(0, Number(at.toFixed(1))), text: '', kind: 'narration' })
  commit()
  selected.value = props.shot.narrations.length - 1
}

function removeLine(i: number): void {
  props.shot.narrations?.splice(i, 1)
  commit()
  selected.value = -1
}

/** 把旧的单段 narration 迁成 narrations[0] */
function migrateLegacy(): void {
  const t = legacyNarration.value
  if (!t) return
  props.shot.narrations = [{ at_sec: 0, text: t, kind: 'narration' }]
  props.shot.narration = ''
  commit()
  selected.value = 0
}

/** 块拖拽：pointerdown 后按像素位移换算秒（时间轴宽度 = 容器宽） */
const trackEl = ref<HTMLElement | null>(null)
const dragging = ref(-1)

function onDragStart(i: number, ev: PointerEvent): void {
  if (props.disabled) return
  selected.value = i
  dragging.value = i
  const el = ev.currentTarget as HTMLElement
  el.setPointerCapture?.(ev.pointerId)
}

function onDragMove(i: number, ev: PointerEvent): void {
  if (props.disabled || dragging.value !== i) return
  const track = trackEl.value
  const line = props.shot.narrations?.[i]
  if (!track || !line) return
  const rect = track.getBoundingClientRect()
  if (rect.width <= 0) return
  const x = Math.min(Math.max(0, ev.clientX - rect.left), rect.width)
  const sec = (x / rect.width) * dur.value
  // 吸附到 0.1s，且不允许超出镜头（至少留 0.3s）
  line.at_sec = Math.max(0, Math.min(dur.value - 0.3, Math.round(sec * 10) / 10))
  commit()
}

function onDragEnd(): void {
  dragging.value = -1
  commit()
}

const subjectOptions = computed(() =>
  (props.subjects ?? []).filter(Boolean).map((s) => ({ label: s, value: s })),
)
const voiceOpts = computed(() => (props.voices ?? []).map((v) => ({ label: v, value: v })))

/** 时间刻度：每秒一条（镜头过长时每 2 秒） */
const ticks = computed(() => {
  const step = dur.value <= 6 ? 1 : 2
  const out: number[] = []
  for (let t = 0; t <= dur.value + 0.001; t += step) out.push(Number(t.toFixed(2)))
  return out
})

const cur = computed(() => (selected.value >= 0 ? props.shot.narrations?.[selected.value] : undefined))

/** 每条语音的隐藏 file input（导入配音） */
function pickVoiceFile(i: number): void {
  if (props.disabled) return
  const el = document.createElement('input')
  el.type = 'file'
  el.accept = 'audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac'
  el.onchange = () => {
    const f = el.files?.[0]
    const l = props.shot.narrations?.[i]
    if (!f || !l) return
    emit('importLine', props.shot.shot_no, i, f, l.at_sec ?? 0, l.subject ?? '')
  }
  el.click()
}


</script>

<template>
  <div class="nt" :class="{ locked: disabled }">
    <!-- 时间轴 -->
    <div ref="trackEl" class="nt-track" :data-testid="`narration-track-${shot.shot_no}`">
      <div v-for="t in ticks" :key="t" class="nt-tick" :style="{ left: (t / dur) * 100 + '%' }">
        <span v-if="t > 0" class="nt-tick-label font-mono">{{ t }}s</span>
      </div>

      <div
        v-for="(l, i) in lines"
        :key="i"
        class="nt-block"
        :class="{ sel: selected === i, narration: (l.kind ?? 'narration') === 'narration', dialogue: l.kind === 'dialogue', dragging: dragging === i }"
        :style="{
          left: ((l.at_sec ?? 0) / dur) * 100 + '%',
          width: Math.max(4, (estimateSec(l.text) / dur) * 100) + '%',
        }"
        :title="`${l.at_sec ?? 0}s · ${l.subject || '旁白'} · ${l.text || '(空)'}`"
        :data-testid="`narration-block-${shot.shot_no}-${i}`"
        @pointerdown="onDragStart(i, $event)"
        @pointermove="onDragMove(i, $event)"
        @pointerup="onDragEnd"
        @pointercancel="onDragEnd"
      >
        <span class="nt-block-tag font-mono">{{ (l.kind ?? 'narration') === 'dialogue' ? (l.subject || '台词') : '旁白' }}</span>
        <span class="nt-block-text">{{ l.text || '（空）' }}</span>
      </div>

      <div v-if="!lines.length" class="nt-empty text-secondary">
        本镜暂无语音段
        <NButton v-if="legacyNarration" size="tiny" secondary style="margin-left: 6px" :disabled="disabled" @click="migrateLegacy">
          把现有旁白转为多段
        </NButton>
      </div>
    </div>

    <!-- 操作条 -->
    <div class="nt-bar">
      <NButton size="tiny" secondary :disabled="disabled" :data-testid="`narration-add-${shot.shot_no}`" @click="addLine">
        <template #icon><NIcon><Plus :size="12" /></NIcon></template>
        加一段
      </NButton>
      <span class="text-secondary" style="font-size: 12px">
        拖块改起点（吸附 0.1s）；旁白短于镜头就留白，不会为填满而慢放
      </span>
    </div>

    <!-- 选中段的编辑面板 -->
    <div v-if="cur" class="nt-panel">
      <label class="nt-field wide">
        <span class="fl">文本</span>
        <NInput
          v-model:value="cur.text"
          size="small"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 3 }"
          :disabled="disabled"
          placeholder="这一段要说什么"
          :data-testid="`narration-text-${shot.shot_no}`"
          @update:value="commit"
        />
      </label>
      <label class="nt-field">
        <span class="fl">类型</span>
        <NSelect
          v-model:value="cur.kind"
          size="small"
          :disabled="disabled"
          :options="[
            { label: '旁白 narration', value: 'narration' },
            { label: '台词 dialogue', value: 'dialogue' },
          ]"
          @update:value="commit"
        />
      </label>
      <label class="nt-field">
        <span class="fl">说话人</span>
        <NSelect
          v-model:value="cur.subject"
          size="small"
          filterable
          tag
          clearable
          :options="subjectOptions"
          :disabled="disabled"
          placeholder="留空=旁白"
          @update:value="commit"
        />
      </label>
      <label class="nt-field">
        <span class="fl">音色覆盖</span>
        <NSelect
          v-model:value="cur.voice"
          size="small"
          filterable
          tag
          clearable
          :options="voiceOpts"
          :disabled="disabled"
          placeholder="跟随角色绑定"
          @update:value="commit"
        />
      </label>
      <label class="nt-field narrow">
        <span class="fl">起点(s)</span>
        <NInputNumber
          v-model:value="cur.at_sec"
          size="small"
          :min="0"
          :max="dur - 0.3"
          :step="0.1"
          :disabled="disabled"
          @update:value="commit"
        />
      </label>
      <label class="nt-field narrow">
        <span class="fl">语速</span>
        <NTooltip>
          <template #trigger>
            <NInputNumber
              v-model:value="cur.speed"
              size="small"
              :min="0.5"
              :max="2.0"
              :step="0.05"
              :disabled="disabled"
              placeholder="1.0"
              @update:value="commit"
            />
          </template>
          1.0 为自然语速。留空则跟随角色绑定/默认
        </NTooltip>
      </label>
      <NButton size="tiny" quaternary type="error" :disabled="disabled" @click="removeLine(selected)">
        <template #icon><NIcon><Delete :size="12" /></NIcon></template>
        删除本段
      </NButton>
      <span class="nt-sep" />
      <NButton
        size="tiny"
        secondary
        :disabled="disabled || busy || !cur.text.trim()"
        :data-testid="`line-gen-${shot.shot_no}-${selected}`"
        title="只重新生成这一条配音（其余段落不受影响）"
        @click="emit('genLine', shot.shot_no, selected)"
      >
        重新生成这条
      </NButton>
      <NButton
        size="tiny"
        secondary
        :disabled="disabled || busy"
        :data-testid="`line-import-${shot.shot_no}-${selected}`"
        title="上传你自己配好的声音（mp3/wav/m4a/aac/ogg/flac）"
        @click="pickVoiceFile(selected)"
      >
        导入配音
      </NButton>
      <NButton
        size="tiny"
        quaternary
        :disabled="disabled || busy"
        :data-testid="`line-preview-${shot.shot_no}-${selected}`"
        title="试听这一条（需已生成或已导入）"
        @click="emit('previewLine', shot.shot_no, selected)"
      >
        试听这条
      </NButton>
    </div>

    <p v-if="lines.length" class="nt-hint text-secondary">
      预计语音总长 {{ lines.reduce((s, l) => s + estimateSec(l.text), 0).toFixed(1) }}s /
      镜头 {{ dur.toFixed(1) }}s（估算，实际由 TTS 决定）
    </p>
  </div>
</template>

<style scoped>
.nt {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.nt-track {
  position: relative;
  height: 46px;
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(120, 140, 170, 0.14), rgba(120, 140, 170, 0.06));
  border: 1px solid rgba(140, 160, 190, 0.25);
  overflow: hidden;
}
.locked .nt-track {
  opacity: 0.6;
}
.nt-tick {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: rgba(140, 160, 190, 0.22);
}
.nt-tick-label {
  position: absolute;
  top: 2px;
  left: 3px;
  font-size: 9px;
  color: rgba(150, 165, 190, 0.75);
  white-space: nowrap;
}
.nt-block {
  position: absolute;
  top: 14px;
  bottom: 4px;
  min-width: 26px;
  border-radius: 5px;
  padding: 2px 6px;
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: grab;
  user-select: none;
  overflow: hidden;
  white-space: nowrap;
  font-size: 11px;
  border: 1px solid transparent;
}
.nt-block.dragging {
  cursor: grabbing;
  opacity: 0.85;
}
.nt-block.narration {
  background: rgba(96, 165, 250, 0.26);
  border-color: rgba(96, 165, 250, 0.55);
}
.nt-block.dialogue {
  background: rgba(244, 180, 96, 0.26);
  border-color: rgba(244, 180, 96, 0.6);
}
.nt-block.sel {
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.35) inset;
  border-color: #fff;
}
.nt-block-tag {
  font-size: 10px;
  opacity: 0.85;
  flex: 0 0 auto;
}
.nt-block-text {
  overflow: hidden;
  text-overflow: ellipsis;
}
.nt-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.nt-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.nt-panel {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
  padding: 10px;
  border-radius: 6px;
  background: rgba(120, 140, 170, 0.08);
  border: 1px solid rgba(140, 160, 190, 0.18);
}
.nt-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 130px;
}
.nt-field.wide {
  flex: 1 1 260px;
}
.nt-field.narrow {
  min-width: 92px;
  max-width: 110px;
}
.nt-field .fl {
  font-size: 11px;
  color: rgba(160, 175, 200, 0.85);
}
.nt-hint {
  font-size: 11px;
  margin: 0;
}
.nt-sep {
  width: 1px;
  align-self: stretch;
  background: rgba(140, 160, 190, 0.25);
  margin: 0 2px;
}
</style>
