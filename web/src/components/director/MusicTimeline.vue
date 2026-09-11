<script setup lang="ts">
/**
 * P8 全局配乐时间轴：多段配乐，块可拖拽移动 / 拖右边缘改结束时间。
 *
 * 每段独立控制：情绪 mood、音量 gain_db（相对基准，0dB=原始）、淡入/淡出、循环、是否被旁白压低。
 * 生成时按 mood 去重（同一情绪只生成一次曲子，多段引用同一产物），所以段数增加不涨 GPU 成本。
 */
import { Delete, Plus } from 'lucide-vue-next'
import { NButton, NIcon, NInputNumber, NSelect, NSlider, NSwitch } from 'naive-ui'
import { computed, ref } from 'vue'

import type { MusicCue, VideoPlan } from '@/api/types'

const props = withDefaults(
  defineProps<{
    plan: VideoPlan
    disabled?: boolean
    moods?: string[]
  }>(),
  { disabled: false, moods: () => [] },
)

const emit = defineEmits<{ 'update:plan': [VideoPlan] }>()

/** 子组件就地在 props.plan 上改动（与父级本模板其它字段的写法一致），emit 仅供父级需要时感知 */
function commit(): void {
  const m = props.plan.audio?.music
  if (Array.isArray(m)) m.sort((a, b) => a.start_sec - b.start_sec)
  emit('update:plan', props.plan)
}

const total = computed(() => {
  const d = Number(props.plan.duration_sec) || 0
  if (d > 0) return d
  return (props.plan.shots ?? []).reduce((s, sh) => s + (Number(sh.duration_sec) || 0), 0) || 24
})

const cues = computed<MusicCue[]>(() => {
  const m = props.plan.audio?.music
  return Array.isArray(m) ? [...m].sort((a, b) => a.start_sec - b.start_sec) : []
})

const selected = ref(-1)
const trackEl = ref<HTMLElement | null>(null)
const dragging = ref<{ i: number; mode: 'move' | 'resize'; grabSec: number } | null>(null)

function addCue(): void {
  if (!props.plan.audio) return
  if (!Array.isArray(props.plan.audio.music)) props.plan.audio.music = []
  const last = cues.value.length ? cues.value[cues.value.length - 1] : undefined
  const start = last ? Math.min(total.value - 1, last.end_sec) : 0
  props.plan.audio.music.push({
    id: `m${(props.plan.audio.music?.length ?? 0) + 1}`,
    start_sec: Number(start.toFixed(2)),
    end_sec: Number(Math.min(total.value, start + 8).toFixed(2)),
    mood: props.plan.audio.music_mood || '',
    gain_db: -10.5,
    fade_in_sec: 0.5,
    fade_out_sec: 1,
    loop: true,
    duck: true,
  })
  commit()
  selected.value = (props.plan.audio.music?.length ?? 1) - 1
}

function removeCue(i: number): void {
  props.plan.audio?.music?.splice(i, 1)
  commit()
  selected.value = -1
}

/** 把现有 music_mood 初始化成覆盖全片的一段 */
function initFromMood(): void {
  if (!props.plan.audio) return
  props.plan.audio.music = [
    {
      id: 'm1',
      start_sec: 0,
      end_sec: Number(total.value.toFixed(2)),
      mood: props.plan.audio.music_mood || '',
      gain_db: -10.5,
      fade_in_sec: 0,
      fade_out_sec: 0,
      loop: true,
      duck: true,
    },
  ]
  commit()
  selected.value = 0
}

function secAt(clientX: number): number {
  const track = trackEl.value
  if (!track) return 0
  const rect = track.getBoundingClientRect()
  if (rect.width <= 0) return 0
  const x = Math.min(Math.max(0, clientX - rect.left), rect.width)
  return (x / rect.width) * total.value
}

const r2 = (n: number) => Math.round(n * 100) / 100

function onDown(i: number, mode: 'move' | 'resize', ev: PointerEvent): void {
  if (props.disabled) return
  selected.value = i
  const cue = cues.value[i]
  dragging.value = { i, mode, grabSec: secAt(ev.clientX) - (mode === 'move' ? cue.start_sec : cue.end_sec) }
  ;(ev.currentTarget as HTMLElement).setPointerCapture?.(ev.pointerId)
  ev.stopPropagation()
}

