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
    /** 音色选项（内置名 + 克隆音色 clone:<id>） */
    voices?: { label: string; value: string }[]
    /** P9：角色绑定的语速（subject → speed），用于在行内提示“实际会用多快” */
    speedHints?: Record<string, number>
    /** P10：各段配音的**实际时长**（毫秒），key = "镜号:段号" —— 用于对齐字幕与判定超长 */
    durations?: Record<string, number>
  }>(),
  { disabled: false, busy: false, subjects: () => [], voices: () => [], speedHints: () => ({}), durations: () => ({}) },
)

const emit = defineEmits<{
  'update:shot': [DirectorShot]
  /** 单条重新生成配音 */
  genLine: [shotNo: number, lineIndex: number]
  /** 试听某一条（本地导入的或已生成的） */
  previewLine: [shotNo: number, lineIndex: number]
  /** 导入自己配好的声音 */
  importLine: [shotNo: number, lineIndex: number, file: File, atSec: number, subject: string]
  /** P9：打开克隆配音（录音/上传 → 处理 → 设为音色或直接用这段） */
  cloneLine: [shotNo: number, lineIndex: number, atSec: number, subject: string]
  /** P10：一键调整本镜（延长镜头 / 允许溢出）—— 镜头时长与标记由父级回写 */
  extendShot: [shotNo: number, patch: { duration_sec?: number; allowNarrationOverflow?: boolean }]
  /** P11：AI 一键生成台词（本镜） */
  aiLines: [shotNo: number]
}>()

/** 配音大致语速（字/秒）——仅用于估算块宽与时长，不是真实合成结果 */
const CHARS_PER_SEC = 4.5
/** 英文（拉丁文本）语速：≈13 字符/秒。不分开算的话英文台词会被误判超长 */
const LATIN_CHARS_PER_SEC = 13.0

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
  const clean = (text ?? '').replace(/\s/g, '')
  // 拉丁字母占比过半 → 当英文估算（与后端 AiAudioService.charsPerSec 同口径）
  let letters = 0
  for (const ch of clean) {
    if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) letters++
  }
  const rate = clean.length > 0 && letters * 2 > clean.length ? LATIN_CHARS_PER_SEC : CHARS_PER_SEC
  return Math.max(0.8, clean.length / rate)
}

/** 该段配音的**实际时长**（秒）；未生成/未导入则为 null */
function lineDurSec(i: number): number | null {
  const ms = props.durations?.[`${props.shot.shot_no}:${i}`]
  return ms && ms > 0 ? ms / 1000 : null
}

/** 该段占用的镜内区间（秒）：**配音实际时长优先** > 手动 end_sec > 字数估算
 *
 * <p>P13：以前优先用手动 end_sec，而它常常是自动铺排时用**字数估算**写进去的
 * （例：显示 2.9s，实际音频只有 2.3s）→ 时间刻度不可信。改为：有实际时长就以它为准。
 */
function spanOf(l: NarrationLine, i: number): { start: number; len: number; exact: boolean } {
  const start = Math.max(0, l.at_sec ?? 0)
  const actual = lineDurSec(i)
  if (actual != null) {
    return { start, len: actual, exact: true }
  }
  const end = Number(l.end_sec ?? 0)
  if (end > start) return { start, len: end - start, exact: true }
  return { start, len: Math.min(estimateSec(l.text), Math.max(0.4, dur.value - start)), exact: false }
}

/** 把当前段的结束点按“实际音频时长”写回（手动微调，不再靠估算） */
function adoptActualEnd(i: number): void {
  const l = props.shot.narrations?.[i]
  const actual = lineDurSec(i)
  if (!l || actual == null) {
    message.warning('这一段还没有实际配音（先「重新生成这条」）')
    return
  }
  l.manual = true
  l.end_sec = Math.round(((l.at_sec ?? 0) + actual) * 10) / 10
  commit()
  message.success(`已按实际音频对齐：结束点 = ${l.end_sec}s`)
}

/** 本镜配音总时长（秒）：有实际时长用实际，否则用窗口/估算 */
const totalVoiceSec = computed(() =>
  lines.value.reduce((s, l, i) => {
    const actual = lineDurSec(i)
    if (actual != null) return s + actual
    const end = Number(l.end_sec ?? 0)
    if (end > (l.at_sec ?? 0)) return s + (end - (l.at_sec ?? 0))
    return s + estimateSec(l.text)
  }, 0),
)

