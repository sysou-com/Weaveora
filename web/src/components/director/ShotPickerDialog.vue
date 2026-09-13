<script setup lang="ts">
/**
 * P12 分镜勾选弹窗：分镜多于 3 镜时，生成按钮先弹这个，选「部分镜」还是「全部」。
 *
 * 每行两个勾选：
 *  1. **处理** —— 本批要不要生成这一镜
 *  2. **封版** —— 该镜资源已满足要求；之后批量生成自动跳过（后端 JobService 也会兜底跳过）
 *
 * 行标签带「版本号」以便区分同名镜（vN = 该镜最新资源来自哪一版确认稿；无资源显示 —）。
 */
import { NButton, NCheckbox, NModal, useMessage } from 'naive-ui'
import { computed, ref, watch } from 'vue'

export interface PickerShot {
  shotNo: number
  /** 该镜资源来自的版本号（null = 还没生成过资源） */
  revNo?: number | null
  /** 该镜的关键帧/配音资源是否来自旧版本（已过期） */
  stale?: boolean
  /** 是否有语音段（用于展示「N 段」） */
  lineCount?: number
  /** 计数的单位（配音=段语音；关键帧/motion=张已出）——由当前操作类型决定 */
  unit?: string
  /** 是否满足本次操作的前置条件（undefined = 不判断，保持旧行为） */
  eligible?: boolean
  /** 行内补充说明，例如「可生成 · 2 段语音」「不可：缺画面(motion/关键帧)、缺配音」 */
  note?: string
}

const props = withDefaults(
  defineProps<{
    show: boolean
    /** 弹窗标题（例如「生成关键帧(still)」） */
    title?: string
    /** 顶部说明（不传用通用文案；对口型这类有前置条件的操作应传入） */
    hint?: string
    shots: PickerShot[]
    /** 已封版镜号 */
    locked: number[]
    busy?: boolean
  }>(),
  { title: '选择分镜', hint: '', busy: false },
)

const emit = defineEmits<{
  'update:show': [v: boolean]
  /** 确认：要处理的镜号 + 封版变更（需要落库的） */
  confirm: [payload: { shotNos: number[]; lock: number[]; unlock: number[] }]
}>()

const message = useMessage()

/** 本批要处理的镜（默认：全部未封版镜） */
const picked = ref<number[]>([])
/** 封版勾选状态（默认 = 服务端现状，可在此直接改） */
const lockedSet = ref<number[]>([])

function reset(): void {
  lockedSet.value = [...props.locked]
  const unlocked = props.shots.filter((s) => !props.locked.includes(s.shotNo))
  // 未封版的默认全勾；若全部都封版了，则默认勾「全部」（否则用户点确认什么也不会发生）
  const pool = unlocked.length ? unlocked : props.shots
  // 有前置条件信息时（对口型），默认只勾「可生成」的镜，避免点了确认却全被跳过
  const usable = pool.filter((s) => s.eligible !== false)
  picked.value = (usable.length ? usable : pool).map((s) => s.shotNo)
}

watch(
  () => props.show,
  (v) => {
    if (v) reset()
  },
  { immediate: true },
)
watch(() => props.locked, () => { if (props.show) lockedSet.value = [...props.locked] })

const lockedCount = computed(() => lockedSet.value.length)

function togglePick(no: number, on: boolean): void {
  picked.value = on ? [...new Set([...picked.value, no])] : picked.value.filter((x) => x !== no)
}
function toggleLock(no: number, on: boolean): void {
  lockedSet.value = on ? [...new Set([...lockedSet.value, no])] : lockedSet.value.filter((x) => x !== no)
  // 封版即“不动它”：顺手取消勾选，少一次误操作（用户仍可再勾回来强制生成）
  if (on) picked.value = picked.value.filter((x) => x !== no)
}
function pickAll(): void {
  picked.value = props.shots.map((s) => s.shotNo)
}
function pickNone(): void {
  picked.value = []
}
function pickUnlocked(): void {
  picked.value = props.shots.filter((s) => !lockedSet.value.includes(s.shotNo)).map((s) => s.shotNo)
}
/** 仅当列表带前置条件信息（eligible）时才显示：只勾「现在真能跑」的镜 */
const hasEligibility = computed(() => props.shots.some((s) => s.eligible !== undefined))
function pickEligible(): void {
  picked.value = props.shots
    .filter((s) => s.eligible !== false && !lockedSet.value.includes(s.shotNo))
    .map((s) => s.shotNo)
}

