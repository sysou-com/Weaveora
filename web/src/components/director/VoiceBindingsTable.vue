<script setup lang="ts">
/**
 * P8 角色 → 音色绑定。说话人名字与分镜里的「说话人 subject」对齐即自动关联；
 * 未绑定的角色回落到 plan.audio.voice（默认音色）。
 *
 * ★ 2026-09-19（用户要求，第二次纠正口径）：**一个下拉框列出所有剧情主体，选定后绑定/解绑，不展示列表。**
 *   · 第一版：4 列表格（角色｜音色｜语速｜用量）→ 用户否
 *   · 第二版：选择条 + 已绑定小标签列表 → 用户否（"不用展示绑定的列表"）
 *   · 本版：只留「角色下拉（全部剧情主体）→ 音色下拉 + 绑定/解绑」，下面是**一行状态**（当前这个角色绑了谁），
 *           不渲染任何列表。想看别的角色绑了什么，就在下拉里换成那个角色。
 */
import { NButton, NInputNumber, NSelect, NTooltip } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import type { VideoPlan, VoiceBinding } from '@/api/types'

const props = withDefaults(
  defineProps<{
    plan: VideoPlan
    disabled?: boolean
    /** 音色选项（内置名 + 克隆音色 clone:<id>） */
    voices?: { label: string; value: string }[]
    /** **所有剧情主体**（来自参考图主体 + 分镜里的说话人），下拉用 */
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

/* ---------------- 只操作「当前选中的这个角色」 ---------------- */
const pickSubject = ref('')
const pickVoice = ref('')

/** 下拉：**所有剧情主体**（不再过滤掉已绑定的——本来就是一次编辑一个角色） */
const subjectOpts = computed(() =>
  (props.knownSubjects ?? []).filter(Boolean).map((s) => ({ label: s, value: s })),
)
const voiceOpts = computed(() => props.voices ?? [])

const curRow = computed(() =>
  rows.value.find((r) => (r.subject ?? '').trim() === (pickSubject.value ?? '').trim()),
)
const isBound = computed(() => !!curRow.value && !!(curRow.value.voice ?? '').trim())
const canBind = computed(
  () => !props.disabled && !!(pickSubject.value ?? '').trim() && !!(pickVoice.value ?? '').trim(),
)

/** 换角色 → 把该角色**当前绑定的音色**回填到音色框（没绑过就清空），这样「绑定」即改绑 */
watch(
  () => pickSubject.value,
  (s) => {
    const r = rows.value.find((x) => (x.subject ?? '').trim() === (s ?? '').trim())
    pickVoice.value = (r?.voice ?? '') || ''
  },
)

function bind(): void {
  const s = (pickSubject.value ?? '').trim()
  const v = (pickVoice.value ?? '').trim()
  if (!s || !v) return
  if (curRow.value) {
    curRow.value.voice = v
  } else {
    rows.value.push({ subject: s, voice: v, speed: 1 })
  }
  commit()
}

function unbind(): void {
  const i = rows.value.findIndex((r) => (r.subject ?? '').trim() === (pickSubject.value ?? '').trim())
  if (i < 0) return
  rows.value.splice(i, 1)
  commit()
}

/** 该角色在分镜里被引用了几次（只用于状态行文字，**不显示成数字列表**） */
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

const defaultVoice = computed(() => (props.plan.audio?.voice ?? '').trim())
</script>

<template>
  <div class="vb">
    <div class="vb-pick">
      <NSelect
        v-model:value="pickSubject"
        size="small"
        filterable
        tag
        clearable
        :options="subjectOpts"
        :disabled="disabled"
        placeholder="选角色（全部剧情主体）"
        style="width: 180px"
        data-testid="binding-pick-subject"
      />
      <span class="arrow">→</span>
      <NSelect
        v-model:value="pickVoice"
        size="small"
        filterable
        tag
        clearable
        :options="voiceOpts"
        :disabled="disabled"
        placeholder="选音色（可搜索）"
        style="width: 180px"
        data-testid="binding-pick-voice"
      />
      <NButton
        size="tiny"
        secondary
        type="primary"
        :disabled="!canBind"
        data-testid="binding-bind"
        @click="bind"
      >
        绑定
      </NButton>
      <NButton
        size="tiny"
        quaternary
        type="error"
        :disabled="disabled || !isBound"
        data-testid="binding-unbind"
        @click="unbind"
      >
        解绑
      </NButton>
    </div>

    <!-- 一行状态（不是列表）：只描述**当前选中的这个角色** -->
    <div v-if="pickSubject" class="vb-state">
      <template v-if="isBound">
        <b>{{ curRow?.subject }}</b> 当前绑定「<b>{{ curRow?.voice }}</b>」<template
          v-if="(curRow?.speed ?? 1) !== 1"
        >（语速 {{ curRow?.speed }}×）</template>
        <span class="text-secondary">
          · {{ usage(pickSubject) ? `分镜里有 ${usage(pickSubject)} 段台词由他/她说` : '分镜里还没用到这个说话人' }}
        </span>
        <NTooltip>
          <template #trigger>
            <NInputNumber
              v-model:value="curRow!.speed"
              size="tiny"
              :min="0.5"
              :max="2"
              :step="0.05"
              :disabled="disabled"
              placeholder="1.0"
              class="vb-speed"
              @update:value="commit"
            >
              <template #suffix>×</template>
            </NInputNumber>
          </template>
          语速倍率：1.0 为自然语速；2.0 为两倍速。会应用到该角色<b>所有</b>台词。
        </NTooltip>
      </template>
      <template v-else>
        <b>{{ pickSubject }}</b> 未绑定
        <span class="text-secondary">
          → 回落到默认音色<template v-if="defaultVoice">「{{ defaultVoice }}」</template>
        </span>
      </template>
    </div>

    <p class="vb-tip text-secondary">
      发音人要和分镜里的「说话人」一致才会关联。选好音色点「绑定」即可（重复绑定＝改绑）。
    </p>
  </div>
</template>

<style scoped>
.vb {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.vb-pick {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.vb-state {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--wv-text-2);
}
.vb-state b {
  color: var(--wv-text-1);
}
.vb-speed {
  width: 64px;
}
.vb-speed :deep(.n-input) {
  --n-height: 20px;
  font-size: 11px;
}
.arrow {
  opacity: 0.5;
}
.vb-tip {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
}
</style>