const overflowSec = computed(() => totalVoiceSec.value - dur.value)
/** 超出镜头且未标记“允许溢出” → 需提醒 */
const overflowWarn = computed(() => overflowSec.value > 0.05 && props.shot.allowNarrationOverflow !== true)

/** 一键：把本镜语速提到刚好能装下（上限 2.0×） */
const neededSpeed = computed(() => {
  if (totalVoiceSec.value <= 0) return 1
  return Math.min(2, Math.max(0.5, Math.round((totalVoiceSec.value / dur.value) * 100) / 100))
})

function applySpeedToFit(): void {
  const spd = neededSpeed.value
  for (const l of lines.value) l.speed = spd
  commit()
}

/** 一键：把镜头延长到刚好装下（向上取 0.1s，留 0.2s 余量） */
function extendShotToFit(): void {
  const need = Math.round((totalVoiceSec.value + 0.2) * 10) / 10
  emit('extendShot', props.shot.shot_no, { duration_sec: need })
}

/** P10：恢复自动 —— 清掉手动标记，之后重新生成/自动铺排会重新接管它的位置 */
function restoreAuto(): void {
  if (!cur.value) return
  cur.value.manual = null
  commit()
}

/** 一键：允许溢出（不再报警；音频与字幕会自动跨到下一镜） */
function allowOverflow(): void {
  emit('extendShot', props.shot.shot_no, { allowNarrationOverflow: true })
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
    props.shot.narrations.push({ at_sec: 0, text: legacy, kind: 'narration', manual: true })
    props.shot.narration = ''
  }
  // 新段放在已有段之后，但不超出镜头
  const last = lines.value.length ? lines.value[lines.value.length - 1] : undefined
  const at = last ? Math.min(dur.value - 0.3, (last.at_sec ?? 0) + estimateSec(last.text) + 0.1) : 0
  // manual:true —— 用户手加的段落：P10 自动铺排不动它，AI「覆盖」也不会删它
  props.shot.narrations.push({ at_sec: Math.max(0, Number(at.toFixed(1))), text: '', kind: 'narration', manual: true })
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
/** 按下时的横坐标：位移 < 阈值 就当成“点击选中”，**不改时间**（修：点一下就动了起点 / 变 manual） */
const dragStartX = ref(0)
/** 本次拖拽是否真的移动过（纯点击不提交、不置脏） */
const dragMoved = ref(false)
const DRAG_THRESHOLD = 3

function onDragStart(i: number, ev: PointerEvent): void {
  if (props.disabled) return
  selected.value = i
  dragging.value = i
  dragMode.value = 'move'
  dragStartX.value = ev.clientX
  const el = ev.currentTarget as HTMLElement
  el.setPointerCapture?.(ev.pointerId)
}

/** 拖块右边缘 → 直接设结束点（精确设定，比估算准） */
function onResizeStart(i: number, ev: PointerEvent): void {
  if (props.disabled) return
  selected.value = i
  dragging.value = i
  dragMode.value = 'resize'
  dragStartX.value = ev.clientX
  ;(ev.currentTarget as HTMLElement).setPointerCapture?.(ev.pointerId)
  ev.stopPropagation()
}

const dragMode = ref<'move' | 'resize'>('move')

/** 把 clientX 换算成镜内秒 */
function secAtClientX(clientX: number): number {
  const track = trackEl.value
  if (!track) return 0
  const rect = track.getBoundingClientRect()
  if (rect.width <= 0) return 0
  const x = Math.min(Math.max(0, clientX - rect.left), rect.width)
  return (x / rect.width) * dur.value
}

function onDragMove(i: number, ev: PointerEvent): void {
  if (props.disabled || dragging.value !== i) return
  // 纯点击/微小抖动不算拖动：不改时间、不标记 manual（否则点一下就脏了方案）
  if (Math.abs(ev.clientX - dragStartX.value) < DRAG_THRESHOLD) return
  dragMoved.value = true
  const line = props.shot.narrations?.[i]
  if (!line) return
  const sec = secAtClientX(ev.clientX)
  line.manual = true          // P10：真的拖过 → 自动铺排不再动它
  if (dragMode.value === 'resize') {
    // 结束点：至少比起点大 0.3s，不超过镜头
    line.end_sec = Math.max((line.at_sec ?? 0) + 0.3, Math.min(dur.value, Math.round(sec * 10) / 10))
  } else {
    const span = spanOf(line, i)
    const start = Math.max(0, Math.min(dur.value - Math.min(span.len, dur.value - 0.3), sec))
    line.at_sec = Math.round(start * 10) / 10
    // 跟着平移时同步平移结束点，保持时长不变
    if (Number(line.end_sec ?? 0) > 0) {
      line.end_sec = Math.round((line.at_sec + span.len) * 10) / 10
    }
  }
  commit()
}

