<script setup lang="ts">
import { NButton, NInput, NModal } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import ScriptLengthField from './ScriptLengthField.vue'
import { rememberedEpisodeTarget } from '@/utils/script'

/**
 * 「开始下一集」弹层：先问**是否需要 AI 润色**（用户原话），并设定**本集目标字数**。
 *
 * - 要 AI 润色：AI 先读「精简的故事」，再结合要素与提纲生成本集
 * - 我自己写：直接给空文本框手写
 *
 * 2026-09-17 用户追加两条：
 * 1. 两个选项都要有**右下角的主按钮**（原来只能点卡片本身，没有主按钮，容易找不到）；
 * 2. 选「我自己写」也要能 **AI 润色** —— 这里可以直接写/粘贴一段草稿，右下角按钮就在**你的稿子**上润色
 *    （不重编剧情）；一个字都不写就点「开始手写」，进编辑器（编辑器里同样有「AI 润色」）。
 */
const props = defineProps<{
  show: boolean
  nextNo: number
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  choose: [
    payload: {
      polished: boolean
      titleHint: string
      instruction: string
      targetChars: number
      /** 「我自己写」时先写/粘贴的草稿：非空 → 只润色这段；空 → 进编辑器空白手写 */
      draft: string
    },
  ]
}>()

const mode = ref<'ai' | 'self'>('ai')
const titleHint = ref('')
const instruction = ref('')
const targetChars = ref(rememberedEpisodeTarget())
const draft = ref('')

watch(
  () => props.show,
  (v) => {
    if (v) {
      mode.value = 'ai'
      titleHint.value = ''
      instruction.value = ''
      targetChars.value = rememberedEpisodeTarget()
      draft.value = ''
    }
  },
)

/** 右下角主按钮：文案与动作都跟着选项走（自己写 + 已有草稿 = 润色草稿） */
const footer = computed(() => {
  if (mode.value === 'ai') {
    return { label: 'AI 润色整集', hint: '按《精简的故事》与剧本要素生成一整集' }
  }
  if (draft.value.trim()) {
    return { label: 'AI 润色我的稿子', hint: '在你的基础上改文笔，不重编剧情' }
  }
  return { label: '开始手写', hint: '进编辑器空白手写（编辑器里也能「AI 润色」）' }
})

function submit(): void {
  emit('choose', {
    polished: mode.value === 'ai',
    titleHint: titleHint.value,
    instruction: instruction.value,
    targetChars: targetChars.value,
    draft: mode.value === 'self' ? draft.value : '',
  })
}
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
      <br />选「我自己写」时，你也可以先把自己的稿子贴进来，让 AI 在你写的基础上润色（不重编剧情）。
    </p>

    <div class="options" role="radiogroup" aria-label="本集怎么写">
      <button
        type="button"
        role="radio"
        :aria-checked="mode === 'ai'"
        :class="['opt', { on: mode === 'ai' }]"
        :disabled="busy"
        data-testid="next-episode-mode-ai"
        @click="mode = 'ai'"
      >
        <span class="opt-name">要 AI 润色</span>
        <span class="opt-desc">按精简故事与要素生成完整一集，之后仍可自由修改</span>
      </button>
      <button
        type="button"
        role="radio"
        :aria-checked="mode === 'self'"
        :class="['opt plain', { on: mode === 'self' }]"
        :disabled="busy"
        data-testid="next-episode-mode-self"
        @click="mode = 'self'"
      >
        <span class="opt-name">我自己写</span>
        <span class="opt-desc">打开文本自己写；写好后也能一键让 AI 润色</span>
      </button>
    </div>

    <div v-if="mode === 'self'" class="draft">
      <label class="lbl">我已经写好的这一集（可选 —— 贴进来就能让 AI 润色）</label>
      <NInput
        v-model:value="draft"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 10 }"
        :maxlength="40000"
        show-count
        placeholder="把你的稿子贴/写在这里；留空则直接进编辑器手写"
        data-testid="next-episode-draft"
      />
      <p class="tip text-secondary">
        AI 只会改文笔与节奏（精修台词、补舞台说明、理顺衔接），<b>不会改剧情</b>；结果进编辑器，你确认后再保存。
        要「保持原长度」（不增不减），保存后在分集里点「AI 润色」并勾「保持原长度」。
      </p>
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
        <p class="lbl len-lbl">
          {{ mode === 'ai' ? '本集目标字数' : '润色后的目标字数（按你写的正文改，目标是这么长）' }}
        </p>
        <ScriptLengthField v-model="targetChars" label="本集字数" />
      </div>
    </div>

    <template #footer>
      <div class="foot">
        <span class="foot-hint text-secondary">{{ footer.hint }}</span>
        <NButton quaternary :disabled="busy" @click="emit('update:show', false)">取消</NButton>
        <NButton
          type="primary"
          :loading="busy"
          :disabled="busy"
          data-testid="next-episode-submit"
          @click="submit"
        >
          {{ footer.label }}
        </NButton>
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
  background: var(--wv-surface-sunken); border: 1px solid var(--wv-line);
  color: var(--wv-text); transition: background var(--wv-dur) var(--wv-ease), border-color var(--wv-dur) var(--wv-ease);
}
.opt:hover { background: var(--wv-surface-raised); }
.opt.on { background: var(--wv-accent-soft); border-color: var(--wv-accent-strong); }
.opt-name { font-size: 15px; font-weight: 600; }
.opt-desc { font-size: 12px; color: var(--wv-text-3); line-height: 1.6; }
.draft { display: flex; flex-direction: column; gap: 6px; margin-top: 16px; }
.extra { display: flex; flex-direction: column; gap: 6px; margin-top: 18px; }
.lbl { font-size: 12px; color: var(--wv-text-3); margin-top: 6px; }
.tip { margin: 2px 0 0; font-size: 12px; line-height: 1.7; }
.len-block { margin-top: 14px; padding: 14px 16px 4px; border-radius: var(--wv-radius-s); background: var(--wv-surface-sunken); border: 1px solid var(--wv-line); }
.len-lbl { margin: 0 0 10px; font-size: 12px; }
.foot { display: flex; align-items: center; gap: 10px; }
.foot-hint { margin-right: auto; font-size: 12px; }
@media (max-width: 560px) {
  .options { grid-template-columns: 1fr; }
  .foot { flex-wrap: wrap; }
  .foot-hint { flex: 1 0 100%; }
}
</style>
