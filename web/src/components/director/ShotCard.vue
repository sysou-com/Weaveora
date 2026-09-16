<script setup lang="ts">
import { Check, ChevronDown } from 'lucide-vue-next'
import { NButton, NIcon, NInput } from 'naive-ui'
import { computed, ref } from 'vue'

import type { DirectorShot } from '@/api/types'
import { shotCastInfo, shotHasText } from '@/utils/plan'
import ShotLayoutEditor, { type LayoutBox } from '@/components/director/ShotLayoutEditor.vue'

const props = withDefaults(
  defineProps<{
    shot: DirectorShot
    /** 落库状态（approved 等），用于显示确认态 */
    status?: string
    /** 已整版确认或生成中：锁编辑 */
    disabled?: boolean
    busy?: boolean
    /** 配音试听中（父级统一 busy） */
    previewBusy?: boolean
    /** P12：该镜已封版（资源达标，批量生成会跳过） */
    locked?: boolean
    /** P5：本镜可能出镜的主体名（方案主体顺序），供「画面位置」编辑器用 */
    subjects?: string[]
    /** P5：方案级默认区域（主体名 → 归一化框），作为本镜位置的继承默认 */
    defaultLayout?: Record<string, LayoutBox>
    /** 画幅（CSS aspect-ratio 值） */
    aspect?: string
  }>(),
  {
    status: 'draft',
    disabled: false,
    busy: false,
    previewBusy: false,
    locked: false,
    subjects: () => [],
    defaultLayout: () => ({}),
    aspect: '16 / 9',
  },
)

const emit = defineEmits<{
  approve: [shotNo: number]
  previewVoice: [shotNo: number]
  /** P12：切换封版 */
  toggleLock: [shotNo: number, locked: boolean]
  /** P5：AI 更新本镜提示词（含运镜关键帧逐帧）——由父级弹框确认后写入 */
  aiPrompt: [shot: DirectorShot]
  /** P5：跳转到顶部「位置总控」并选中本镜（方案 A：分镜卡只做快捷入口）；★ 2026-09-16 夜加帧号（-1=只看镜） */
  editLayoutOnTop: [shotNo: number, frameIndex?: number]
}>()

const approved = computed(() => props.status === 'approved')

// ---------- P5：本镜主体（cast）----------
/** 主体绑定情况：显式 cast / 文本自动 / 都未命中（ambiguous） */
const castInfo = computed(() => shotCastInfo(props.shot, props.subjects ?? []))
/** 勾选状态（未显式指定时按自动推断显示，避免“看着是空的”） */
const castChecked = computed<Record<string, boolean>>(() => {
  const set = new Set(castInfo.value.subjects)
  const out: Record<string, boolean> = {}
  for (const n of props.subjects ?? []) out[n] = set.has(n)
  return out
})
function toggleCast(name: string, on: boolean): void {
  const next = new Set(castInfo.value.subjects)
  if (on) next.add(name)
  else next.delete(name)
  // 「本镜主体」的实际注入顺序按方案主体顺序
  props.shot.cast = (props.subjects ?? []).filter((n) => next.has(n))
}
/** 回到「按镜文本自动」：删掉 cast 字段（而不是写空数组——空数组 = 明确的空镜） */
function castAuto(): void {
  delete (props.shot as unknown as Record<string, unknown>).cast
}
/** 明确的空镜（环境/道具镜）：不注入任何人物参考图 */
function castEmpty(): void {
  props.shot.cast = []
}

