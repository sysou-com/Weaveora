<script setup lang="ts">
/**
 * P9 克隆配音弹窗。
 *
 * 两种用途（由 mode 决定）：
 *  - 'preset'：设为可复用音色（一个角色录一次，全片/多段复用）
 *  - 'line'  ：也可以「直接用这段作为本行配音」（真人配音）
 *
 * 流程：取声源（录音/上传）→ 选处理项 → 服务端 ffmpeg 处理 → A/B 对比试听 + 自动转写 → 保存。
 */
import { Mic, Square, Upload } from 'lucide-vue-next'
import { NAlert, NButton, NCheckbox, NIcon, NInput, NModal, NSlider, useMessage } from 'naive-ui'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import {
  createVoicePreset,
  fetchAssetBlob,
  transcribeVoicePreset,
  type VoicePresetCreated,
} from '@/api/assets'

const props = withDefaults(
  defineProps<{
    show: boolean
    disabled?: boolean
    /** preset=设为角色音色；line=也可以用这段作为本行配音 */
    mode?: 'preset' | 'line'
    /** 默认音色名（一般传角色名/说话人） */
    defaultName?: string
    /** 重录音色：传已有音色 id 时，保存是“替换”而不是新增 */
    replaceId?: string
    workspaceId: string
    projectId: string
    /** line 模式下用 */
    shotNo?: number
    lineIndex?: number
    atSec?: number
    subject?: string
  }>(),
  { disabled: false, mode: 'preset', defaultName: '', shotNo: 0, lineIndex: 0, atSec: 0, subject: '' },
)

const emit = defineEmits<{
  'update:show': [v: boolean]
  /** 保存成功：A 用途（写入 plan.audio.voicePresets） */
  saved: [payload: {
    id: string
    name: string
    presetAssetId: string
    rawAssetId: string
    promptText: string
    durationSec: number
  }]
  /** B 用途：已把样本落成本行配音 */
  usedForLine: []
}>()

const message = useMessage()

/* ---------------- 浏览器录音支持检测 ---------------- */
const recSupported = computed(() => {
  if (typeof window === 'undefined') return false
  return typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
})

/** 优先选浏览器支持的容器：Chrome/Edge/Firefox 走 webm/opus，Safari 走 mp4 */
function pickMime(): string {
  const cands = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
  for (const c of cands) {
    try {
      if (MediaRecorder.isTypeSupported(c)) return c
    } catch {
      // ignore
    }
  }
  return ''
}

/* ---------------- 状态 ---------------- */
const srcMode = ref<'record' | 'upload'>('record')
const recording = ref(false)
const recSecs = ref(0)
const blob = ref<Blob | null>(null)
const blobUrl = ref('')
const fileName = ref('')
let recorder: MediaRecorder | null = null
let chunks: BlobPart[] = []
let timer: number | undefined
let stream: MediaStream | null = null

// 处理选项（默认 = 产品默认：去静音 + 响度归一开，其余关）
const trimSilence = ref(true)
const loudnorm = ref(true)
const denoise = ref(false)
const limitLength = ref(false)
const pitch = ref(0)

const busy = ref(false)
const result = ref<VoicePresetCreated | null>(null)
const rawUrl = ref('')
const presetUrl = ref('')
const transcript = ref('')
const transcribing = ref(false)
const name = ref('')

watch(
  () => props.show,
  (v) => {
    if (v) {
      reset()
      name.value = props.defaultName || ''
      srcMode.value = recSupported.value ? 'record' : 'upload'
    } else {
      stopAll()
    }
  },
)

function reset(): void {
  revoke()
  blob.value = null
  fileName.value = ''
  result.value = null
  transcript.value = ''
  recSecs.value = 0
  pitch.value = 0
  trimSilence.value = true
  loudnorm.value = true
  denoise.value = false
  limitLength.value = false
}

function revoke(): void {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
  if (rawUrl.value) URL.revokeObjectURL(rawUrl.value)
  if (presetUrl.value) URL.revokeObjectURL(presetUrl.value)
  blobUrl.value = ''
  rawUrl.value = ''
  presetUrl.value = ''
}

