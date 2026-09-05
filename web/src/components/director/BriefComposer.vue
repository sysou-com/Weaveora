<script setup lang="ts">
import { WandSparkles } from 'lucide-vue-next'
import { NButton, NIcon, NInput } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import type { ProjectMode } from '@/api/types'

const props = defineProps<{
  mode: ProjectMode
  busy?: boolean
}>()

const emit = defineEmits<{ submit: [payload: { rawText: string; dirMode: 'image' | 'video' }] }>()

const rawText = ref('')
const pickMode = ref<'image' | 'video' | null>(null)

// 目标模式缺省跟随项目类型；mixed 需用户选
const dirMode = computed<'image' | 'video' | null>(() => {
  if (props.mode === 'video') return 'video'
  if (props.mode === 'image') return 'image'
  return pickMode.value
})

watch(
  () => props.mode,
  () => {
    pickMode.value = null
  },
)

const examples = [
  { text: '被水淹的巴洛克图书馆，月光从穹顶落下，不要人，电影静帧，16:9。', d: 'image' as const },
  { text: '青瓷盘子与一枝白梅，窗光，产品海报，1:1。', d: 'image' as const },
  { text: '一只纸船穿越城市雨夜的 12 秒短片，孤独，不要人脸。', d: 'video' as const },
]

function useExample(ex: (typeof examples)[number]): void {
  rawText.value = ex.text
  if (props.mode === 'mixed') pickMode.value = ex.d
}

const tooShort = computed(() => rawText.value.trim().length > 0 && rawText.value.trim().length < 10)

function submit(): void {
  if (!dirMode.value) return
  emit('submit', { rawText: rawText.value.trim(), dirMode: dirMode.value })
}
</script>

<template>
  <div class="composer" data-testid="brief-composer">
    <div class="head">
      <span class="en font-mono">BRIEF</span>
      <span class="hint text-secondary">用一句话描述，导演层会织成可编辑方案</span>
    </div>

    <NInput
      v-model:value="rawText"
      type="textarea"
      placeholder="例如：一只纸船在暴雨城市的运河里漂过霓虹，孤独，不要人脸……"
      :autosize="{ minRows: 5, maxRows: 12 }"
      :maxlength="2000"
      show-count
      data-testid="brief-input"
    />

    <div v-if="mode === 'mixed'" class="mode-pick" role="radiogroup" aria-label="导演模式">
      <button
        type="button"
        role="radio"
        :aria-checked="dirMode === 'image'"
        :class="['chip', { on: dirMode === 'image' }]"
        @click="pickMode = 'image'"
      >
        按图片导演
      </button>
      <button
        type="button"
        role="radio"
        :aria-checked="dirMode === 'video'"
        :class="['chip', { on: dirMode === 'video' }]"
        @click="pickMode = 'video'"
      >
        按视频导演
      </button>
    </div>

    <div class="examples">
      <p class="examples-label font-mono">怕空白？点一句示例</p>
      <button v-for="ex in examples" :key="ex.text" type="button" class="example" @click="useExample(ex)">
        “{{ ex.text }}”
      </button>
    </div>

    <NButton
      type="primary"
      block
      :loading="busy"
      :disabled="!dirMode || rawText.trim().length < 10"
      data-testid="brief-generate"
      @click="submit"
    >
      <template #icon><NIcon><WandSparkles :size="16" /></NIcon></template>
      交给导演层
    </NButton>
    <p v-if="tooShort" class="tip text-secondary">至少 10 个字（§7.2）</p>
  </div>
</template>

<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.en {
  font-size: 11px;
  letter-spacing: 0.3em;
  color: var(--wv-accent-text);
}
.hint {
  font-size: 12px;
}
.mode-pick {
  display: flex;
  gap: 8px;
}
.chip {
  appearance: none;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  color: var(--wv-text-3);
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--wv-dur) var(--wv-ease), color var(--wv-dur) var(--wv-ease);
}
.chip.on {
  border-color: var(--wv-accent);
  color: var(--wv-text);
  background: var(--wv-accent-soft);
}
.examples {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.examples-label {
  margin: 0;
  font-size: 10px;
  letter-spacing: 0.16em;
  color: var(--wv-text-4);
}
.example {
  appearance: none;
  text-align: left;
  background: none;
  border: none;
  padding: 3px 0;
  color: var(--wv-text-3);
  font-size: 12.5px;
  cursor: pointer;
  transition: color var(--wv-dur) var(--wv-ease);
}
.example:hover {
  color: var(--wv-accent-text);
}
.tip {
  margin: -6px 0 0;
  font-size: 12px;
}
</style>