function onMove(ev: PointerEvent): void {
  const d = dragging.value
  if (!d) return
  const m = props.plan.audio?.music
  const cue = m?.[d.i]
  if (!cue) return
  const sec = r2(secAt(ev.clientX) - d.grabSec)
  const MIN = 1
  if (d.mode === 'move') {
    const len = cue.end_sec - cue.start_sec
    const start = Math.max(0, Math.min(total.value - len, sec))
    cue.start_sec = r2(start)
    cue.end_sec = r2(start + len)
  } else {
    cue.end_sec = r2(Math.max(cue.start_sec + MIN, Math.min(total.value, sec)))
  }
  commit()
}

function onUp(): void {
  dragging.value = null
  commit()
}

const cur = computed(() => (selected.value >= 0 ? props.plan.audio?.music?.[selected.value] : undefined))

const moodOptions = computed(() => (props.moods ?? []).map((m) => ({ label: m, value: m })))

/** dB → 线性，给人直观参照（-10.5dB≈0.30，-16.5dB≈0.15） */
function linear(db: number | null | undefined): string {
  const v = Math.pow(10, (db ?? -10.5) / 20)
  return v.toFixed(2)
}

const ticks = computed(() => {
  const step = total.value <= 12 ? 1 : total.value <= 30 ? 2 : 5
  const out: number[] = []
  for (let t = 0; t <= total.value + 0.001; t += step) out.push(Number(t.toFixed(2)))
  return out
})
</script>