function stopAll(): void {
  if (timer) window.clearInterval(timer)
  timer = undefined
  recording.value = false
  try {
    recorder?.stop()
  } catch {
    // ignore
  }
  recorder = null
  stream?.getTracks().forEach((t) => t.stop())
  stream = null
}

onBeforeUnmount(stopAll)

async function startRec(): Promise<void> {
  if (!recSupported.value) return
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    })
  } catch (e) {
    message.error('拿不到麦克风权限：' + (e instanceof Error ? e.message : '请检查浏览器设置，或改用「上传文件」'))
    return
  }
  const mime = pickMime()
  recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
  chunks = []
  recorder.ondataavailable = (ev) => {
    if (ev.data && ev.data.size > 0) chunks.push(ev.data)
  }
  recorder.onstop = () => {
    const type = recorder?.mimeType || mime || 'audio/webm'
    const b = new Blob(chunks, { type })
    setBlob(b, `record.${type.includes('mp4') ? 'm4a' : 'webm'}`)
    stream?.getTracks().forEach((t) => t.stop())
    stream = null
  }
  recorder.start()
  recording.value = true
  recSecs.value = 0
  timer = window.setInterval(() => {
    recSecs.value += 0.1
    if (recSecs.value >= 20) stopRec()   // 20s 硬上限，参考音用不了那么长
  }, 100)
}

function stopRec(): void {
  if (!recording.value) return
  recording.value = false
  if (timer) window.clearInterval(timer)
  timer = undefined
  try {
    recorder?.stop()
  } catch {
    // ignore
  }
}

function setBlob(b: Blob, fname: string): void {
  revoke()
  blob.value = b
  fileName.value = fname
  blobUrl.value = URL.createObjectURL(b)
  result.value = null
  transcript.value = ''
}

function onPickFile(ev: Event): void {
  const f = (ev.target as HTMLInputElement)?.files?.[0]
  if (f) setBlob(f, f.name)
  ;(ev.target as HTMLInputElement).value = ''
}

/* ---------------- 提交处理 ---------------- */
async function submit(): Promise<void> {
  if (!blob.value) {
    message.warning(srcMode.value === 'record' ? '请先录一段（建议 5–15 秒连续说话）' : '请先选择音频文件')
    return
  }
  busy.value = true
  try {
    const r = await createVoicePreset(props.workspaceId, props.projectId, {
      file: blob.value,
      name: name.value || props.defaultName || '音色',
      trimSilence: trimSilence.value,
      loudnorm: loudnorm.value,
      denoise: denoise.value,
      limitLength: limitLength.value,
      pitchSemitones: pitch.value,
    })
    result.value = r
    for (const w of r.warnings ?? []) message.warning(w, { duration: 6000 })
    // A/B 试听
    const [raw, preset] = await Promise.all([
      fetchAssetBlob(props.workspaceId, r.rawAssetId),
      fetchAssetBlob(props.workspaceId, r.presetAssetId),
    ])
    if (raw) rawUrl.value = URL.createObjectURL(raw)
    if (preset) presetUrl.value = URL.createObjectURL(preset)
    message.success(`样本已处理（${r.durationSec.toFixed(1)}s）`)
    void autoTranscribe(r.presetAssetId)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '处理失败')
  } finally {
    busy.value = false
  }
}

async function autoTranscribe(assetId: string): Promise<void> {
  transcribing.value = true
  try {
    const t = await transcribeVoicePreset(props.workspaceId, props.projectId, assetId)
    transcript.value = t || ''
    if (!t) message.info('自动转写没返回文本，可手动填写（有文本时克隆更像）')
  } finally {
    transcribing.value = false
  }
}

/* ---------------- 保存 ---------------- */
function mkId(n: string): string {
  const base = (n || 'voice').trim().toLowerCase().replace(/[^\w\u4e00-\u9fa5-]+/g, '-').slice(0, 24)
  return `${base || 'voice'}-${Date.now().toString(36).slice(-4)}`
}

