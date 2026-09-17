<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { NAlert, NButton, NFormItem, NModal, NRadioButton, NRadioGroup, NSelect, NSwitch } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import { fetchStyleTemplates } from '@/api/styleTemplates'
import { ASPECT_OPTIONS, VIDEO_DURATIONS } from '@/utils/format'

/**
 * 「转成项目」弹层：先设项目要素，再转化成**分镜动作 + 提示词**。
 *
 * 口径：默认「视频 + 立即出方案」——因为用户要的是分镜动作与提示词；
 * `condenseBrief` 默认开：整集正文动辄数千字，先让 AI 压成 ≤1800 字的导演 brief 效果更稳。
 */
export interface ConvertPayload {
  mode: 'image' | 'video'
  aspectRatio: string
  durationSec: number | null
  shotDurationSec: number | null
  styleTemplateId: string | null
  condenseBrief: boolean
  runDirector: boolean
  /** 提示词语言（默认 zh）—— 项目级：导演首次生成与后续 AI 更新提示词都看它 */
  promptLang: 'zh' | 'en'
}
const props = defineProps<{
  show: boolean
  episodeLabel: string
  busy?: boolean
  /** 这集已经转过的项目：有值时红色提示 + 规格锁在项目上（新版本 V+1） */
  existing?: { projectId: string; projectTitle: string; revisionNo?: number | null } | null
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  submit: [payload: ConvertPayload]
}>()

const SHOT_DURATIONS = [
  { label: '自动（导演定镜）', value: 0 },
  { label: '每镜 2 秒', value: 2 },
  { label: '每镜 3 秒', value: 3 },
  { label: '每镜 5 秒', value: 5 },
  { label: '每镜 8 秒', value: 8 },
  { label: '每镜 10 秒', value: 10 },
]

const mode = ref<'image' | 'video'>('video')
const aspectRatio = ref('16:9')
const durationSec = ref<number>(30)
const shotDurationSec = ref<number>(0)
const styleTemplateId = ref<string>('')
const condenseBrief = ref(true)
const runDirector = ref(true)
/** 提示词语言（用户 2026-09-17：转项目时选，**默认中文**） */
const promptLang = ref<'zh' | 'en'>('zh')

const { data: styleTemplates } = useQuery({
  queryKey: ['style-templates'],
  queryFn: () => fetchStyleTemplates(),
  staleTime: 10 * 60 * 1000,
})
const styleOptions = computed(() => [
  { label: '默认（跟随描述）', value: '' },
  ...(styleTemplates.value ?? []).map((s) => ({ label: s.name, value: s.id })),
])

watch(
  () => props.show,
  (v) => {
    if (v) {
      mode.value = 'video'
      aspectRatio.value = '16:9'
      durationSec.value = 30
      shotDurationSec.value = 0
      styleTemplateId.value = ''
      condenseBrief.value = true
      runDirector.value = true
      promptLang.value = 'zh'
    }
  },
)