function onDragEnd(): void {
  // 只有真的拖动过才提交（否则“点一下就改时间/置脏” —— 实测痛点）
  if (dragging.value >= 0 && dragMoved.value) commit()
  dragging.value = -1
  dragMoved.value = false
}

/** 清除结束点 → 回到“配音自然长度” */
function clearEnd(): void {
  if (!cur.value) return
  cur.value.end_sec = null
  commit()
}

/** 把结束点设为“此刻 + 估算时长” */
function setEndFromEstimate(): void {
  if (!cur.value) return
  const est = estimateSec(cur.value.text)
  const start = cur.value.at_sec ?? 0
  cur.value.end_sec = Math.round(Math.min(dur.value, start + est) * 10) / 10
  commit()
}

const r1 = (n: number) => Math.round(n * 10) / 10

/**
 * 起点输入：**不交给组件的 min/max 去拒绝**（naive-ui 在输入中会直接丢弃越界值 → 输入框标红且写不进去）。
 * 这里自己兜：夹到 [0, dur]，并把结束点一起推好。
 */
function onStartInput(v: number | null): void {
  const l = cur.value
  if (!l) return
  l.manual = true             // P10：手动改过
  if (v == null || !Number.isFinite(v)) {
    l.at_sec = 0
  } else {
    l.at_sec = r1(Math.min(Math.max(0, v), Math.max(0, dur.value - 0.3)))
  }
  if (Number(l.end_sec ?? 0) > 0 && l.end_sec! < l.at_sec + 0.3) {
    l.end_sec = r1(Math.min(dur.value, l.at_sec + 0.3))
  }
  commit()
}

/**
 * 结束点输入：清空 = 恢复自然长度；填了则至少比起点晚 0.3s
 * （不够就把**起点往前挪**，而不是拒绝输入 —— 用户填的结束时间是他的本意）。
 */
function onEndInput(v: number | null): void {
  const l = cur.value
  if (!l) return
  l.manual = true             // P10：手动改过
  if (v == null || !Number.isFinite(v) || v <= 0) {
    l.end_sec = null
    commit()
    return
  }
  l.end_sec = r1(Math.min(Math.max(0.3, v), dur.value))
  if ((l.at_sec ?? 0) > l.end_sec - 0.3) {
    l.at_sec = r1(Math.max(0, l.end_sec - 0.3))
  }
  commit()
}

/** 语速输入：夹到 [0.5, 2.0]；清空 = 跟随角色绑定/默认 */
function onSpeedInput(v: number | null): void {
  const l = cur.value
  if (!l) return
  if (v == null || !Number.isFinite(v)) {
    l.speed = null
  } else {
    l.speed = Math.max(0.5, Math.min(2, Math.round(v * 20) / 20))
  }
  commit()
}

const subjectOptions = computed(() =>
  (props.subjects ?? []).filter(Boolean).map((s) => ({ label: s, value: s })),
)
const voiceOpts = computed(() => props.voices ?? [])

/** 时间刻度：每秒一条（镜头过长时每 2 秒） */
const ticks = computed(() => {
  const step = dur.value <= 6 ? 1 : 2
  const out: number[] = []
  for (let t = 0; t <= dur.value + 0.001; t += step) out.push(Number(t.toFixed(2)))
  return out
})

const cur = computed(() => (selected.value >= 0 ? props.shot.narrations?.[selected.value] : undefined))

/**
 * 该行**实际会用**的语速：行内 speed > 角色绑定 speed > 1.0
 * （与后端 AudioPlan.speedFor 同口径 —— 之前用户看不到角色的 2× 绑定，就疑惑“怎么这么快”）。
 */
function effectiveSpeed(l: NarrationLine): number {
  const own = Number(l.speed ?? 0)
  if (own >= 0.5 && own <= 2) return own
  const sub = (l.subject ?? '').trim()
  if (sub) {
    const b = props.speedHints?.[sub]
    if (typeof b === 'number' && b >= 0.5 && b <= 2) return b
  }
  return 1
}