function saveAsPreset(): void {
  if (!result.value) return
  emit('saved', {
    id: props.replaceId || mkId(name.value),
    name: (name.value || '音色').trim(),
    presetAssetId: result.value.presetAssetId,
    rawAssetId: result.value.rawAssetId,
    promptText: transcript.value.trim(),
    durationSec: result.value.durationSec,
  })
  emit('update:show', false)
}

async function useForLine(): Promise<void> {
  if (!result.value) return
  // 这里只把「处理后的样本」交给父级落成本行配音（A/B 里用户已经能听到差别）
  emit('saved', {
    id: props.replaceId || mkId(name.value || props.subject || 'voice'),
    name: (name.value || props.subject || '音色').trim(),
    presetAssetId: result.value.presetAssetId,
    rawAssetId: result.value.rawAssetId,
    promptText: transcript.value.trim(),
    durationSec: result.value.durationSec,
  })
  emit('usedForLine')
  emit('update:show', false)
}

const canSave = computed(() => !!result.value && !busy.value)
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="mode === 'line' ? '克隆配音 / 录音配音' : (replaceId ? '重录音色（替换现有）' : '克隆音色')"
    style="max-width: 720px"
    data-testid="voice-clone-modal"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <!-- ① 取声源 -->
    <div class="vc-step">
      <p class="vc-label">① 取声源</p>
      <div class="vc-tabs">
        <NButton
          size="small"
          :type="srcMode === 'record' ? 'primary' : 'default'"
          :secondary="srcMode !== 'record'"
          :disabled="!recSupported"
          :title="recSupported ? '' : '当前浏览器不支持录音，请改用上传文件'"
          @click="srcMode = 'record'"
        >
          <template #icon><NIcon><Mic :size="13" /></NIcon></template>
          现场录音
        </NButton>
        <NButton
          size="small"
          :type="srcMode === 'upload' ? 'primary' : 'default'"
          :secondary="srcMode !== 'upload'"
          @click="srcMode = 'upload'"
        >
          <template #icon><NIcon><Upload :size="13" /></NIcon></template>
          上传文件
        </NButton>
      </div>

      <NAlert v-if="!recSupported" type="warning" :show-icon="true" style="margin-top: 8px">
        当前浏览器不支持网页录音（缺 MediaRecorder / getUserMedia）。请改用
        <b>Chrome / Edge / Firefox</b>，或 <b>Safari 14.1+</b>；也可以直接用下面的「上传文件」。
      </NAlert>

      <div v-if="srcMode === 'record' && recSupported" class="vc-rec">
        <NButton
          v-if="!recording"
          size="small"
          type="primary"
          :disabled="disabled"
          data-testid="clone-record-start"
          @click="startRec"
        >
          <template #icon><NIcon><Mic :size="13" /></NIcon></template>
          {{ blob ? '重录' : '开始录音' }}
        </NButton>
        <NButton v-else size="small" type="error" data-testid="clone-record-stop" @click="stopRec">
          <template #icon><NIcon><Square :size="12" /></NIcon></template>
          停止（{{ recSecs.toFixed(1) }}s）
        </NButton>
        <span class="text-secondary" style="font-size: 12px">
          建议 5–15 秒连续说话；安静环境、不要放背景音乐。20 秒自动停。
        </span>
      </div>

      <div v-else class="vc-rec">
        <input type="file" accept="audio/*,.webm,.m4a" data-testid="clone-file" @change="onPickFile" />
      </div>

      <audio v-if="blobUrl" :src="blobUrl" class="vc-audio" controls preload="metadata" />
      <p v-if="fileName" class="text-secondary vc-file font-mono">
        已选：{{ fileName }}<template v-if="recSecs"> · 录音 {{ recSecs.toFixed(1) }}s</template>
      </p>
    </div>

    <!-- ② 处理 -->
    <div class="vc-step">
      <p class="vc-label">② 声音处理</p>
      <div class="vc-opts">
        <NCheckbox v-model:checked="trimSilence" :disabled="disabled">去首尾静音</NCheckbox>
        <NCheckbox v-model:checked="loudnorm" :disabled="disabled">响度归一（-16 LUFS）</NCheckbox>
        <NCheckbox v-model:checked="denoise" :disabled="disabled">降噪</NCheckbox>
        <NCheckbox v-model:checked="limitLength" :disabled="disabled">裁到 15s 内</NCheckbox>
      </div>
      <div class="vc-pitch">
        <span class="vc-fl">音高微调 <span class="font-mono">{{ pitch.toFixed(1) }} 半音</span></span>
        <NSlider v-model:value="pitch" :min="-4" :max="4" :step="0.5" :disabled="disabled" :tooltip="false" style="max-width: 240px" />
        <span class="text-secondary" style="font-size: 11px">想让男声更低沉/女声更亮可微调，一般保持 0</span>
      </div>
      <p class="text-secondary" style="font-size: 11px; margin: 4px 0 0">
        单声道 + 24kHz 重采样是必做的（CosyVoice 前置要求），已自动应用
      </p>
      <NButton
        size="small"
        type="primary"
        :loading="busy"
        :disabled="disabled || !blob"
        style="margin-top: 8px"
        data-testid="clone-process"
        @click="submit"
      >
        处理这段声音
      </NButton>
    </div>

    <!-- ③ 结果 -->
    <div v-if="result" class="vc-step">
      <p class="vc-label">③ 对比试听（原声 / 处理后）</p>
      <div class="vc-ab">
        <span class="vc-ab-tag font-mono">原声</span>
        <audio v-if="rawUrl" :src="rawUrl" class="vc-audio" controls preload="metadata" />
      </div>
      <div class="vc-ab">
        <span class="vc-ab-tag font-mono">处理后</span>
        <audio v-if="presetUrl" :src="presetUrl" class="vc-audio" controls preload="metadata" />
        <span class="text-secondary" style="font-size: 12px">{{ result.durationSec.toFixed(1) }}s</span>
      </div>

      <label class="vc-field">
        <span class="vc-fl">
          这段说了什么（{{ transcribing ? '自动转写中…' : '自动转写，可手改' }}）
        </span>
        <NInput
          v-model:value="transcript"
          type="textarea"
          size="small"
          :autosize="{ minRows: 2, maxRows: 4 }"
          :disabled="disabled || transcribing"
          placeholder="有文本时克隆更像（例如：希望你以后能够做的比我还好唷。）"
          data-testid="clone-transcript"
        />
      </label>

      <label class="vc-field">
        <span class="vc-fl">音色名称</span>
        <NInput v-model:value="name" size="small" :disabled="disabled" placeholder="如：关羽" data-testid="clone-name" />
      </label>

      <div class="vc-actions">
        <NButton size="small" :disabled="disabled || !canSave" data-testid="clone-use-line" @click="useForLine">
          直接用这段作为本行配音
        </NButton>
        <NButton
          size="small"
          type="primary"
          :disabled="disabled || !canSave"
          data-testid="clone-save-preset"
          @click="saveAsPreset"
        >
          {{ replaceId ? '保存并替换该音色' : '设为角色音色' }}
        </NButton>
      </div>
      <p class="text-secondary" style="font-size: 11px; margin: 6px 0 0">
        设为角色音色后，该角色所有台词都会用这个音色念；「直接用这段」则是把这句真人录音当成本行配音。
        克隆他人声音请确保已获得授权。
      </p>
    </div>
  </NModal>
</template>

<style scoped>
.vc-step {
  margin-bottom: 14px;
}
.vc-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  opacity: 0.9;
}
.vc-tabs,
.vc-rec,
.vc-opts,
.vc-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.vc-audio {
  width: 100%;
  height: 34px;
  margin-top: 6px;
}
.vc-file {
  font-size: 11px;
  margin: 4px 0 0;
}
.vc-pitch {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.vc-fl {
  font-size: 11px;
  color: rgba(160, 175, 200, 0.9);
}
.vc-ab {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.vc-ab-tag {
  font-size: 10px;
  min-width: 46px;
  opacity: 0.8;
}
.vc-field {
  display: block;
  margin-top: 10px;
}
.vc-actions {
  margin-top: 12px;
}
</style>
