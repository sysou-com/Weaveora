<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { NButton, NFormItem, NModal, NSelect, NSwitch } from 'naive-ui'
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
}

const props = defineProps<{
  show: boolean
  episodeLabel: string
  busy?: boolean
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
  })
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="转成项目"
    style="max-width: 640px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lead text-secondary">
      把 <b>{{ episodeLabel }}</b> 的标题与内容带入一个新项目，并按下面的项目要素转化成分镜动作与提示词。
    </p>

    <div class="grid">
      <NFormItem label="创作类型">
        <NSelect
          v-model:value="mode"
          :options="[
            { label: '视频（出分镜 · 推荐）', value: 'video' },
            { label: '图片', value: 'image' },
          ]"
        />
      </NFormItem>

      <NFormItem label="画幅">
        <NSelect v-model:value="aspectRatio" :options="ASPECT_OPTIONS" />
      </NFormItem>

      <NFormItem v-if="mode === 'video'" label="目标时长">
        <NSelect
          v-model:value="durationSec"
          :options="VIDEO_DURATIONS.map((d) => ({ label: d.label, value: d.value }))"
        />
      </NFormItem>

      <NFormItem v-if="mode === 'video'" label="每镜时长">
        <NSelect v-model:value="shotDurationSec" :options="SHOT_DURATIONS" />
      </NFormItem>

      <NFormItem label="视觉风格" class="span-2">
        <NSelect v-model:value="styleTemplateId" :options="styleOptions" clearable placeholder="默认（跟随描述）" />
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
          {{ runDirector ? '创建项目并生成分镜' : '仅创建项目' }}
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
.sw { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
.sw span { display: flex; flex-direction: column; gap: 2px; }
.sw b { font-size: 13.5px; font-weight: 600; }
.sw em { font-style: normal; font-size: 12px; }
.foot { display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 560px) {
  .grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
}
</style>