function effectiveSpeedText(l: NarrationLine): string {
  const v = effectiveSpeed(l)
  return v === 1 ? '1.0' : `${v.toFixed(2)}×（跟随角色绑定）`
}

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
          left: (spanOf(l, i).start / dur) * 100 + '%',
          width: Math.max(3, (spanOf(l, i).len / dur) * 100) + '%',
        }"
        :title="`${l.at_sec ?? 0}s${Number(l.end_sec ?? 0) > 0 ? ' → ' + l.end_sec + 's（固定窗口）' : '（自然长度）'} · ${l.subject || '旁白'} · ${l.text || '(空)'}`"
        :data-testid="`narration-block-${shot.shot_no}-${i}`"
        @pointerdown="onDragStart(i, $event)"
        @pointermove="onDragMove(i, $event)"
        @pointerup="onDragEnd"
        @pointercancel="onDragEnd"
      >
        <span class="nt-block-tag font-mono">{{ (l.kind ?? 'narration') === 'dialogue' ? (l.subject || '台词') : '旁白' }}</span>
        <span class="nt-block-text">{{ l.text || '（空）' }}</span>
        <span class="nt-est font-mono">
          <template v-if="lineDurSec(i) != null">
            <span class="nt-real">{{ lineDurSec(i)!.toFixed(1) }}s</span>
          </template>
          <template v-else>{{ spanOf(l, i).len.toFixed(1) }}{{ spanOf(l, i).exact ? '' : '~' }}s</template>
        </span>
        <span
          v-if="effectiveSpeed(l) !== 1"
          class="nt-spd font-mono"
          :class="{ fast: effectiveSpeed(l) > 1.3, slow: effectiveSpeed(l) < 0.8 }"
          :title="`实际语速 ${effectiveSpeed(l)}×（来自角色音色绑定或本段设置）`"
        >{{ effectiveSpeed(l).toFixed(1) }}×</span>
        <!-- 拖右缘 = 设结束点 -->
        <span
          class="nt-resize"
          title="拖我设结束点（超出部分会被剪掉）"
          :data-testid="`narration-resize-${shot.shot_no}-${i}`"
          @pointerdown="onResizeStart(i, $event)"
        />
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
      <NButton
        size="tiny"
        secondary
        :disabled="disabled || busy"
        :data-testid="`narration-ai-${shot.shot_no}`"
        title="让 AI 分析本镜画面与人物，写 1~3 段台词（可按角色音色分说话人）"
        @click="emit('aiLines', shot.shot_no)"
      >
        ✨ AI 台词
      </NButton>
      <span class="text-secondary" style="font-size: 12px">
        拖块改起点（吸附 0.1s）、拖<span class="hl">右缘</span>设结束点；不设结束点就用配音自然长度，旁白短于镜头就留白
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
          :value="cur.at_sec ?? 0"
          size="small"
          :min="0"
          :max="dur"
          :step="0.1"
          :disabled="disabled"
          @update:value="onStartInput"
        />
      </label>
      <label class="nt-field narrow">
        <span class="fl">结束(s)</span>
        <NInputNumber
          :value="cur.end_sec ?? null"
          size="small"
          :min="0"
          :max="dur"
          :step="0.1"
          clearable
          :disabled="disabled"
          placeholder="自然长"
          :data-testid="`narration-end-${shot.shot_no}`"
          @update:value="onEndInput"
        />
      </label>
      <NButton size="tiny" quaternary :disabled="disabled || !(Number(cur.end_sec ?? 0) > 0)" @click="clearEnd">
        清结束点
      </NButton>
      <NButton size="tiny" quaternary :disabled="disabled" @click="setEndFromEstimate">
        按估算设结束
      </NButton>
      <NButton size="tiny" quaternary :disabled="disabled" :data-testid="`narration-end-actual-${shot.shot_no}`"
               title="用这段配音的**实际音频时长**写回结束点（比字数估算准）" @click="adoptActualEnd(selected)">
        按实际配时
      </NButton>
      <NButton
        v-if="cur.manual"
        size="tiny"
        quaternary
        type="warning"
        :disabled="disabled"
        :title="'本段位置/结束点被手动改过，自动铺排不再动它；点此交回自动'"
        @click="restoreAuto"
      >
        已手动 · 恢复自动
      </NButton>
      <label class="nt-field narrow">
        <span class="fl">语速</span>
        <NTooltip>
          <template #trigger>
            <NInputNumber
              :value="cur.speed ?? null"
              size="small"
              :min="0.5"
              :max="2.0"
              :step="0.05"
              clearable
              :disabled="disabled"
              :placeholder="effectiveSpeedText(cur)"
              @update:value="onSpeedInput"
            />
          </template>
          1.0 为自然语速；留空则跟随角色绑定/默认。当前实际会用 {{ effectiveSpeed(cur).toFixed(2) }}×
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
      <NButton
        size="tiny"
        quaternary
        :disabled="disabled || busy"
        :data-testid="`line-clone-${shot.shot_no}-${selected}`"
        title="录一段声音或上传样本：可以先处理成音色，也可以直接用这段当本行配音"
        @click="emit('cloneLine', shot.shot_no, selected, cur.at_sec ?? 0, cur.subject ?? '')"
      >
        克隆配音
      </NButton>
    </div>

    <p v-if="lines.length" class="nt-hint text-secondary">
      配音总长 {{ totalVoiceSec.toFixed(1) }}s / 镜头 {{ dur.toFixed(1) }}s
      <span v-if="totalVoiceSec <= dur + 0.05" class="nt-ok">✓ 装得下</span>
      （带 ~ 的是按字数估算；有实际时长的显示为白色数字）
    </p>

    <!-- P10：配音总长超出镜头时的提示 + 一键处理 -->
    <div v-if="overflowWarn" class="nt-overflow" :data-testid="`narration-overflow-${shot.shot_no}`">
      <span class="nt-ovf-text">
        ⚠️ 本镜配音 <b>{{ totalVoiceSec.toFixed(1) }}s</b> 超出镜头 <b>{{ dur.toFixed(1) }}s</b>
        共 <b>{{ overflowSec.toFixed(1) }}s</b>。
        <span class="text-secondary">不处理也可以：音频与字幕会自动溢到下一镜。</span>
      </span>
      <NButton
        size="tiny"
        secondary
        :disabled="disabled || neededSpeed >= 2"
        :title="neededSpeed >= 2 ? '需要的倍速超过 2.0× 上限，请改用延长镜头或精简文案' : '把本镜所有段的语速提到刚好装得下'"
        :data-testid="`narration-fit-speed-${shot.shot_no}`"
        @click="applySpeedToFit"
      >
        提语速到 {{ neededSpeed.toFixed(2) }}×
      </NButton>
      <NButton
        size="tiny"
        secondary
        :disabled="disabled"
        :data-testid="`narration-fit-extend-${shot.shot_no}`"
        @click="extendShotToFit"
      >
        延长镜头到 {{ (totalVoiceSec + 0.2).toFixed(1) }}s
      </NButton>
      <NButton
        size="tiny"
        quaternary
        :disabled="disabled"
        :data-testid="`narration-allow-overflow-${shot.shot_no}`"
        @click="allowOverflow"
      >
        允许溢出到下一镜
      </NButton>
    </div>
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
  padding: 2px 12px 2px 6px;
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
.nt-resize {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 8px;
  cursor: ew-resize;
  background: rgba(255, 255, 255, 0.16);
}
.nt-est {
  opacity: 0.7;
  font-size: 10px;
}
.nt-spd {
  flex: 0 0 auto;
  font-size: 10px;
  padding: 0 3px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.18);
}
.nt-spd.fast {
  background: rgba(255, 92, 92, 0.45);
  color: #fff;
}
.nt-spd.slow {
  background: rgba(96, 165, 250, 0.45);
  color: #fff;
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
.nt-real {
  color: #fff;
  font-weight: 600;
}
.nt-ok {
  color: #7bc47f;
}
.nt-overflow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(244, 180, 96, 0.12);
  border: 1px solid rgba(244, 180, 96, 0.45);
}
.nt-ovf-text {
  font-size: 11.5px;
  color: #f4b460;
  flex: 1 1 320px;
}
.nt-ovf-text b {
  color: #ffd08a;
}
.nt-sep {
  width: 1px;
  align-self: stretch;
  background: rgba(140, 160, 190, 0.25);
  margin: 0 2px;
}
.hl {
  color: #f4b460;
  font-weight: 600;
}
</style>
