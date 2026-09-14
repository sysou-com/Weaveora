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
  /** 该行是否需要先「指定人脸」（多人镜：每句台词是谁的脸）——点了会打开点选弹窗 */
  needsFaceHint?: boolean
  /** 已指定人脸的人数 / 总说话人数（展示用，如 "1/2"） */
  faceHint?: string
  /** 对口型专用：底片（驱动嘴型的那份画面） */
  lipsyncSource?: 'clip' | 'still' | null
  /** 对口型专用：该镜是否同时有 motion 片段与关键帧静帧（都有才能切底片） */
  hasClip?: boolean
  hasStill?: boolean
  /** 对口型专用：底片是否是自动选的（用户没显式指定） */
  lipsyncAuto?: boolean
  /** 对口型专用：「底片里嘴本来就大张」的高危镜头（惊恐/喊叫）——建议静帧底片/改画外音 */
  expressionRisk?: boolean
}

const props = withDefaults(
  defineProps<{
    show: boolean
    /** 弹窗标题（例如「生成关键帧(still)」） */
    title?: string
    /** 顶部说明（不传用通用文案；对口型这类有前置条件的操作应传入） */
    hint?: string
    /** 当前操作类型（对口型行会多一个「底片」开关） */
    kind?: 'still' | 'clip' | 'voice' | 'lipsync'
    shots: PickerShot[]
    /** 已封版镜号 */
    locked: number[]
    busy?: boolean
  }>(),
  { title: '选择分镜', hint: '', busy: false, kind: 'still' },
)

const emit = defineEmits<{
  'update:show': [v: boolean]
  /** 确认：要处理的镜号 + 封版变更（需要落库的） */
  confirm: [payload: { shotNos: number[]; lock: number[]; unlock: number[] }]
  /** 点「指定人脸」：为该镜打开对口型人脸点选弹窗 */
  'pick-face': [shotNo: number]
  /** 对口型：切换该镜的底片（motion 片段 / 关键帧静帧） */
  'set-base': [payload: { shotNo: number; source: 'clip' | 'still' }]
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
          <button
            v-if="s.needsFaceHint || s.faceHint"
            type="button"
            class="sp-face"
            :class="{ todo: s.needsFaceHint }"
            :data-testid="`sp-face-${s.shotNo}`"
            :title="s.needsFaceHint
              ? '多人说话：先指定每句台词是谁的脸，否则会把台词配到同一张脸上'
              : '查看/修改人脸指定'"
            @click.stop="emit('pick-face', s.shotNo)"
          >
            指定人脸<template v-if="s.faceHint">（{{ s.faceHint }}）</template>
          </button>
          <!-- 对口型：底片切换（motion 片段 / 关键帧静帧）。
               底片 = 驱动嘴型的那份画面；静帧只有一张干净的脸，嘴部状态单一，
               比「已经在动/大张的 motion 片段」稳得多（LatentSync 要把大张的嘴先合上再重开）。 -->
          <span v-if="kind === 'lipsync' && (s.hasClip || s.hasStill)" class="sp-base">
            <span class="sp-base-label">{{ s.lipsyncAuto ? '底片(自动)' : '底片' }}</span>
            <span class="sp-base-seg">
              <button
                type="button"
                class="sp-base-btn"
                :class="{ on: s.lipsyncSource === 'clip' }"
                :disabled="!s.hasClip"
                :title="s.hasClip ? '用该镜最新 motion 片段当底片（保留运镜，但底片里嘴在动时容易画坏）' : '该镜还没有 motion 片段'"
                :data-testid="`sp-base-clip-${s.shotNo}`"
                @click.stop="emit('set-base', { shotNo: s.shotNo, source: 'clip' })"
              >片段</button>
              <button
                type="button"
                class="sp-base-btn"
                :class="{ on: s.lipsyncSource === 'still' }"
                :disabled="!s.hasStill"
                :title="s.hasStill ? '用关键帧静帧当底片（嘴部干净，推荐给近景对话镜）' : '该镜还没有关键帧静帧'"
                :data-testid="`sp-base-still-${s.shotNo}`"
                @click.stop="emit('set-base', { shotNo: s.shotNo, source: 'still' })"
              >静帧</button>
            </span>
            <span v-if="s.expressionRisk" class="sp-risk" :title="'该镜文字里是喊叫/惊恐类表达：底片里嘴很可能大张，对口型会把嘴部画坏——建议用静帧底片，或把这句改成旁白/画外音'">
              ⚠ 大张口风险
            </span>
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
.sp-face {
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--wv-line);
  background: transparent;
  font-size: 11px;
  color: var(--wv-text-3);
  cursor: pointer;
}
.sp-face.todo {
  border-color: var(--wv-warn, #d0a24e);
  color: var(--wv-warn, #d0a24e);
}
.sp-base {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 8px;
}
.sp-base-label {
  font-size: 11px;
  color: var(--wv-text-4);
}
.sp-base-seg {
  display: inline-flex;
  border: 1px solid var(--wv-line);
  border-radius: 999px;
  overflow: hidden;
}
.sp-base-btn {
  padding: 1px 8px;
  border: 0;
  background: transparent;
  font-size: 11px;
  color: var(--wv-text-3);
  cursor: pointer;
}
.sp-base-btn.on {
  background: color-mix(in srgb, var(--wv-accent, #8fb9b4) 30%, transparent);
  color: var(--wv-text-1);
  font-weight: 500;
}
.sp-base-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.sp-risk {
  font-size: 11px;
  color: var(--wv-warn, #d0a24e);
  white-space: nowrap;
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
