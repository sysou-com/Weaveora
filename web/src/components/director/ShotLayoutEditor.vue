<script setup lang="ts">
/**
 * 逐镜「画面位置」编辑器（P5，2026-09-16）——写 `shots[].layout`。
 *
 * 为什么需要它（与方案级「位置预览」卡的区别）：
 * - 方案级 `referenceAssets[].region` 是**全片默认位置**（一个主体一个框），适合首尾一致的机位；
 * - 但同一主体在不同镜头里的位置/远近（w/h 表达大小与远近）本来就会变：进门在左、坐下在右、
 *   推近变大 —— 用一套全片默认会互相打架。所以这里提供**逐镜**覆盖，后端 `shots[].layout` 优先级最高。
 *
 * 数据契约（归一化 0–1，与后端 JobService.applyLayoutRegions 一致）：
 *   `shots[].layout = [{subject, x, y, w, h}]`（x/y=左上角，w/h=宽高；w/h 越大=越近越大）
 *
 * 交互：拖动色块移动、右下角小方块缩放；浅色虚线框 = 方案级默认（未覆盖），实心框 = 本镜已覆盖。
 */
import { computed } from 'vue'

export interface LayoutBox {
  subject: string
  x: number
  y: number
  w: number
  h: number
}

const props = withDefaults(
  defineProps<{
    /** 归一化 0–1 的框数组（= shots[].layout） */
    modelValue?: LayoutBox[] | null
    /** 本镜可能出镜的主体名（按方案主体顺序） */
    subjects: string[]
    /** 方案级默认框（归一化），用于「继承方案区域」与虚线提示 */
    defaults?: Record<string, LayoutBox>
    /** 画幅（CSS aspect-ratio 值，如 '16 / 9'） */
    aspect?: string
    disabled?: boolean
  }>(),
  { modelValue: null, defaults: () => ({}), aspect: '16 / 9', disabled: false },
)

const emit = defineEmits<{ 'update:modelValue': [LayoutBox[] | null] }>()

const COLORS = ['#8FB9B4', '#C8A25E', '#C45C4A', '#7AA87A']

const clamp01 = (v: number): number => Math.max(0, Math.min(1, v))
const round3 = (v: number): number => Math.round(clamp01(v) * 1000) / 1000

function validBox(b: unknown): b is LayoutBox {
  const o = b as LayoutBox | null
  if (!o || typeof o !== 'object') return false
  return (['x', 'y', 'w', 'h'] as const).every(
    (k) => typeof o[k] === 'number' && Number.isFinite(o[k]),
  ) && o.w > 0 && o.h > 0 && o.x >= 0 && o.y >= 0
}

/** 本镜显式覆盖的框（来自 modelValue） */
const explicit = computed<Record<string, LayoutBox>>(() => {
  const out: Record<string, LayoutBox> = {}
  for (const b of props.modelValue ?? []) {
    if (validBox(b) && (b.subject ?? '').trim()) {
      out[b.subject.trim()] = { subject: b.subject.trim(), x: b.x, y: b.y, w: b.w, h: b.h }
    }
  }
  return out
})

/** 主体名（去重、去空），保持方案顺序 */
const names = computed<string[]>(() => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of props.subjects ?? []) {
    const n = (raw ?? '').trim()
    if (!n || seen.has(n)) continue
    seen.add(n)
    out.push(n)
  }
  return out
})

/** 显示项：显式框优先，其次方案级默认（虚线），都没有则不画（用户可点「继承方案区域」补） */
const items = computed(() =>
  names.value.map((n, i) => {
    const own = explicit.value[n]
    const def = props.defaults?.[n]
    return {
      name: n,
      color: COLORS[i % COLORS.length],
      box: own ?? (validBox(def) ? def : null),
      own: !!own,
    }
  }),
)

const pct = (v: number): string => `${(v * 100).toFixed(1)}%`

// ---- 拖动 / 缩放 ----
let frameRect = { w: 1, h: 1 }
let drag: {
  subject: string
  mode: 'move' | 'resize'
  sx: number
  sy: number
  orig: LayoutBox
} | null = null

function writeOne(subject: string, box: LayoutBox): void {
  const next = { ...explicit.value, [subject]: { ...box, subject } }
  emit('update:modelValue', names.value.filter((n) => next[n]).map((n) => next[n]))
}

function onDown(e: PointerEvent, item: { name: string; box: LayoutBox | null }, mode: 'move' | 'resize'): void {
  if (props.disabled) return
  e.preventDefault()
  e.stopPropagation()
  const frame = (e.currentTarget as HTMLElement).closest('.sle-frame') as HTMLElement | null
  if (!frame) return
  const r = frame.getBoundingClientRect()
  frameRect = { w: r.width || 1, h: r.height || 1 }
  drag = {
    subject: item.name,
    mode,
    sx: e.clientX,
    sy: e.clientY,
    orig: item.box ?? props.defaults?.[item.name] ?? { subject: item.name, x: 0.35, y: 0.3, w: 0.3, h: 0.45 },
  }
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}