// ---------- ★ 2026-09-16 夜：**帧级主体**（运镜关键帧每一帧可以出镜不同的人）----------
/** 关键帧元素类型 */
type Kf = NonNullable<DirectorShot['keyframes']>[number]
/** 帧级是否**显式**指定过 cast（undefined=继承镜级） */
function kfCastExplicit(kf: Kf): boolean {
  return Array.isArray((kf as { cast?: string[] | null }).cast)
}
/** 该帧当前生效的主体集合（显式则是显式值，否则继承本镜） */
function kfCastSet(kf: Kf): string[] {
  return kfCastExplicit(kf) ? [...((kf as { cast?: string[] }).cast ?? [])] : [...castInfo.value.subjects]
}
function kfCastChecked(kf: Kf): Record<string, boolean> {
  const set = new Set(kfCastSet(kf))
  const out: Record<string, boolean> = {}
  for (const n of props.subjects ?? []) out[n] = set.has(n)
  return out
}
function toggleKfCast(kf: Kf, name: string, on: boolean): void {
  const next = new Set(kfCastSet(kf))
  if (on) next.add(name)
  else next.delete(name)
  ;(kf as { cast?: string[] | null }).cast = (props.subjects ?? []).filter((n) => next.has(n))
}
/** 改回「继承本镜」：删掉帧级 cast 字段（空数组 = 该帧空镜，语义不同） */
function kfCastAuto(kf: Kf): void {
  delete (kf as unknown as Record<string, unknown>).cast
}
function kfCastEmpty(kf: Kf): void {
  ;(kf as { cast?: string[] | null }).cast = []
}

/** 分镜折叠板：默认收起，只展示“画面动作”摘要，展开才显示全部字段 */
const open = ref(false)
const summaryText = computed(() => {
  const a = props.shot.action?.trim()
  if (a) return a
  const p = props.shot.positive_prompt?.trim() ?? ''
  return p.length > 160 ? `${p.slice(0, 160)}…` : p || '（暂无动作描述，展开编辑）'
})

const sizeOptions = [
  { label: '远景 EW', value: 'extreme-wide' },
  { label: '全景 W', value: 'wide' },
  { label: '中景 M', value: 'medium' },
  { label: '近景 CU', value: 'close-up' },
  { label: '特写 ECU', value: 'extreme-close-up' },
]
</script>

