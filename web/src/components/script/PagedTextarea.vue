<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { NIcon } from 'naive-ui'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 分页文本域（用户要求 2026-09-17）：**不要内部滚动，改成一页一页翻**。
 *
 * - 新建剧本页：默认 15 行/页；详情抽屉：25 行/页（`pageRows`）。
 * - 超过一页的内容自动落到下一页；翻页/失焦/停止输入时把当前页写回整篇。
 * - 手机支持**左右滑动换页**（横向位移 ≥ 44px、且明显大于纵向位移时触发；正在选字时不换页）。
 *
 * 为什么按「估算显示行数」而不是直接量 DOM：
 * textarea 的换行不可读，用镜像 DOM 逐页测量成本高且会被 IME 组合态打断。
 * 这里用**最坏情况**（全角字符宽度）换算每行字数 → 中文内容恰好 15/25 行，
 * 英文/数字偏多的内容行数只会更少（不会溢出）。这是安全的近似。
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 每页显示行数 */
    pageRows?: number
    maxlength?: number
    placeholder?: string
    disabled?: boolean
    testId?: string
  }>(),
  { pageRows: 15, maxlength: 8000, placeholder: '', disabled: false, testId: '' },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const full = ref(props.modelValue ?? '')
const cpl = ref(48)
/** 当前页在 `full` 中的起始偏移（用偏移而不是页码：编辑后页数会变，偏移不会错位） */
const pageStart = ref(0)
/** 初始视图只取第 1 页（避免大字串在挂载前整篇渲染 + 字数重复统计） */
const initialPage = paginate(props.modelValue ?? '', props.pageRows, cpl.value)[0]
const view = ref((props.modelValue ?? '').slice(initialPage.start, initialPage.end))

const taRef = ref<HTMLTextAreaElement | null>(null)
let lastEmitted = props.modelValue ?? ''
let flushTimer: number | undefined

interface Page {
  start: number
  end: number
}

/** 把整篇切成「每页 ≤ rows 行」的字符区间 */
function paginate(text: string, rows: number, perLine: number): Page[] {
  const pages: Page[] = []
  const maxRows = Math.max(1, rows)
  const n = text.length
  let i = 0
  while (i < n) {
    let rowsUsed = 0
    let cursor = i
    let end = i
    while (cursor < n) {
      const nl = text.indexOf('\n', cursor)
      const lineEnd = nl === -1 ? n : nl
      const lineRows = Math.max(1, Math.ceil((lineEnd - cursor) / perLine))
      if (rowsUsed + lineRows > maxRows) {
        if (rowsUsed === 0) {
          // 单个逻辑行本身就超过一页 → 硬切（尽量在句读/空格处断）
          end = softBreak(text, cursor, Math.min(lineEnd, cursor + maxRows * perLine))
          rowsUsed = maxRows
        }
        break
      }
      rowsUsed += lineRows
      end = nl === -1 ? n : nl + 1
      cursor = nl === -1 ? n : nl + 1
      if (rowsUsed >= maxRows) break
    }
    if (end <= i) end = Math.min(n, i + maxRows * perLine)
    pages.push({ start: i, end })
    i = end
  }
  if (!pages.length) pages.push({ start: 0, end: 0 })
  return pages
}

/** 在 [from, to) 里找一个更自然的断点（句读/空格/英文词边界） */
function softBreak(text: string, from: number, to: number): number {
  const min = from + Math.floor((to - from) * 0.6)
  for (let k = to; k > min; k--) {
    const ch = text[k - 1]
    if (ch && '。！？；：，、\n ,.!?;:'.includes(ch)) return k
  }
  return to
}

const pages = computed(() => paginate(full.value, props.pageRows, cpl.value))
const totalPages = computed(() => pages.value.length)

function pageIndexAt(ps: Page[], offset: number): number {
  for (let i = ps.length - 1; i >= 0; i--) {
    if (ps[i].start <= offset) return i
  }
  return 0
}

const currentPage = computed(() => pageIndexAt(pages.value, pageStart.value))

/** 未落库的总字数（含当前页未写回的编辑） */
const liveTotal = computed(() => {
  const p = pages.value[currentPage.value]
  if (!p) return view.value.length
  return full.value.length - (p.end - p.start) + view.value.length
})

/** 把 textarea 内容写回整篇（不重排当前视图，避免打字时被截断） */
function flush(): void {
  const p = pages.value[currentPage.value]
  if (!p) return
  const before = full.value.slice(0, p.start)
  const after = full.value.slice(p.end)
  const next = before + view.value + after
  if (next === full.value) return
  full.value = next
  lastEmitted = next
  emit('update:modelValue', next)
}

function flushSoon(): void {
  if (flushTimer !== undefined) window.clearTimeout(flushTimer)
  flushTimer = window.setTimeout(() => {
    flushTimer = undefined
    flush()
  }, 200)
}

/** 跳到某一页：先写回，再按新分页重新取视图 */
function syncTo(offset: number): void {
  const ps = pages.value
  const idx = pageIndexAt(ps, Math.max(0, offset))
  pageStart.value = ps[idx].start
  view.value = full.value.slice(ps[idx].start, ps[idx].end)
}

function go(delta: number): void {
  flush()
  const ps = pages.value
  const idx = pageIndexAt(ps, pageStart.value)
  const target = idx + delta
  if (target < 0 || target >= ps.length) {
    // 到边界：只把当前页视图与分页对齐
    pageStart.value = ps[idx].start
    view.value = full.value.slice(ps[idx].start, ps[idx].end)
    return
  }
  syncTo(ps[target].start)
}

