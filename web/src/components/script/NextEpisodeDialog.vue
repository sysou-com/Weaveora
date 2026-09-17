<script setup lang="ts">
import { NButton, NInput, NModal } from 'naive-ui'
import { ref, watch } from 'vue'

import ScriptLengthField from './ScriptLengthField.vue'
import { rememberedEpisodeTarget } from '@/utils/script'

/**
 * 「开始下一集」弹层：先问**是否需要 AI 润色**（用户原话），并设定**本集目标字数**（用户 2026-09-17 追加）。
 * - 要润色：AI 先读「精简的故事」，再结合要素与提纲生成本集
 * - 不要：直接给空文本框，自己写
 */
const props = defineProps<{
  show: boolean
  nextNo: number
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  choose: [payload: { polished: boolean; titleHint: string; instruction: string; targetChars: number }]
}>()

const titleHint = ref('')
const instruction = ref('')
const targetChars = ref(rememberedEpisodeTarget())

watch(
  () => props.show,
  (v) => {
    if (v) {
      titleHint.value = ''
      instruction.value = ''
      targetChars.value = rememberedEpisodeTarget()
    }
  },
)
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="`开始第 ${nextNo} 集`"
    style="max-width: 620px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lead text-secondary">
      要不要让 AI 润色这一集？AI 会先读**不断更新的「精简的故事」**，再结合剧本要素生成符合下一集剧情的内容。
      <br />长文分段生成，请勿关闭页面。
    </p>

    <div class="options">
      <button type="button" class="opt" :disabled="busy" @click="emit('choose', { polished: true, titleHint, instruction, targetChars })">
        <span class="opt-name">要 AI 润色</span>
        <span class="opt-desc">按精简故事与要素生成完整一集，之后仍可自由修改</span>
      </button>
      <button type="button" class="opt plain" :disabled="busy" @click="emit('choose', { polished: false, titleHint, instruction, targetChars })">
        <span class="opt-name">我自己写</span>
        <span class="opt-desc">打开空白文本，直接手写这一集</span>
      </button>
    </div>

    <div class="extra">
      <label class="lbl">本集标题提示（可选）</label>
      <NInput v-model:value="titleHint" :maxlength="200" placeholder="例如：雨夜重逢" />
      <label class="lbl">额外要求（可选）</label>
      <NInput
        v-model:value="instruction"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 4 }"
        :maxlength="2000"
        placeholder="例如：本集要交代父辈的秘密，结尾留一个悬念"
      />
      <div class="len-block">
        <p class="lbl len-lbl">本集目标字数（仅「要 AI 润色」时生效）</p>
        <ScriptLengthField v-model="targetChars" label="本集字数" />
      </div>
    </div>

    <template #footer>
      <div class="foot">
        <NButton quaternary :disabled="busy" @click="emit('update:show', false)">取消</NButton>
        <span v-if="busy" class="text-secondary busy">生成中，请稍候（1–3 分钟）…</span>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lead { margin: 0 0 16px; font-size: 13px; line-height: 1.8; }
.options { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.opt {
  display: flex; flex-direction: column; gap: 6px; text-align: left;
  padding: 16px; border-radius: var(--wv-radius-m); cursor: pointer;
  background: var(--wv-accent-soft); border: 1px solid var(--wv-accent-strong);
  color: var(--wv-text); transition: background var(--wv-dur) var(--wv-ease);
}
.opt:hover { background: color-mix(in srgb, var(--wv-accent) 18%, var(--wv-surface)); }
.opt.plain { background: var(--wv-surface-sunken); border-color: var(--wv-line); }
.opt.plain:hover { background: var(--wv-surface-raised); }
.opt-name { font-size: 15px; font-weight: 600; }
.opt-desc { font-size: 12px; color: var(--wv-text-3); line-height: 1.6; }
.extra { display: flex; flex-direction: column; gap: 6px; margin-top: 18px; }
.lbl { font-size: 12px; color: var(--wv-text-3); margin-top: 6px; }
.len-block { margin-top: 14px; padding: 14px 16px 4px; border-radius: var(--wv-radius-s); background: var(--wv-surface-sunken); border: 1px solid var(--wv-line); }
.len-lbl { margin: 0 0 10px; font-size: 12px; }
.foot { display: flex; align-items: center; gap: 12px; }
.busy { margin-left: auto; font-size: 12px; }
@media (max-width: 560px) {
  .options { grid-template-columns: 1fr; }
}
</style>