<template>
  <article :class="['shot-card', { approved, locked: disabled, sealed: locked }]" :data-testid="`shot-card-${shot.shot_no}`">
    <header class="shot-head">
      <div class="shot-tag font-mono">
        <span class="dot" :class="{ on: approved }" />
        SHOT {{ shot.shot_no }}
        <span class="dur">{{ Number(shot.duration_sec).toFixed(2) }}s</span>
        <span v-if="shot.keyframes && shot.keyframes.length" class="dur kf-hint">运镜 {{ shot.keyframes.length }} 帧</span>
      </div>
      <NButton
        size="tiny"
        :type="locked ? 'warning' : 'default'"
        :secondary="locked"
        quaternary
        :data-testid="`shot-lock-${shot.shot_no}`"
        :title="locked
          ? '已封版：批量生成会跳过本镜（点一下取消封版）'
          : '封版：本镜资源已达标，之后批量生成自动跳过'"
        @click="emit('toggleLock', shot.shot_no, !locked)"
      >
        {{ locked ? '🔒 已封版' : '封版' }}
      </NButton>
      <NButton
        size="tiny"
        quaternary
        :disabled="disabled"
        data-testid="shot-ai-prompt"
        title="用本镜的「画面动作」让 AI 重写正/负提示词（运镜关键帧会一起逐帧重写）"
        @click="emit('aiPrompt', shot)"
      >
        AI 更新提示词
      </NButton>
      <NButton
        v-if="shotHasText(shot)"
        size="tiny"
        quaternary
        :loading="previewBusy"
        :data-testid="`shot-preview-voice-${shot.shot_no}`"
        title="用本镜旁白试听配音（自托管 CosyVoice）"
        @click="emit('previewVoice', shot.shot_no)"
      >
        试听配音
      </NButton>
      <NButton
        v-if="!disabled && !approved"
        size="tiny"
        secondary
        type="primary"
        :loading="busy"
        :data-testid="`shot-approve-${shot.shot_no}`"
        @click="emit('approve', shot.shot_no)"
      >
        <template #icon><NIcon><Check :size="13" /></NIcon></template>
        确认此镜
      </NButton>
      <span v-else-if="approved" class="approved-chip font-mono">✓ APPROVED</span>
      <span v-else-if="disabled" class="approved-chip dim font-mono">整版已确认</span>
    </header>

    <!-- 折叠摘要：默认只展示画面动作 -->
    <button
      type="button"
      class="shot-summary"
      :class="{ on: open }"
      :aria-expanded="open"
      data-testid="shot-toggle"
      @click="open = !open"
    >
      <span class="sum-label font-mono">画面动作</span>
      <span class="sum-text">{{ summaryText }}</span>
      <NIcon size="14" class="sum-chev"><ChevronDown /></NIcon>
    </button>

    <div v-show="open" class="shot-grid">
      <label class="field">
        <span class="fl">景别</span>
        <select v-model="shot.shot_size" class="select" :disabled="disabled">
          <option v-for="o in sizeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
      </label>
      <label class="field">
        <span class="fl">运镜</span>
        <input v-model="shot.camera_move" class="text" type="text" placeholder="slow dolly in" :disabled="disabled" />
      </label>
      <label class="field wide">
        <span class="fl">画面动作（中文描述，供确认/剪辑用）</span>
        <NInput
          :value="shot.action"
          size="small"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 3 }"
          :disabled="disabled"
          placeholder="本镜发生什么"
          @update:value="(v) => { shot.action = v as string; shot.en_synced = false }"
        />
      </label>
      <label class="field wide">
        <span class="fl">正向提示词（EN，20–1200）</span>
        <NInput
          v-model:value="shot.positive_prompt"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
          :disabled="disabled"
          data-testid="shot-positive"
        />
      </label>
      <label class="field wide">
        <span class="fl">负向提示词</span>
        <NInput
          v-model:value="shot.negative_prompt"
          size="small"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          :disabled="disabled"
        />
      </label>
      <!-- P5 本镜主体：不点明就会“全部注入”（多角色串脸）或拿不到参考图，所以显式勾选 -->
      <div v-if="(subjects ?? []).length" class="field wide">
        <span class="fl">
          本镜主体
          <span v-if="castInfo.explicit" class="cast-tag on font-mono">已指定</span>
          <span v-else class="cast-tag font-mono">自动（按镜文本匹配）</span>
        </span>
        <div class="cast-row">
          <label v-for="n in subjects" :key="n" class="cast-item" :class="{ off: !castChecked[n] }">
            <input
              type="checkbox"
              :checked="castChecked[n]"
              :disabled="disabled"
              :data-testid="`shot-cast-${shot.shot_no}-${n}`"
              @change="toggleCast(n, ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ n }}</span>
          </label>
          <span class="cast-actions">
            <button v-if="castInfo.explicit" type="button" class="link-btn" :disabled="disabled" @click="castAuto">
              改回自动
            </button>
            <button type="button" class="link-btn" :disabled="disabled" title="本镜没人（纯环境/道具镜）：不注入任何人物参考图" @click="castEmpty">
              标记为空镜
            </button>
          </span>
        </div>
        <p v-if="castInfo.ambiguous" class="cast-warn" data-testid="shot-cast-warn">
          ⚠️ 本镜的「画面动作/正向提示词」没点到任何主体 —— 生成时会按**全部 {{ subjects?.length }} 个主体**注入参考图（多角色容易串脸）。
          请勾选本镜真正出镜的主体，或点「标记为空镜」。
        </p>
        <p v-else-if="castInfo.explicit && !castInfo.subjects.length" class="cast-note text-secondary">
          已标记为空镜：本镜不注入人物参考图（仅文生图 + 风格）。
        </p>
      </div>
      <!-- P5 逐镜画面位置（快捷入口）：正式编辑在顶部「位置总控」，这里也能直接拖，并可一键跳过去 -->
      <div class="field wide">
        <div class="kf-hint-row">
          <span class="fl" style="margin: 0">画面位置（本镜）</span>
          <button type="button" class="link-btn" data-testid="shot-edit-layout-top"
                  title="跳到顶部「位置总控」：那里能按作用范围改（方案默认 / 本镜 / 本镜的某一帧）"
                  @click="emit('editLayoutOnTop', shot.shot_no)">
            在顶部编辑位置 →
          </button>
        </div>
        <ShotLayoutEditor
          :model-value="shot.layout ?? null"
          :subjects="castInfo.subjects"
          :defaults="defaultLayout"
          :aspect="aspect"
          :disabled="disabled"
          @update:model-value="(v) => { shot.layout = v }"
        />
      </div>
      <!-- P2 运镜关键帧：穿越型镜头逐帧生成（同 seed），motion 用首/尾帧 -->
      <div v-if="shot.keyframes && shot.keyframes.length" class="field wide kf-block">
        <span class="fl">运镜关键帧（{{ shot.keyframes.length }} 帧 · 生成时逐帧出图，motion 用首/尾帧）</span>
        <div class="kf-list">
          <div v-for="(kf, i) in shot.keyframes" :key="i" class="kf-row">
            <div class="kf-head">
              <span class="kf-no font-mono">#{{ i + 1 }}</span>
              <input
                v-model="kf.label"
                class="text kf-label"
                type="text"
                placeholder="起始帧/结束帧"
                :disabled="disabled"
              />
              <input
                v-if="kf.shot_size !== undefined"
                v-model="kf.shot_size"
                class="text kf-size"
                type="text"
                placeholder="shot_size"
                :disabled="disabled"
              />
            </div>
            <NInput
              v-model:value="kf.positive_prompt"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
              :disabled="disabled"
            />
            <p v-if="kf.composition" class="kf-comp">{{ kf.composition }}</p>
            <!-- ★ 2026-09-16 夜（用户报「运镜帧没有剧情主体，出图随意」）：每一帧都能指定主体 -->
            <div v-if="(subjects ?? []).length" class="kf-cast">
              <span class="kf-cast-label">
                本帧主体
                <span v-if="kfCastExplicit(kf)" class="cast-tag on font-mono">已指定</span>
                <span v-else class="cast-tag font-mono">继承本镜</span>
              </span>
              <label v-for="n in subjects" :key="n" class="cast-item" :class="{ off: !kfCastChecked(kf)[n] }">
                <input
                  type="checkbox"
                  :checked="kfCastChecked(kf)[n]"
                  :disabled="disabled"
                  :data-testid="`kf-cast-${shot.shot_no}-${i}-${n}`"
                  @change="toggleKfCast(kf, n, ($event.target as HTMLInputElement).checked)"
                />
                <span>{{ n }}</span>
              </label>
              <span class="cast-actions">
                <button v-if="kfCastExplicit(kf)" type="button" class="link-btn" :disabled="disabled" @click="kfCastAuto(kf)">
                  改回继承
                </button>
                <button type="button" class="link-btn" :disabled="disabled" title="这一帧没有人物（纯环境/道具帧）" @click="kfCastEmpty(kf)">
                  本帧空镜
                </button>
                <button type="button" class="link-btn" :disabled="disabled" data-testid="kf-edit-layout"
                        title="到顶部「位置总控」按「第N镜·帧M」调这一帧的区域位置" @click="emit('editLayoutOnTop', shot.shot_no, i)">
                  编辑本帧区域
                </button>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.shot-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  transition: border-color var(--wv-dur) var(--wv-ease);
}
.shot-card.approved {
  border-color: color-mix(in srgb, var(--wv-success) 45%, var(--wv-line));
}
.shot-card.locked {
  opacity: 0.85;
}
/* P12：已封版的分镜——左侧一道金色标记 + 徽标，一眼看出“这镜不再动” */
.shot-card.sealed {
  border-left: 3px solid var(--wv-accent, #d0a24e);
}

.shot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.shot-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 0.14em;
  color: var(--wv-text-3);
}
.shot-tag .dur {
  color: var(--wv-text-4);
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 4px;
  padding: 1px 6px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wv-line-strong);
}
.dot.on {
  background: var(--wv-success);
}
.approved-chip {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-success);
}
.approved-chip.dim {
  color: var(--wv-text-4);
}