function onInput(e: Event): void {
  const el = e.target as HTMLTextAreaElement
  let v = el.value
  if (props.maxlength) {
    const p = pages.value[currentPage.value]
    const rest = p ? full.value.length - (p.end - p.start) : 0
    const room = Math.max(0, props.maxlength - rest)
    if (v.length > room) {
      v = v.slice(0, room)
      el.value = v
    }
  }
  view.value = v
  flushSoon()
}

// ---------- 每页字数换算（用最坏情况：全角字符宽度） ----------
function measure(): void {
  const el = taRef.value
  if (!el) return
  const cs = getComputedStyle(el)
  const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0)
  const width = el.clientWidth - padX - 2
  if (width <= 40) return
  const probe = document.createElement('span')
  probe.style.position = 'absolute'
  probe.style.visibility = 'hidden'
  probe.style.whiteSpace = 'pre'
  probe.style.fontFamily = cs.fontFamily
  probe.style.fontSize = cs.fontSize
  probe.style.fontWeight = cs.fontWeight
  probe.style.letterSpacing = cs.letterSpacing
  probe.textContent = '国'.repeat(20)
  document.body.appendChild(probe)
  const cjk = probe.getBoundingClientRect().width / 20 || 14
  document.body.removeChild(probe)
  const next = Math.max(12, Math.floor(width / cjk))
  if (next !== cpl.value) cpl.value = next
}

let ro: ResizeObserver | undefined

onMounted(() => {
  measure()
  syncTo(0)
  if (typeof ResizeObserver !== 'undefined' && taRef.value) {
    ro = new ResizeObserver(() => {
      const before = cpl.value
      measure()
      if (cpl.value !== before) {
        flush()
        syncTo(pageStart.value)
      }
    })
    ro.observe(taRef.value)
  }
})

onBeforeUnmount(() => {
  if (flushTimer !== undefined) window.clearTimeout(flushTimer)
  flush()
  ro?.disconnect()
})

// 外部改值（如异步加载到剧本、AI 应用 diff）→ 重置到第 1 页
watch(
  () => props.modelValue,
  (v) => {
    const next = v ?? ''
    if (next === lastEmitted) return
    full.value = next
    lastEmitted = next
    pageStart.value = 0
    const ps = pages.value
    view.value = next.slice(ps[0].start, ps[0].end)
    measure()
    syncTo(0)
  },
)

// ---------- 手机左右滑动换页 ----------
let touch: { x: number; y: number } | null = null

function onTouchStart(e: TouchEvent): void {
  const t = e.touches[0]
  touch = { x: t.clientX, y: t.clientY }
}

function onTouchEnd(e: TouchEvent): void {
  if (!touch) return
  const t = e.changedTouches[0]
  const dx = t.clientX - touch.x
  const dy = t.clientY - touch.y
  touch = null
  if (Math.abs(dx) < 44 || Math.abs(dx) < Math.abs(dy) * 1.4) return
  if (window.getSelection()?.toString()) return // 正在选字，别抢手势
  go(dx < 0 ? 1 : -1)
}

defineExpose({ flush, go })
</script>

<template>
  <div class="paged" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
    <textarea
      ref="taRef"
      class="ta"
      :value="view"
      :rows="pageRows"
      :disabled="disabled"
      :placeholder="placeholder"
      :data-testid="testId"
      spellcheck="false"
      @input="onInput"
      @blur="flush()"
    />

    <div class="bar">
      <span class="stat font-mono text-secondary">
        {{ liveTotal }} / {{ maxlength }} 字 · 第 {{ currentPage + 1 }} / {{ totalPages }} 页
        <span class="swipe-hint">· 左右滑动可换页</span>
      </span>
      <div class="nav">
        <button
          type="button"
          class="pg"
          :disabled="currentPage <= 0"
          title="上一页"
          @click="go(-1)"
        >
          <NIcon size="13"><ChevronLeft /></NIcon>
          上一页
        </button>
        <button
          type="button"
          class="pg"
          :disabled="currentPage >= totalPages - 1"
          title="下一页"
          @click="go(1)"
        >
          下一页
          <NIcon size="13"><ChevronRight /></NIcon>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.paged {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-s);
  background: var(--wv-surface-sunken);
  padding: 10px 12px 8px;
  transition: border-color var(--wv-dur) var(--wv-ease);
}
.paged:focus-within {
  border-color: var(--wv-accent);
}
.ta {
  width: 100%;
  box-sizing: border-box;
  border: none;
  outline: none;
  background: transparent;
  color: var(--wv-text);
  font-family: var(--wv-font-sans);
  font-size: 14px;
  line-height: 1.75;
  resize: none;
  /* 安全阀：万一估算偏差，宁可微滚动也不丢字 */
  overflow-y: auto;
  word-break: break-word;
  white-space: pre-wrap;
}
.ta::placeholder {
  color: var(--wv-text-4);
}
.ta:disabled {
  opacity: 0.6;
}
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  border-top: 1px solid var(--wv-divider);
  padding-top: 6px;
}
.stat {
  font-size: 11px;
  margin-right: auto;
}
.nav {
  display: flex;
  gap: 6px;
}
.pg {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  appearance: none;
  border: 1px solid var(--wv-line-strong);
  background: transparent;
  color: var(--wv-text-3);
  font-size: 11.5px;
  line-height: 1;
  padding: 5px 9px;
  border-radius: 6px;
  cursor: pointer;
}
.pg:hover:not(:disabled) {
  color: var(--wv-text);
  background: var(--wv-surface-raised);
}
.pg:disabled {
  opacity: 0.4;
  cursor: default;
}
/* 桌面（精确指针）隐藏滑动提示，移动端保留 */
@media (hover: hover) and (pointer: fine) {
  .swipe-hint {
    display: none;
  }
}
</style>