function submit(): void {
  emit('submit', {
    mode: mode.value,
    aspectRatio: aspectRatio.value,
    durationSec: mode.value === 'video' ? durationSec.value : null,
    shotDurationSec: mode.value === 'video' && shotDurationSec.value > 0 ? shotDurationSec.value : null,
    styleTemplateId: styleTemplateId.value || null,
    condenseBrief: condenseBrief.value,
    runDirector: runDirector.value,
    promptLang: promptLang.value,
  })
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="existing ? '在已有项目里出新版本' : '转成项目'"
    style="max-width: 640px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lead text-secondary">
      <template v-if="existing">
        这一集已经生成过项目《{{ existing.projectTitle }}》
        <b v-if="existing.revisionNo">（当前 V{{ existing.revisionNo }}）</b>。
      </template>
      <template v-else>
        把 <b>{{ episodeLabel }}</b> 的标题与内容带入一个新项目，并按下面的项目要素转化成分镜动作与提示词。
      </template>
    </p>

    <NAlert v-if="existing" type="error" :bordered="false" class="warn">
      <b>这一集已经有项目了，重复转成项目不会新建项目。</b><br />
      继续会在<b>同一个项目</b>里生成<b>新版本 V{{ (existing.revisionNo ?? 0) + 1 }}</b>
      （老版本仍保留、可切回）；类型/画幅/时长/风格沿用项目原有设置。
      确实想另建一个项目，请在项目页复制或联系管理员。
    </NAlert>

    <div class="grid">
      <NFormItem label="创作类型">
        <NSelect
          v-model:value="mode"
          :disabled="!!existing"
          :options="[
            { label: '视频（出分镜 · 推荐）', value: 'video' },
            { label: '图片', value: 'image' },
          ]"
        />
      </NFormItem>

      <NFormItem label="画幅">
        <NSelect v-model:value="aspectRatio" :disabled="!!existing" :options="ASPECT_OPTIONS" />
      </NFormItem>

      <NFormItem v-if="mode === 'video'" label="目标时长">
        <NSelect
          v-model:value="durationSec"
          :disabled="!!existing"
          :options="VIDEO_DURATIONS.map((d) => ({ label: d.label, value: d.value }))"
        />
      </NFormItem>

      <NFormItem v-if="mode === 'video'" label="每镜时长">
        <NSelect v-model:value="shotDurationSec" :disabled="!!existing" :options="SHOT_DURATIONS" />
        <em class="lang-hint text-secondary">
          超过视频模型单次上限的镜头会**自动切段**生成（上限：本机 GPU 由显存决定 ≈6s；云看模型，常见 5s）。
          生成完会告知具体几个镜超限。
        </em>
      </NFormItem>

      <NFormItem label="视觉风格" class="span-2">
        <NSelect
          v-model:value="styleTemplateId"
          :disabled="!!existing"
          :options="styleOptions"
          clearable
          placeholder="默认（跟随描述）"
        />
      </NFormItem>

      <NFormItem label="提示词语言" class="span-2">
        <NRadioGroup v-model:value="promptLang" data-testid="convert-prompt-lang">
          <NRadioButton value="zh">中文</NRadioButton>
          <NRadioButton value="en">English</NRadioButton>
        </NRadioGroup>
        <em class="lang-hint text-secondary">
          默认中文（Qwen 系对中文理解好）：导演**首次生成**的正/负词与运镜关键帧就用这个语言，
          之后项目页「AI 更新提示词」也默认用它。
        </em>
      </NFormItem>
    </div>

    <div class="switches">
      <label class="sw">
        <NSwitch v-model:value="condenseBrief" />
        <span>
          <b>AI 精简 brief</b>
          <em class="text-secondary">先把整集正文压成 ≤1800 字的导演 brief（推荐）</em>
        </span>
      </label>
      <label class="sw">
        <NSwitch v-model:value="runDirector" />
        <span>
          <b>立即生成分镜与提示词</b>
          <em class="text-secondary">关闭则只建项目与 brief，稍后在项目页生成</em>
        </span>
      </label>
    </div>

    <template #footer>
      <div class="foot">
        <NButton quaternary :disabled="busy" @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" :loading="busy" @click="submit">
          {{ existing ? `在项目里生成 V${(existing.revisionNo ?? 0) + 1}` : runDirector ? '创建项目并生成分镜' : '仅创建项目' }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lead { margin: 0 0 16px; font-size: 13px; line-height: 1.8; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.span-2 { grid-column: span 2; }
.switches { display: flex; flex-direction: column; gap: 12px; margin-top: 4px; }
.warn { margin: 0 0 16px; font-size: 12.5px; line-height: 1.85; }
.sw { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
.lang-hint { display: block; margin-top: 6px; font-style: normal; font-size: 11.5px; line-height: 1.7; }
.sw span { display: flex; flex-direction: column; gap: 2px; }
.sw b { font-size: 13.5px; font-weight: 600; }
.sw em { font-style: normal; font-size: 12px; }
.foot { display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 560px) {
  .grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
}
</style>