function onMove(e: PointerEvent): void {
  if (!drag) return
  const dx = (e.clientX - drag.sx) / frameRect.w
  const dy = (e.clientY - drag.sy) / frameRect.h
  const o = drag.orig
  if (drag.mode === 'move') {
    writeOne(drag.subject, {
      subject: drag.subject,
      x: round3(Math.min(Math.max(0, o.x + dx), 1 - o.w)),
      y: round3(Math.min(Math.max(0, o.y + dy), 1 - o.h)),
      w: round3(o.w),
      h: round3(o.h),
    })
  } else {
    writeOne(drag.subject, {
      subject: drag.subject,
      x: round3(o.x),
      y: round3(o.y),
      w: round3(Math.min(Math.max(0.05, o.w + dx), 1 - o.x)),
      h: round3(Math.min(Math.max(0.05, o.h + dy), 1 - o.y)),
    })
  }
}

function onUp(): void {
  drag = null
}

function nudge(subject: string, k: 'x' | 'y' | 'w' | 'h', delta: number): void {
  const base = explicit.value[subject] ?? props.defaults?.[subject] ?? { subject, x: 0.35, y: 0.3, w: 0.3, h: 0.45 }
  writeOne(subject, { ...base, subject, [k]: round3(base[k] + delta) })
}

/**
 * 直接填值（用户要求：x/y/w/h 要能直接输入）。
 *
 * 输入的是**百分比 0–100**（跟画面位置预览卡口径一致，用户看百分比最直观），
 * 存进去仍是归一化 0–1。空值/非数字 → 忽略（不写成 NaN，不把框弄丢）。
 */
function setVal(subject: string, k: 'x' | 'y' | 'w' | 'h', raw: string): void {
  const n = Number(String(raw ?? '').trim().replace('%', ''))
  if (!Number.isFinite(n)) return
  const base = explicit.value[subject] ?? props.defaults?.[subject] ?? { subject, x: 0.35, y: 0.3, w: 0.3, h: 0.45 }
  const next = { ...base, subject, [k]: round3(n / 100) }
  // 框必须留在画面内：右/下越界时把左上角回推，而不是默默裁掉尺寸
  if (k === 'w') next.x = round3(Math.min(next.x, 1 - next.w))
  if (k === 'h') next.y = round3(Math.min(next.y, 1 - next.h))
  writeOne(subject, next)
}

/** 输入框里显示的百分比（保留 1 位小数，去掉多余的 .0）。 */
function showPct(v: number | undefined): string {
  if (v === undefined) return ''
  return String(Math.round(v * 1000) / 10)
}

/** 继承方案区域：把方案级默认写成本镜显式框（之后就能单独微调本镜） */
function inherit(): void {
  const out: LayoutBox[] = []
  for (const n of names.value) {
    const d = props.defaults?.[n]
    if (validBox(d)) out.push({ subject: n, x: d.x, y: d.y, w: d.w, h: d.h })
  }
  if (!out.length) return
  emit('update:modelValue', out)
}

/** 自动均分（2 → 左右；3 → 三等分；4+ → 2×2 网格） */
function autoSplit(): void {
  const ns = names.value
  if (ns.length < 2) return
  const out: LayoutBox[] = []
  if (ns.length === 2) {
    out.push({ subject: ns[0], x: 0, y: 0, w: 0.5, h: 1 }, { subject: ns[1], x: 0.5, y: 0, w: 0.5, h: 1 })
  } else if (ns.length === 3) {
    out.push(
      { subject: ns[0], x: 0, y: 0, w: 0.34, h: 1 },
      { subject: ns[1], x: 0.34, y: 0, w: 0.33, h: 1 },
      { subject: ns[2], x: 0.67, y: 0, w: 0.33, h: 1 },
    )
  } else {
    ns.forEach((n, i) => {
      out.push({ subject: n, x: (i % 2) * 0.5, y: Math.floor(i / 2) * 0.5, w: 0.5, h: 0.5 })
    })
  }
  emit('update:modelValue', out)
}

/** 清除本镜位置：回到方案级默认（后端优先级自动降级） */
function clear(): void {
  emit('update:modelValue', null)
}
</script>