<template>
  <div class="mt" :class="{ locked: disabled }">
    <div
      ref="trackEl"
      class="mt-track"
      data-testid="music-track"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
    >
      <div v-for="t in ticks" :key="t" class="mt-tick" :style="{ left: (t / total) * 100 + '%' }">
        <span v-if="t > 0" class="mt-tick-label font-mono">{{ t }}s</span>
      </div>

      <div
        v-for="(c, i) in cues"
        :key="c.id ?? i"
        class="mt-block"
        :class="{ sel: selected === i, dragging: dragging?.i === i }"
        :style="{ left: (c.start_sec / total) * 100 + '%', width: Math.max(3, ((c.end_sec - c.start_sec) / total) * 100) + '%' }"
        :title="`${c.mood || '(默认情绪)'} · ${c.start_sec}s→${c.end_sec}s · ${c.gain_db ?? -10.5}dB`"
        :data-testid="`music-block-${i}`"
        @pointerdown="onDown(i, 'move', $event)"
      >
        <span class="mt-block-text">{{ c.mood || '默认情绪' }}</span>
        <span class="mt-block-gain font-mono">{{ (c.gain_db ?? -10.5).toFixed(1) }}dB</span>
        <span class="mt-handle" title="拖我改结束时间" @pointerdown="onDown(i, 'resize', $event)" />
      </div>

      <div v-if="!cues.length" class="mt-empty text-secondary">
        尚未配置配乐段落（将按上方 BGM 情绪铺满全片）
        <NButton size="tiny" secondary style="margin-left: 6px" :disabled="disabled" @click="initFromMood">
          从当前情绪生成一段
        </NButton>
      </div>
    </div>

    <div class="mt-bar">
      <NButton size="tiny" secondary :disabled="disabled" data-testid="music-add" @click="addCue">
        <template #icon><NIcon><Plus :size="12" /></NIcon></template>
        加一段配乐
      </NButton>
      <span class="text-secondary" style="font-size: 12px">
        拖块移动、拖右边缘改结束；同一情绪只生成一次曲子，多段复用（不额外花 GPU）
      </span>
    </div>

    <div v-if="cur" class="mt-panel">
      <label class="mt-field">
        <span class="fl">情绪 mood</span>
        <NSelect
          v-model:value="cur.mood"
          size="small"
          filterable
          tag
          :options="moodOptions"
          :disabled="disabled"
          placeholder="留空用全片默认情绪"
          @update:value="commit"
        />
      </label>
      <label class="mt-field grow">
        <span class="fl">
          音量 <span class="font-mono">{{ (cur.gain_db ?? -10.5).toFixed(1) }}dB</span>
          <span class="text-secondary">（线性 ≈{{ linear(cur.gain_db) }}；0dB=原始，-16.5dB≈默认的一半）</span>
        </span>
        <NSlider
          :value="cur.gain_db ?? -10.5"
          :min="-30"
          :max="0"
          :step="0.5"
          :disabled="disabled"
          :tooltip="false"
          @update:value="(v) => { cur!.gain_db = v as number; commit() }"
        />
      </label>
      <label class="mt-field narrow">
        <span class="fl">淡入(s)</span>
        <NInputNumber v-model:value="cur.fade_in_sec" size="small" :min="0" :max="10" :step="0.5" :disabled="disabled" @update:value="commit" />
      </label>
      <label class="mt-field narrow">
        <span class="fl">淡出(s)</span>
        <NInputNumber v-model:value="cur.fade_out_sec" size="small" :min="0" :max="10" :step="0.5" :disabled="disabled" @update:value="commit" />
      </label>
      <label class="mt-field narrow">
        <span class="fl">起点(s)</span>
        <NInputNumber v-model:value="cur.start_sec" size="small" :min="0" :max="total" :step="0.5" :disabled="disabled" @update:value="commit" />
      </label>
      <label class="mt-field narrow">
        <span class="fl">结束(s)</span>
        <NInputNumber v-model:value="cur.end_sec" size="small" :min="0" :max="total" :step="0.5" :disabled="disabled" @update:value="commit" />
      </label>
      <label class="mt-field switch">
        <span class="fl">循环填满</span>
        <NSwitch :value="cur.loop ?? true" size="small" :disabled="disabled" @update:value="(v) => { cur!.loop = v as boolean; commit() }" />
      </label>
      <label class="mt-field switch">
        <span class="fl">旁白时压低</span>
        <NSwitch :value="cur.duck ?? true" size="small" :disabled="disabled" @update:value="(v) => { cur!.duck = v as boolean; commit() }" />
      </label>
      <NButton size="tiny" quaternary type="error" :disabled="disabled" @click="removeCue(selected)">
        <template #icon><NIcon><Delete :size="12" /></NIcon></template>
        删除本段
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.mt {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mt-track {
  position: relative;
  height: 46px;
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(120, 140, 170, 0.14), rgba(120, 140, 170, 0.06));
  border: 1px solid rgba(140, 160, 190, 0.25);
  overflow: hidden;
}
.locked .mt-track {
  opacity: 0.6;
}
.mt-tick {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: rgba(140, 160, 190, 0.22);
}
.mt-tick-label {
  position: absolute;
  top: 2px;
  left: 3px;
  font-size: 9px;
  color: rgba(150, 165, 190, 0.75);
  white-space: nowrap;
}
.mt-block {
  position: absolute;
  top: 14px;
  bottom: 4px;
  min-width: 30px;
  border-radius: 5px;
  padding: 2px 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: grab;
  user-select: none;
  overflow: hidden;
  white-space: nowrap;
  font-size: 11px;
  background: rgba(159, 130, 235, 0.28);
  border: 1px solid rgba(159, 130, 235, 0.6);
}
.mt-block.dragging {
  cursor: grabbing;
  opacity: 0.85;
}
.mt-block.sel {
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.35) inset;
  border-color: #fff;
}
.mt-block-text {
  overflow: hidden;
  text-overflow: ellipsis;
}
.mt-block-gain {
  font-size: 10px;
  opacity: 0.85;
  flex: 0 0 auto;
}
.mt-handle {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 7px;
  cursor: ew-resize;
  background: rgba(255, 255, 255, 0.18);
}
.mt-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.mt-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mt-panel {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
  padding: 10px;
  border-radius: 6px;
  background: rgba(120, 140, 170, 0.08);
  border: 1px solid rgba(140, 160, 190, 0.18);
}
.mt-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 120px;
}
.mt-field.grow {
  flex: 1 1 260px;
}
.mt-field.narrow {
  min-width: 90px;
  max-width: 108px;
}
.mt-field.switch {
  flex-direction: row;
  align-items: center;
  gap: 6px;
  min-width: auto;
}
.mt-field .fl {
  font-size: 11px;
  color: rgba(160, 175, 200, 0.85);
}
</style>