function confirm(): void {
  let sel = [...picked.value].sort((a, b) => a - b)
  if (!sel.length) {
    message.warning('至少勾选一个要处理的分镜（或取消）')
    return
  }
  // 前置条件不满足的镜：本地就剔除并说清楚原因，不让后端静默跳过
  const bad = props.shots.filter((s) => sel.includes(s.shotNo) && s.eligible === false)
  if (bad.length) {
    const why = bad
      .map((s) => `第${s.shotNo}镜${s.note ? `（${s.note.replace(/^不可[：:]?/, '')}）` : ''}`)
      .join('、')
    sel = sel.filter((no) => !bad.some((s) => s.shotNo === no))
    if (!sel.length) {
      message.error(`所选分镜都不满足前置条件：${why}`)
      return
    }
    message.warning(`已跳过不满足前置条件的分镜：${why}`)
  }
  const lock = lockedSet.value.filter((x) => !props.locked.includes(x))
  const unlock = props.locked.filter((x) => !lockedSet.value.includes(x))
  emit('confirm', { shotNos: sel, lock, unlock })
  emit('update:show', false)
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="title"
    style="max-width: 560px"
    data-testid="shot-picker"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="sp-hint">
      {{ hint || '勾选要处理的分镜；右侧「封版」表示该镜资源已达标，以后批量生成会自动跳过（单镜重做不受限）。' }}
    </p>

    <div class="sp-tools">
      <NButton size="tiny" secondary data-testid="sp-pick-all" @click="pickAll">全选</NButton>
      <NButton size="tiny" secondary data-testid="sp-pick-none" @click="pickNone">全不选</NButton>
      <NButton size="tiny" secondary data-testid="sp-pick-unlocked" @click="pickUnlocked">
        只选未封版
      </NButton>
      <NButton
        v-if="hasEligibility"
        size="tiny"
        secondary
        data-testid="sp-pick-eligible"
        @click="pickEligible"
      >
        只选可生成
      </NButton>
      <span class="sp-count font-mono">
        已选 {{ picked.length }} / {{ shots.length }} 镜 · 封版 {{ lockedCount }} 镜
      </span>
    </div>

    <div class="sp-list">
      <div
        v-for="s in shots"
        :key="s.shotNo"
        :class="['sp-row', { locked: lockedSet.includes(s.shotNo), bad: s.eligible === false }]"
        :data-testid="`sp-row-${s.shotNo}`"
      >
        <NCheckbox
          :checked="picked.includes(s.shotNo)"
          @update:checked="(v: boolean) => togglePick(s.shotNo, v)"
        >
          <span class="sp-no">第 {{ s.shotNo }} 镜</span>
          <span class="sp-rev font-mono" :class="{ stale: s.stale }">
            {{ s.revNo ? `v${s.revNo}${s.stale ? '·旧' : ''}` : 'v—' }}
          </span>
          <span v-if="s.lineCount" class="sp-extra font-mono">{{ s.lineCount }} {{ s.unit ?? '段语音' }}</span>
          <span v-if="s.note" class="sp-note" :class="{ bad: s.eligible === false }">
            {{ s.note }}
          </span>
        </NCheckbox>
        <NCheckbox
          :checked="lockedSet.includes(s.shotNo)"
          :data-testid="`sp-lock-${s.shotNo}`"
          @update:checked="(v: boolean) => toggleLock(s.shotNo, v)"
        >
          <span class="sp-lock-label">封版</span>
        </NCheckbox>
      </div>
      <p v-if="!shots.length" class="sp-hint">方案里还没有分镜。</p>
    </div>

    <template #footer>
      <div class="sp-actions">
        <NButton size="small" :disabled="busy" @click="emit('update:show', false)">取消</NButton>
        <NButton
          size="small"
          type="primary"
          :loading="busy"
          data-testid="sp-confirm"
          @click="confirm"
        >
          按勾选生成（{{ picked.length }} 镜）
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.sp-hint {
  margin: 0 0 10px;
  font-size: 12.5px;
  color: var(--wv-text-2);
  line-height: 1.7;
}
.sp-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--wv-line);
}
.sp-count {
  font-size: 11px;
  color: var(--wv-text-4);
  margin-left: auto;
}
.sp-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  max-height: 46vh;
  overflow: auto;
}
.sp-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 10px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface-sunken);
}
.sp-row.locked {
  border-color: color-mix(in srgb, var(--wv-warn, #d0a24e) 45%, var(--wv-line));
}
.sp-row.bad {
  border-color: color-mix(in srgb, var(--wv-danger, #d9534f) 35%, var(--wv-line));
  opacity: 0.82;
}
.sp-note {
  margin-left: 8px;
  font-size: 11px;
  color: var(--wv-text-4);
}
.sp-note.bad {
  color: var(--wv-danger, #d9534f);
}
.sp-no {
  font-size: 13px;
}
.sp-rev {
  margin-left: 8px;
  font-size: 11px;
  color: var(--wv-text-4);
}
.sp-rev.stale {
  color: var(--wv-danger, #d9534f);
}
.sp-extra {
  margin-left: 8px;
  font-size: 11px;
  color: var(--wv-text-4);
}
.sp-lock-label {
  font-size: 12px;
}
.sp-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 640px) {
  .sp-row {
    flex-wrap: wrap;
  }
  .sp-count {
    margin-left: 0;
    width: 100%;
  }
}
</style>