<template>
  <div class="sle" :class="{ off: disabled }" data-testid="shot-layout-editor">
    <div class="sle-head">
      <span class="fl font-mono">画面位置（本镜）</span>
      <span class="sle-hint text-secondary">
        {{ names.length ? '拖色块移动、右下角缩放；浅虚线框=方案默认' : '本镜没有绑定参考图的主体' }}
      </span>
      <span class="sle-actions">
        <button v-if="names.length > 1" type="button" class="link-btn" :disabled="disabled" @click="autoSplit">
          自动均分
        </button>
        <button
          v-if="names.length"
          type="button"
          class="link-btn"
          :disabled="disabled"
          title="把方案「位置预览」里的区域抄成本镜的显式位置"
          @click="inherit"
        >
          继承方案区域
        </button>
        <button
          v-if="Object.keys(explicit).length"
          type="button"
          class="link-btn warn"
          :disabled="disabled"
          title="删掉本镜的覆盖，回到方案级默认"
          @click="clear"
        >
          清除本镜位置
        </button>
      </span>
    </div>

    <div v-if="names.length" class="sle-body">
      <div class="sle-frame" :style="{ aspectRatio: aspect }">
        <div class="sle-third v1" /><div class="sle-third v2" />
        <div class="sle-third h1" /><div class="sle-third h2" />
        <div
          v-for="it in items.filter((x) => x.box)"
          :key="it.name"
          class="sle-box"
          :class="{ ghost: !it.own }"
          :data-testid="`sle-box-${it.name}`"
          :style="{
            left: pct(it.box!.x),
            top: pct(it.box!.y),
            width: pct(it.box!.w),
            height: pct(it.box!.h),
            borderColor: it.color,
            background: it.own ? it.color + '22' : 'transparent',
          }"
          @pointerdown="onDown($event, it, 'move')"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="onUp"
        >
          <span class="sle-label font-mono" :style="{ color: it.color }">
            {{ it.name }}<template v-if="!it.own"> · 方案默认</template>
          </span>
          <span v-if="!disabled" class="sle-resize" @pointerdown.stop="onDown($event, it, 'resize')" />
        </div>
      </div>

      <!-- 精确填写（可直接输入百分比；x/y=左上角，w/h=宽高，0–100） -->
      <div class="sle-table">
        <div v-for="it in items" :key="it.name" class="sle-row">
          <span class="sle-name" :style="{ color: it.color }">{{ it.name }}</span>
          <template v-for="k in (['x', 'y', 'w', 'h'] as const)" :key="k">
            <span class="sle-k font-mono">{{ k }}</span>
            <button type="button" class="sle-step" :disabled="disabled" @click="nudge(it.name, k, -0.01)">−</button>
            <input
              class="sle-input font-mono"
              type="text"
              inputmode="decimal"
              :disabled="disabled"
              :data-testid="`sle-${k}-${it.name}`"
              :value="showPct(it.box?.[k])"
              :placeholder="k"
              @change="setVal(it.name, k, ($event.target as HTMLInputElement).value)"
              @keyup.enter="setVal(it.name, k, ($event.target as HTMLInputElement).value)"
            />
            <button type="button" class="sle-step" :disabled="disabled" @click="nudge(it.name, k, 0.01)">+</button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sle {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sle.off {
  opacity: 0.7;
}
.sle-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.sle-head .fl {
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--wv-text-2);
}
.sle-hint {
  font-size: 11px;
}
.sle-actions {
  margin-left: auto;
  display: inline-flex;
  gap: 10px;
}
.link-btn {
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--wv-accent, #d0a24e);
  cursor: pointer;
}
.link-btn.warn {
  color: var(--wv-danger, #c45c4a);
}
.link-btn:disabled {
  color: var(--wv-text-4);
  cursor: not-allowed;
}
.sle-body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.sle-frame {
  position: relative;
  flex: 1 1 300px;
  min-width: 240px;
  max-width: 420px;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  overflow: hidden;
  touch-action: none;
}
.sle-third {
  position: absolute;
  background: var(--wv-line);
  opacity: 0.5;
}
.sle-third.v1,
.sle-third.v2 {
  top: 0;
  bottom: 0;
  width: 1px;
}
.sle-third.v1 {
  left: 33.333%;
}
.sle-third.v2 {
  left: 66.666%;
}
.sle-third.h1,
.sle-third.h2 {
  left: 0;
  right: 0;
  height: 1px;
}
.sle-third.h1 {
  top: 33.333%;
}
.sle-third.h2 {
  top: 66.666%;
}
.sle-box {
  position: absolute;
  border: 1.5px solid;
  border-radius: 6px;
  cursor: grab;
  min-width: 24px;
  min-height: 24px;
}
.sle-box.ghost {
  border-style: dashed;
  cursor: pointer;
}
.sle-box:active {
  cursor: grabbing;
}
.sle-label {
  position: absolute;
  left: 2px;
  top: 2px;
  font-size: 10px;
  line-height: 1.2;
  padding: 1px 4px;
  background: color-mix(in srgb, var(--wv-surface) 82%, transparent);
  border-radius: 3px;
  pointer-events: none;
  white-space: nowrap;
}
.sle-resize {
  position: absolute;
  right: -5px;
  bottom: -5px;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  background: var(--wv-surface);
  border: 1.5px solid currentColor;
  cursor: nwse-resize;
}
.sle-table {
  flex: 1 1 260px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sle-row {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
}
.sle-name {
  min-width: 56px;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sle-k {
  color: var(--wv-text-4);
  margin-left: 4px;
}
.sle-step {
  appearance: none;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface);
  color: var(--wv-text-2);
  width: 18px;
  height: 18px;
  line-height: 1;
  border-radius: 4px;
  cursor: pointer;
  padding: 0;
}
.sle-v {
  min-width: 26px;
  text-align: center;
  color: var(--wv-text-2);
}
.sle-input {
  width: 46px;
  min-width: 0;
  text-align: center;
  font-size: 11px;
  padding: 1px 2px;
  color: var(--wv-text-2);
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: 4px;
  appearance: none;
}
.sle-input:focus {
  outline: none;
  border-color: var(--wv-accent, #d0a24e);
}
</style>