/* 折叠摘要条：默认收起只展示画面动作 */
.shot-summary {
  appearance: none;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  border-radius: 8px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  color: var(--wv-text-2);
  cursor: pointer;
  transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.shot-summary:hover {
  border-color: color-mix(in srgb, var(--wv-accent) 45%, var(--wv-line));
  background: var(--wv-surface-raised);
}
.shot-summary .sum-label {
  flex: none;
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-accent-text);
  background: var(--wv-accent-soft);
  padding: 3px 8px;
  border-radius: 5px;
}
.shot-summary .sum-text {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--wv-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.shot-summary .sum-chev {
  flex: none;
  color: var(--wv-text-4);
  transition: transform var(--wv-dur) var(--wv-ease);
}
.shot-summary.on .sum-chev {
  transform: rotate(180deg);
}

.shot-grid {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 10px 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.field.wide {
  grid-column: 1 / -1;
}
.fl {
  font-size: 11px;
  color: var(--wv-text-4);
  letter-spacing: 0.04em;
}
/* P5：本镜主体勾选（不点名就会全部注入 → 串脸，所以做成看得见的选择） */
.cast-tag {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 3px;
  border: 1px solid var(--wv-line);
  color: var(--wv-text-4);
}
.cast-tag.on {
  border-color: color-mix(in srgb, var(--wv-accent, #d0a24e) 55%, var(--wv-line));
  color: var(--wv-accent, #d0a24e);
}
.cast-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.cast-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--wv-text-2);
  cursor: pointer;
}
.cast-item.off {
  color: var(--wv-text-4);
}
.cast-actions {
  display: inline-flex;
  gap: 10px;
  margin-left: auto;
}
.cast-actions .link-btn {
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--wv-accent, #d0a24e);
  cursor: pointer;
}
.cast-actions .link-btn:disabled {
  color: var(--wv-text-4);
  cursor: not-allowed;
}
.kf-hint-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.kf-hint-row .link-btn {
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--wv-accent, #d0a24e);
  cursor: pointer;
}
.cast-warn {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--wv-danger, #c45c4a);
}
.cast-note {
  margin: 0;
  font-size: 11.5px;
}
.select,
.text {
  width: 100%;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 6px;
  color: var(--wv-text-2);
  font-family: var(--wv-font-mono);
  font-size: 12px;
  padding: 5px 8px;
  outline: none;
}
.select:focus,
.text:focus {
  border-color: color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
}
.select:disabled,
.text:disabled {
  opacity: 0.6;
}

/* P2 运镜关键帧 */
.kf-hint {
  color: var(--wv-accent-text);
}
.kf-block {
  border-top: 1px dashed var(--wv-line);
  padding-top: 10px;
}
.kf-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kf-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 8px;
}
.kf-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kf-no {
  font-size: 10px;
  color: var(--wv-accent-text);
  background: var(--wv-accent-soft);
  border-radius: 5px;
  padding: 2px 7px;
  flex: none;
}
.kf-label {
  flex: 1 1 auto;
  min-width: 0;
}
.kf-size {
  flex: 0 0 130px;
  min-width: 0;
}
.kf-cast { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 6px; }
.kf-cast-label { font-size: 11px; color: var(--wv-text-4); }
.kf-comp {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--wv-text-4);
  font-family: var(--wv-font-mono);
}
@media (max-width: 760px) {
  .kf-size {
    flex-basis: 96px;
  }
}
</style>
