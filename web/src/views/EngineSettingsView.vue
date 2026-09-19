<script setup lang="ts">
import { ArrowLeft, Save } from 'lucide-vue-next'
import { NAlert, NButton, NDivider, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, useMessage } from 'naive-ui'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { getEngineSettings, refreshModelPreset, saveEngineSettings } from '@/api/engineSettings'
import type { EngineKind, EngineSettings, ModelPreset, ModelSchema } from '@/api/types'
import ModelSchemaPanel from '@/components/engine/ModelSchemaPanel.vue'

const router = useRouter()
const message = useMessage()

const loading = ref(true)
const saving = ref(false)
const ready = ref(false)

const imageEngine = ref<EngineKind>('gpu')
const videoEngine = ref<EngineKind>('gpu')
const imageCloudBaseUrl = ref('')
const imageCloudAuthType = ref<'api_key' | 'basic'>('api_key')
const imageCloudModel = ref('')
const imageCloudUsername = ref('')
const imageCloudApiKey = ref('')
const imageCloudPassword = ref('')
const imageCloudApiKeyMask = ref('')
const imageCloudPasswordSet = ref(false)

const videoCloudModel = ref('')
const videoCloudApiKey = ref('')
const videoCloudApiKeyMask = ref('')

const gpuServerUrl = ref('')
const gpuServerPort = ref<number | null>(null)
// GPU 服务器「最大支持分辨率」——motion 出片上限（实测：720p 在 48G 卡上要 10 分钟+还容易超时，
// 480p 只要 27~50s）。这是机器能力，换卡就改这里。
const gpuMaxResolution = ref<string>('480p')
/** **图片分辨率**（出图长边像素）：全局生效于关键帧/定妆照/参考图（与视频分辨率分开） */
const imageMaxResolution = ref<number>(1280)
const gpuMaxResOptions = [
  { label: '480p（长边 ≤832，A14B 推荐）', value: '480p' },
  { label: '720p（1280×704，慢 5~10 倍）', value: '720p' },
  { label: '1080p（很慢，显存吃紧）', value: '1080p' },
  { label: 'auto（不限制，按项目画幅）', value: 'auto' },
]

// P12：模型调用参数说明 + 全局参数（画质等）
const imageSchema = ref<ModelSchema | null>(null)
const imageSchemaError = ref<string | null>(null)
const gatewayRefsMax = ref<number | null>(null)
const gatewaySample = ref('')
/**
 * 服务地址（配音/配乐、对口型、转写、人脸）—— 换 GPU 服务器时只改这里。
 * 随任务下发给 worker（claim 响应），保存即生效，不用改 worker 脚本、不用重启。
 */
const svcTtsUrl = ref('')
const svcMusicEngine = ref('comfy')
const svcMusicUrl = ref('')
const svcMusicCkpt = ref('')
const svcLipsyncComfy = ref('')
const svcLipsyncWorkflow = ref('')
const svcLipsyncTimeout = ref<number | null>(1800)
const svcLipsyncFps = ref<number | null>(0)
const svcTranscribeUrl = ref('')
const svcFaceUrl = ref('')
const svcFaceDir = ref('')
// 整脸口型（EchoMimicV3）：喊叫/尖叫/吟唱镜替代 LatentSync；留空=用 GPU 服务器地址推导 <gpu>/talk
const svcTalkUrl = ref('')
const svcTalkJawGain = ref<number | null>(1.0)
// 文生图（本机 ComfyUI）：workflow/img2imgWorkflow 是 worker 机器上的绝对路径
const svcImageEngine = ref('comfy')
const svcImageComfy = ref('')
const svcImageWorkflow = ref('')
const svcImageImg2img = ref('')
const svcImageEdit = ref('')
const svcImageModel = ref('')
const svcImageSteps = ref<number | null>(null)
const svcImageCfg = ref<number | null>(null)
const svcImageDenoise = ref<number | null>(0.65)
const imagePresets = ref<ModelPreset[]>([])
const videoPresets = ref<ModelPreset[]>([])
const presetBusy = ref(false)

/** 模型下拉选项：已存库的模型（label 带 baseUrl 主机名便于区分） */
function hostOf(u: string): string {
  try {
    return new URL(u).host
  } catch {
    return u || ''
  }
}
const imageModelOptions = computed(() => {
  const opts = imagePresets.value.map((p) => ({
    label: `${p.model}${p.baseUrl ? ' · ' + hostOf(p.baseUrl) : ''}`,
    value: p.model,
  }))
  if (imageCloudModel.value && !opts.some((o) => o.value === imageCloudModel.value)) {
    opts.unshift({ label: imageCloudModel.value, value: imageCloudModel.value })
  }
  return opts
})
const videoModelOptions = computed(() => {
  const opts = videoPresets.value.map((p) => ({ label: p.model, value: p.model }))
  if (videoCloudModel.value && !opts.some((o) => o.value === videoCloudModel.value)) {
    opts.unshift({ label: videoCloudModel.value, value: videoCloudModel.value })
  }
  return opts
})

/** 选中库里的模型 → 回填 baseUrl/模型/参数/上限/示例（界面立刻展示该模型的参数） */
function onPickModel(kind: 'image' | 'video', model: string): void {
  const list = kind === 'image' ? imagePresets.value : videoPresets.value
  const p = list.find((x) => x.model === model)
  if (!p) return
  if (kind === 'image') {
    imageCloudModel.value = p.model
    if (p.baseUrl) imageCloudBaseUrl.value = p.baseUrl
    imageParams.value = (p.params ?? {}) as Record<string, unknown>
    imageSchema.value = p.schema ?? null
    gatewayRefsMax.value = p.gatewayRefsMax ?? null
    gatewaySample.value = p.gatewaySample ?? ''
    imageSchemaError.value = p.schemaError || null
  } else {
    videoCloudModel.value = p.model
    videoParams.value = (p.params ?? {}) as Record<string, unknown>
    videoSchema.value = p.schema ?? null
  }
  message.success(`已载入模型「${p.model}」的配置与参数说明`)
}

/** 刷新（重新解析/拉取）某模型的参数说明 */
async function refreshPreset(kind: 'image' | 'video'): Promise<void> {
  const model = kind === 'image' ? imageCloudModel.value : videoCloudModel.value
  if (!model) {
    message.warning('先填/选一个模型名')
    return
  }
  presetBusy.value = true
  try {
    const s = await refreshModelPreset(kind, model, kind === 'image' ? imageCloudBaseUrl.value : null)
    applySettings(s)
    const sch = kind === 'image' ? s.imageModelSchema : s.videoModelSchema
    const refs = (sch?.mapping as Record<string, unknown> | undefined)?.refs
    message.success(`参数说明已更新：${sch?.params?.length ?? 0} 个参数，参考图字段 = ${refs || '（未识别）'}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '刷新失败')
  } finally {
    presetBusy.value = false
  }
}
/** 网关通道（填了 BaseURL）= 非 Replicate，无法自动拉参数说明 */
const isGateway = computed(() => (imageCloudBaseUrl.value ?? '').trim().startsWith('http'))
const videoSchema = ref<ModelSchema | null>(null)
const imageParams = ref<Record<string, unknown>>({})
const videoParams = ref<Record<string, unknown>>({})

// ---- 自托管 motion 档位（Wan2.2 I2V-A14B 双专家）----------------------------------
// 这些键与后端 api/…/EngineSettingsService.MOTION_KEYS 白名单一致；保存后随任务下发为 services.motion。
const MOTION_KEYS = [
  'preset', 'steps', 'switch_step', 'cfg', 'cfg_high', 'cfg_low',
  'lora_high', 'lora_low', 'lora_high_name', 'lora_low_name', 'shift',
  'sampler_name', 'scheduler', 'model_high', 'model_low', 'mode', 'dual',
  'width', 'height', 'frames', 'fps',
  'presetSnapshots',   // ★ 档位参数记忆（每个 preset 存一份自己的参数快照）
]
const motionPresetOptions = ['draft', 'balanced', 'motion', 'hero', 'full'].map((v) => ({ label: v, value: v }))

// ★ 档位参数记忆（2026-09-18 用户口径）：“调整档位时把当前档参数保存下来，下次切回去时用它自动填充覆盖”。
//   存在 video_params.presetSnapshots 里（随引擎配置入库 → 跨设备/手机端也有效）。
//   键与后端 MOTION_KEYS 对齐（preset 本身不存）。
const MOTION_SNAP_KEYS = [
  'steps', 'switch_step', 'cfg', 'cfg_high', 'cfg_low',
  'lora_high', 'lora_low', 'lora_high_name', 'lora_low_name', 'shift',
  'sampler_name', 'scheduler', 'model_high', 'model_low', 'mode', 'dual',
  'resolution', 'frames', 'fps', 'width', 'height',
]
const lastPreset = ref<string>('')
function presetSnapshots(): Record<string, Record<string, unknown>> {
  // ★ 2026-09-20 修：后端白名单会把键名归一成 snake_case（EngineSettingsService.snake()）→ 真正落库的是
  //   `preset_snapshots`；旧代码只读 camel `presetSnapshots` ⇒ 永远读不到（切回旧档总是提示「该档还没存过参数」）。
  //   两个名字都认，写回时统一用 snake。
  const src = videoParams.value
  const v = src.preset_snapshots ?? src.presetSnapshots
  return v && typeof v === 'object' ? (v as Record<string, Record<string, unknown>>) : {}
}

/** 切档：先把“当前档”的参数存进快照，若目标档有快照则**覆盖填充**。 */
function onPresetChange(nextRaw: string | null): void {
  const next = nextRaw ?? ''
  const prev = lastPreset.value
  const snaps = { ...presetSnapshots() }
  if (prev && prev !== next) {
    const snap: Record<string, unknown> = {}
    for (const k of MOTION_SNAP_KEYS) {
      if (videoParams.value[k] !== undefined) snap[k] = videoParams.value[k]
    }
    snaps[prev] = snap
  }
  const target = next ? snaps[next] : undefined
  const merged: Record<string, unknown> = { ...(videoParams.value ?? {}), preset_snapshots: snaps }
  delete merged.presetSnapshots   // 统一用后端认识的 snake 键，避免两份并存
  if (next) merged.preset = next
  else delete merged.preset
  if (target) {
    for (const [k, v] of Object.entries(target)) merged[k] = v
    message.info(`已切到「${next}」档：用你上次保存的参数填充（${Object.keys(target).length} 项）`, { duration: 3500 })
  } else if (next) {
    message.info(`已切到「${next}」档：该档还没存过参数（保留当前值）`, { duration: 3500 })
  }
  videoParams.value = merged
  lastPreset.value = next
  motionJson.value = JSON.stringify(pickMotion(merged))
}
// 分辨率：A14B 的甜点是 480p（快 5~10 倍、不会把 48G 卡跑到换入换出）；720p = 原分辨率（很慢）
const motionResOptions = [
  { label: '480p（推荐：长边 832）', value: '480p' },
  { label: '720p（原分辨率，很慢）', value: '720p' },
]
// ★ 图片分辨率（出图长边像素，2026-09-18）：全局生效于**所有出图**（关键帧/定妆照/参考图）。
//   与视频分辨率分开：出图（Qwen-Image）与出视频（Wan2.2 I2V）是两条独立链路，性价比拐点完全不同。
//   尺寸 = 画幅基础尺寸等比放大到该长边（16:9 → 1280×704 / 1920×1056 / 2560×1408），32 对齐。
// ★ 2026-09-20 新增 1392 档：官方工作预算就是 ~1MP（ComfyUI 文档「缩到 one million pixels」）；
//   实测同 prompt/同参考/同 seed：2560×1408→561s、1664×928→350s、1392×752→305s，且 2K 那档会出
//   上下纯黑带（3.6MP 是官方预算的 3.4 倍）。1392 档 16:9 实际出 1408×768（1.08MP）。
const imageResOptions = [
  { label: '1280（16:9 → 1280×704，默认）', value: 1280 },
  { label: '1392（≈1MP：16:9 → 1408×768）', value: 1392 },
  { label: '1664（16:9 → 1664×928，Qwen 官方训练桶，推荐）', value: 1664 },
  { label: '1920（16:9 → 1920×1056）', value: 1920 },
  { label: '2560（16:9 → 2560×1408，≈2K，慢且易出黑边）', value: 2560 },
]
const motionJson = ref('')

function numOf(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/** 写/删单个 motion 键（空值 = 删掉该键，回到档位默认）。 */
function mSet(key: string, v: unknown): void {
  const next = { ...(videoParams.value ?? {}) }
  if (v === null || v === undefined || v === '') delete next[key]
  else next[key] = v
  videoParams.value = next
}

/** 只取白名单键（给 JSON 文本框回显）。 */
function pickMotion(v: Record<string, unknown> | null | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const src = v ?? {}
  for (const k of MOTION_KEYS) {
    const val = src[k]
    if (val !== undefined && val !== null && val !== '') out[k] = val
  }
  return out
}

watch(
  videoParams,
  (v) => { motionJson.value = JSON.stringify(pickMotion(v)) },
  { immediate: true, deep: true },
)

/** JSON 文本框失焦时合并回 videoParams（只认白名单键，其余丢弃并提示）。 */
function applyMotionJson(): void {
  let parsed: Record<string, unknown>
  const raw = (motionJson.value ?? '').trim()
  try {
    parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {}
  } catch {
    message.error('motion JSON 解析失败，请检查格式')
    return
  }
  const next = { ...(videoParams.value ?? {}) }
  for (const k of MOTION_KEYS) delete next[k]
  let ignored = 0
  for (const [k, val] of Object.entries(parsed)) {
    if (MOTION_KEYS.includes(k)) next[k] = val
    else ignored += 1
  }
  videoParams.value = next
  message.success(ignored ? `已更新 motion 档位（忽略 ${ignored} 个非 motion 键）` : '已更新 motion 档位（保存后随任务下发）')
}

/** 后端返回的配置 → 回填本地（保存/刷新后共用） */
function applySettings(s: EngineSettings): void {
  imagePresets.value = s.imageModelPresets ?? []
  videoPresets.value = s.videoModelPresets ?? []
  imageSchema.value = s.imageModelSchema ?? null
  imageSchemaError.value = s.imageModelSchemaError ?? null
  gatewayRefsMax.value = s.gatewayRefsMax ?? null
  gatewaySample.value = s.gatewaySample ?? ''

  videoSchema.value = s.videoModelSchema ?? null
  imageParams.value = (s.imageParams ?? {}) as Record<string, unknown>
  videoParams.value = (s.videoParams ?? {}) as Record<string, unknown>
  lastPreset.value = String((videoParams.value.preset as string) ?? '')
  imageCloudModel.value = s.imageCloudModel ?? ''
  videoCloudModel.value = s.videoCloudModel ?? ''
}

const engineOptions = [
  { label: '云 API（配置下方云服务）', value: 'cloud' },
  { label: 'GPU 服务器（自有引擎）', value: 'gpu' },
]
const authOptions = [
  { label: 'API Key（Bearer）', value: 'api_key' },
  { label: '账号 + 密码（Basic）', value: 'basic' },
]

const cloudActive = computed(() => imageEngine.value === 'cloud' || videoEngine.value === 'cloud')

async function load(): Promise<void> {
  loading.value = true
  try {
    const s = await getEngineSettings()
    imageEngine.value = s.imageEngine
    videoEngine.value = s.videoEngine
    imageCloudBaseUrl.value = s.imageCloudBaseUrl ?? ''
    imageCloudAuthType.value = s.imageCloudAuthType ?? 'api_key'
    imageCloudModel.value = s.imageCloudModel ?? ''
    imageCloudUsername.value = s.imageCloudUsername ?? ''
    imageCloudApiKeyMask.value = s.imageCloudApiKeyMask
    imageCloudPasswordSet.value = s.imageCloudPasswordSet
    videoCloudModel.value = s.videoCloudModel ?? ''
    videoCloudApiKeyMask.value = s.videoCloudApiKeyMask
    gpuServerUrl.value = s.gpuServerUrl ?? ''
    gpuServerPort.value = s.gpuServerPort
    gpuMaxResolution.value = s.gpuMaxResolution || '480p'
    imageMaxResolution.value = s.imageMaxResolution ?? 1280
    const sv = s.services ?? {}
    svcTtsUrl.value = sv.tts?.url ?? ''
    svcMusicEngine.value = sv.music?.engine ?? 'comfy'
    svcMusicUrl.value = sv.music?.url ?? ''
    svcMusicCkpt.value = sv.music?.ckpt ?? ''
    svcLipsyncComfy.value = sv.lipsync?.comfyUrl ?? ''
    svcLipsyncWorkflow.value = sv.lipsync?.workflow ?? ''
    svcLipsyncTimeout.value = sv.lipsync?.timeout ?? 1800
    svcLipsyncFps.value = sv.lipsync?.fps ?? 0
    svcTranscribeUrl.value = sv.transcribe?.url ?? ''
    svcFaceUrl.value = sv.face?.url ?? ''
    svcFaceDir.value = sv.face?.latentsyncDir ?? ''
    svcTalkUrl.value = sv.talk?.url ?? ''
    svcTalkJawGain.value = sv.talk?.jawGain ?? 1.0
    svcImageEngine.value = sv.image?.engine ?? 'comfy'
    svcImageComfy.value = sv.image?.comfyUrl ?? ''
    svcImageWorkflow.value = sv.image?.workflow ?? ''
    svcImageImg2img.value = sv.image?.img2imgWorkflow ?? ''
    svcImageEdit.value = sv.image?.editWorkflow ?? ''
    svcImageModel.value = sv.image?.model ?? ''
    svcImageSteps.value = sv.image?.steps ?? null
    svcImageCfg.value = sv.image?.cfg ?? null
    svcImageDenoise.value = sv.image?.denoise ?? 0.65
    applySettings(s)
    ready.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '配置读取失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const s = await saveEngineSettings({
      imageEngine: imageEngine.value,
      videoEngine: videoEngine.value,
      imageCloudBaseUrl: imageCloudBaseUrl.value || null,
      imageCloudAuthType: imageCloudAuthType.value,
      imageCloudApiKey: imageCloudApiKey.value || undefined,
      imageCloudUsername: imageCloudUsername.value || null,
      imageCloudPassword: imageCloudPassword.value || undefined,
      imageCloudModel: imageCloudModel.value || null,
      videoCloudApiKey: videoCloudApiKey.value || undefined,
      videoCloudModel: videoCloudModel.value || null,
      gpuServerUrl: gpuServerUrl.value || null,
      gpuServerPort: gpuServerPort.value,
      gpuMaxResolution: gpuMaxResolution.value,
      imageMaxResolution: imageMaxResolution.value,
      imageParams: imageParams.value,
      videoParams: videoParams.value,
      gatewayRefsMax: gatewayRefsMax.value,
      gatewaySample: gatewaySample.value || null,
      services: {
        tts: { url: svcTtsUrl.value || null },
        music: { engine: svcMusicEngine.value, url: svcMusicUrl.value || null, ckpt: svcMusicCkpt.value || null },
        lipsync: {
          comfyUrl: svcLipsyncComfy.value || null,
          workflow: svcLipsyncWorkflow.value || null,
          timeout: svcLipsyncTimeout.value,
          fps: svcLipsyncFps.value,
        },
        transcribe: { url: svcTranscribeUrl.value || null },
        face: { url: svcFaceUrl.value || null, latentsyncDir: svcFaceDir.value || null },
        talk: { url: svcTalkUrl.value || null, enabled: true, jawGain: svcTalkJawGain.value ?? 1.0 },
        image: {
          engine: svcImageEngine.value,
          comfyUrl: svcImageComfy.value || null,
          workflow: svcImageWorkflow.value || null,
          img2imgWorkflow: svcImageImg2img.value || null,
          editWorkflow: svcImageEdit.value || null,
          model: svcImageModel.value || null,
          steps: svcImageSteps.value,
          cfg: svcImageCfg.value,
          denoise: svcImageDenoise.value,
        },
      },
    })
    message.success('已保存生成引擎配置')
    imageEngine.value = s.imageEngine
    videoEngine.value = s.videoEngine
    imageCloudApiKeyMask.value = s.imageCloudApiKeyMask
    imageCloudPasswordSet.value = s.imageCloudPasswordSet
    videoCloudApiKeyMask.value = s.videoCloudApiKeyMask
    imageCloudApiKey.value = ''
    imageCloudPassword.value = ''
    videoCloudApiKey.value = ''
    applySettings(s)   // 换模型后后端已主动拉取参数说明 → 立即展示
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="router.back()">
      <NIcon size="15"><ArrowLeft /></NIcon><span>返回</span>
    </button>
    <h1 class="title font-display">生成引擎配置</h1>
    <p class="sub text-secondary">图片与视频可分别选择「云 API」或「GPU 服务器」；凭据仅密文保存，页面不回显明文。</p>

    <div v-if="!ready" class="placeholder text-secondary">加载中…</div>
    <NForm v-else class="form" label-placement="top" :show-feedback="false">
      <section class="card">
        <h2 class="card-title">① 引擎开关</h2>
        <NFormItem label="图片生成引擎">
          <NRadioGroup v-model:value="imageEngine" data-testid="img-engine">
            <NRadio v-for="o in engineOptions" :key="o.value" :value="o.value" :label="o.label" />
          </NRadioGroup>
        </NFormItem>
        <NFormItem label="视频生成引擎">
          <NRadioGroup v-model:value="videoEngine" data-testid="vid-engine">
            <NRadio v-for="o in engineOptions" :key="o.value" :value="o.value" :label="o.label" />
          </NRadioGroup>
        </NFormItem>
      </section>

      <section v-if="imageEngine === 'cloud'" class="card">
        <h2 class="card-title">② 图片云 API（OpenAI Images 兼容）</h2>
        <NAlert type="info" :show-icon="false" class="hint">
          两个都行：<b>基址</b>（如 <code>https://ark.cn-beijing.volces.com/api/v3</code> → 自动补
          <code>/images/generations</code>）或 <b>完整端点</b>
          （如 <code>https://ark.cn-beijing.volces.com/api/v3/images/generations</code>）。
          也兼容 <code>{base}/v1/images/generations</code> 这类 OpenAI 风格网关。
        </NAlert>
        <NFormItem label="Base URL">
          <NInput v-model:value="imageCloudBaseUrl" placeholder="https://…" data-testid="img-base" />
        </NFormItem>
        <NFormItem label="认证方式">
          <NSelect v-model:value="imageCloudAuthType" :options="authOptions" />
        </NFormItem>
        <NFormItem v-if="imageCloudAuthType === 'api_key'" :label="imageCloudApiKeyMask ? `API Key（已设置 ${imageCloudApiKeyMask}，重填覆盖）` : 'API Key'">
          <NInput v-model:value="imageCloudApiKey" type="password" show-password-on="click" placeholder="sk-…" data-testid="img-key" />
        </NFormItem>
        <template v-else>
          <NFormItem label="账号">
            <NInput v-model:value="imageCloudUsername" placeholder="账号" />
          </NFormItem>
          <NFormItem :label="imageCloudPasswordSet ? '密码（已设置，重填覆盖）' : '密码'">
            <NInput v-model:value="imageCloudPassword" type="password" show-password-on="click" />
          </NFormItem>
        </template>
        <NFormItem label="模型名（可从模型库选择）">
          <div class="mdl-row">
            <NSelect
              :value="imageCloudModel"
              :options="imageModelOptions"
              size="small"
              class="mdl-select"
              filterable
              tag
              clearable
              placeholder="从库中选择，或输入/粘贴模型名"
              data-testid="img-model"
              @update:value="(v: string | null) => { imageCloudModel = v ?? ''; if (v) onPickModel('image', v) }"
            />
            <NButton size="small" secondary :loading="presetBusy" data-testid="btn-parse-sample"
                     title="把示例请求里的字段解析成参数模板（保存配置时自动入库）"
                     @click="refreshPreset('image')">解析参数</NButton>
          </div>
          <p v-if="imagePresets.length" class="mdl-hint text-secondary">
            库里已存：{{ imagePresets.map((p) => p.model).join('、') }}
          </p>
        </NFormItem>
        <!-- P12：网关通道（方舟等）不是 Replicate，自动拉不到参数说明 → 只提示关键信息 + 让用户填上限 -->
        <div v-if="isGateway" class="gw-hint" data-testid="gateway-hint">
          <p class="gw-title font-mono">网关通道（OpenAI Images 兼容）</p>
          <p class="gw-text">
            参考图按 <code>image</code> 字段发送（单张=字符串，多张=数组，值用 data URI）；
            该通道无法自动获取模型参数说明，请按官方文档确认「参考图上限」并填写。
          </p>

          <!-- P12：网关没有逐参数规范（方舟只给模态/任务类型），让用户粘贴示例来自动识别字段 -->

          <NInput v-model:value="gatewaySample" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="例：curl https://ark.cn-beijing.volces.com/api/v3/images/generations -H 'Authorization: Bearer …' -d '{&quot;model&quot;:&quot;doubao-seedream-5-0-260128&quot;,&quot;prompt&quot;:&quot;a cat&quot;,&quot;image&quot;:[&quot;data:image/jpeg;base64,…&quot;],&quot;size&quot;:&quot;2K&quot;,&quot;watermark&quot;:false}'" />
          <div class="gw-row">
            <NButton size="small" type="primary" :loading="saving" data-testid="btn-parse-sample-apply"
                     @click="save">解析示例参数</NButton>
          </div>
          <!-- 参数模板：可直接编辑值（保存配置时一起落库，供生成时填充） -->
          <ModelSchemaPanel
            v-if="imageSchema && imageSchema.params.length"
            kind="image"
            :schema="imageSchema"
            :params="imageParams"
            @update:params="(v: Record<string, unknown>) => (imageParams = v)"
            @refreshed="applySettings"
          />

          <NAlert v-if="imageSchemaError" type="warning" :show-icon="false" class="gw-alert">
            {{ imageSchemaError }}
          </NAlert>
          <div v-if="imageSchema" class="gw-row">
            <span class="gw-note text-secondary">
              已识别：参考图字段 <code>{{ (imageSchema.mapping as Record<string, unknown>).refs || '（未识别）' }}</code>
              （{{ (imageSchema.mapping as Record<string, unknown>).refsIsArray ? '数组' : '单张' }}）
              · 参数 {{ imageSchema.params.length }} 个
              <template v-if="imageSchema.gatewayProbe">
                · 网关探测：{{ (imageSchema.gatewayProbe as Record<string, unknown>).modelExists ? '模型存在' : '未找到模型' }}
                <template v-if="(imageSchema.gatewayProbe as Record<string, unknown>).note">
                  （{{ (imageSchema.gatewayProbe as Record<string, unknown>).note }}）
                </template>
              </template>
            </span>
          </div>
          <ul v-if="imageSchema?.notes?.length" class="gw-notes">
            <li v-for="n in imageSchema.notes" :key="n">{{ n }}</li>
          </ul>
        </div>
        <!-- P12：该模型认哪些参数（参考图字段名是关键）、以及可全局调整的画质等 -->
        <ModelSchemaPanel
          v-else
          kind="image"
          :schema="imageSchema"
          :params="imageParams"
          @update:params="(v: Record<string, unknown>) => (imageParams = v)"
          @refreshed="applySettings"
        />
      </section>

      <section v-if="videoEngine === 'cloud'" class="card">
        <h2 class="card-title">③ 视频云 API（Replicate 通道）</h2>
        <NAlert type="info" :show-icon="false" class="hint">
          通过 Replicate 调用视频模型（Kling / Minimax 等）。API Token 密文保存，页面不回显明文。
        </NAlert>
        <NFormItem :label="videoCloudApiKeyMask ? `Replicate Token（已设置 ${videoCloudApiKeyMask}，重填覆盖）` : 'Replicate Token'">
          <NInput v-model:value="videoCloudApiKey" type="password" show-password-on="click" placeholder="r8_…" data-testid="vid-key" />
        </NFormItem>
        <NFormItem label="视频模型（可从模型库选择）">
          <div class="mdl-row">
            <NSelect
              :value="videoCloudModel"
              :options="videoModelOptions"
              size="small"
              class="mdl-select"
              filterable
              tag
              clearable
              placeholder="从库中选择，或输入/粘贴模型名"
              data-testid="vid-model"
              @update:value="(v: string | null) => { videoCloudModel = v ?? ''; if (v) onPickModel('video', v) }"
            />
            <NButton size="small" secondary :loading="presetBusy" title="解析该模型的调用参数（保存配置时自动入库）"
                     @click="refreshPreset('video')">解析参数</NButton>
          </div>
        </NFormItem>
        <ModelSchemaPanel
          kind="video"
          :schema="videoSchema"
          :params="videoParams"
          @update:params="(v: Record<string, unknown>) => (videoParams = v)"
          @refreshed="applySettings"
        />
      </section>

      <section class="card">
        <h2 class="card-title">{{ cloudActive ? '④' : '②' }} GPU 服务器</h2>
        <p class="hint text-secondary">
          配置你自备的 GPU 引擎地址（绑定/健康探测；实际生成经 Weaveora 引擎节点池的 Comfy 节点执行，支持 SDXL 出图与 Wan 视频）。
        </p>
        <div class="row">
          <NFormItem label="URL" class="grow">
            <NInput v-model:value="gpuServerUrl" placeholder="如 http://your-gpu-host 或留空使用池节点" />
          </NFormItem>
          <NFormItem label="端口">
            <NInputNumber v-model:value="gpuServerPort" :min="1" :max="65535" placeholder="8188" style="width: 130px" />
          </NFormItem>
          <NFormItem label="视频分辨率" style="width: 300px">
            <NSelect v-model:value="gpuMaxResolution" :options="gpuMaxResOptions" size="small" />
          </NFormItem>
          <NFormItem label="图片分辨率" style="width: 300px">
            <NSelect v-model:value="imageMaxResolution" :options="imageResOptions" size="small" />
          </NFormItem>
        </div>
        <p class="hint text-secondary" style="margin: -4px 0 10px">
          <b>视频分辨率</b>决定 motion 出片上限（换 GPU 卡就改这里）：实测 48G 卡上
          <b>720p/48 帧要 10 分钟以上且易超时</b>，<b>480p 只要 27~50 秒</b>；A14B 的甜点也是 480p 级。<br>
          <b>图片分辨率</b>决定所有出图（关键帧 / 定妆照 / 参考图）的长边像素，**全局生效**：
          越高越清晰（关键帧细节会带进视频底图），代价是出图更慢、更占显存。
        </p>

        <!-- 自托管 motion 档位（Wan2.2 I2V-A14B 双专家）
             为什么单独放这里：上面“视频参数”面板被 `v-if="videoEngine === 'cloud'"` 包着，
             切到自有引擎后就没有入口了 —— 而自托管恰恰最需要它（preset/lora_high 直接决定动态）。
             这些键会被后端 services.motion 白名单收下并随任务下发给 worker。 -->
        <NDivider style="margin: 6px 0 10px" />
        <p class="hint text-secondary" style="margin-bottom: 10px">
          <b>自托管 motion 档位</b>（Wan2.2 I2V-A14B 双专家）：高噪声专家负责大幅运动，
          <b>默认不蒸馏（lora_high=0）</b>；动态靠 steps / switch / cfg 调。
          保存后随任务下发（无需重启 worker）。留空 = 用 worker 默认档 <code>balanced</code>。<br>
          <b>切档会记忆参数</b>：切换时自动把当前档的参数存储，下次切回该档就用它自动填充覆盖（存在引擎配置里，手机端也生效）。
        </p>
        <div class="row" style="flex-wrap: wrap; gap: 8px">
          <NFormItem label="档位 preset" style="width: 200px">
            <NSelect
              :value="(videoParams.preset as string) ?? null"
              :options="motionPresetOptions"
              size="small"
              clearable
              placeholder="balanced"
              @update:value="(v: string | null) => onPresetChange(v)"
            />
          </NFormItem>
          <NFormItem label="步数 steps" style="width: 150px">
            <NInputNumber :value="numOf(videoParams.steps)" size="small" :min="1" :max="40"
                          placeholder="档位默认" @update:value="(v: number | null) => mSet('steps', v)" />
          </NFormItem>
          <NFormItem label="切换步 switch" style="width: 160px">
            <NInputNumber :value="numOf(videoParams.switch_step)" size="small" :min="1" :max="40"
                          placeholder="总步数折半" @update:value="(v: number | null) => mSet('switch_step', v)" />
          </NFormItem>
          <NFormItem label="cfg 高噪声" style="width: 160px">
            <NInputNumber :value="numOf(videoParams.cfg_high)" size="small" :min="0" :max="10" :step="0.5"
                          placeholder="1.0（hero=3.5）" @update:value="(v: number | null) => mSet('cfg_high', v)" />
          </NFormItem>
          <NFormItem label="cfg 低噪声" style="width: 160px">
            <NInputNumber :value="numOf(videoParams.cfg_low)" size="small" :min="0" :max="10" :step="0.5"
                          placeholder="1.0" @update:value="(v: number | null) => mSet('cfg_low', v)" />
          </NFormItem>
          <NFormItem label="LoRA 高噪声" style="width: 170px">
            <NInputNumber :value="numOf(videoParams.lora_high)" size="small" :min="0" :max="1.5" :step="0.1"
                          placeholder="0（不蒸馏，推荐）" @update:value="(v: number | null) => mSet('lora_high', v)" />
          </NFormItem>
          <NFormItem label="LoRA 低噪声" style="width: 170px">
            <NInputNumber :value="numOf(videoParams.lora_low)" size="small" :min="0" :max="1.5" :step="0.1"
                          placeholder="1.0" @update:value="(v: number | null) => mSet('lora_low', v)" />
          </NFormItem>
          <NFormItem label="分辨率" style="width: 230px">
            <NSelect
              :value="(videoParams.resolution as string) ?? '480p'"
              :options="motionResOptions"
              size="small"
              @update:value="(v: string | null) => mSet('resolution', v ?? '480p')"
            />
          </NFormItem>
          <NFormItem label="shift" style="width: 140px">
            <NInputNumber :value="numOf(videoParams.shift)" size="small" :min="0" :max="20" :step="0.5"
                          placeholder="5.0" @update:value="(v: number | null) => mSet('shift', v)" />
          </NFormItem>
        </div>
        <NFormItem label="高级：直接编辑 motion JSON（白名单键，逗号分隔的任一子集即可）">
          <NInput
            v-model:value="motionJson"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder='如 {"preset":"motion","lora_high":0}'
            @blur="applyMotionJson"
          />
        </NFormItem>
      </section>

      <section class="card" data-testid="svc-card">
        <h2 class="card-title">{{ cloudActive ? '⑤' : '③' }} 服务地址（换 GPU 机器只改这里）</h2>
        <p class="mdl-hint text-secondary">
          ★ <b>GPU 公网 IP/端口会变</b>：只改上面的「GPU 服务器地址 + 端口」，下面这些<b>留空即自动跟随</b>
          （后端会推导成 <code>&lt;gpu&gt;:8001</code>（ComfyUI）、<code>&lt;gpu&gt;/audio</code>（配音/转写）、
          <code>&lt;gpu&gt;/talk</code>（整脸口型））。<b>不要</b>把 IP 写进 worker 脚本、部署脚本或代码。
        </p>
        <p class="mdl-hint text-secondary">
          填在这里后随任务下发给 worker，<b>保存即生效</b>，不用改脚本、不用重启 worker。留空 = 用 worker 机器上的默认值。
        </p>

        <NFormItem label="配音（TTS）服务地址">
          <NInput v-model:value="svcTtsUrl" placeholder="如 http://127.0.0.1:8091（留空=默认）" />
        </NFormItem>

        <div class="mdl-row">
          <NFormItem label="配乐引擎" style="width: 190px">
            <NSelect v-model:value="svcMusicEngine" :options="[
              { label: 'comfy（本机 ComfyUI 里的 ACE-Step）', value: 'comfy' },
              { label: 'http（独立音乐服务）', value: 'http' },
            ]" />
          </NFormItem>
          <NFormItem label="配乐服务地址" class="grow">
            <NInput v-model:value="svcMusicUrl" placeholder="如 http://127.0.0.1:8092（留空=默认）" />
          </NFormItem>
        </div>
        <NFormItem label="配乐权重名（engine=comfy 时用）">
          <NInput v-model:value="svcMusicCkpt" placeholder="如 ace_step_1.5_turbo_aio.safetensors（留空=默认）" />
        </NFormItem>

        <NFormItem label="对口型 ComfyUI 地址">
          <NInput v-model:value="svcLipsyncComfy" placeholder="如 http://127.0.0.1:8188（留空=默认/用上面的 GPU 服务器）" />
        </NFormItem>
        <NFormItem label="对口型工作流（API 格式 JSON 的绝对路径，装在哪台机就填哪台的路径）">
          <NInput v-model:value="svcLipsyncWorkflow" placeholder="如 D:\ComfyUI\_setup\lipsync_workflow_api.json" />
        </NFormItem>
        <div class="mdl-row">
          <NFormItem label="对口型超时（秒）" style="width: 200px">
            <NInputNumber v-model:value="svcLipsyncTimeout" :min="60" :max="14400" placeholder="1800" style="width: 150px" />
          </NFormItem>
          <NFormItem label="输出帧率（0=跟随源片）" class="grow">
            <NInputNumber v-model:value="svcLipsyncFps" :min="0" :max="60" placeholder="0" style="width: 150px" />
          </NFormItem>
        </div>

        <NFormItem label="转写（语音转文字）服务地址">
          <NInput v-model:value="svcTranscribeUrl" placeholder="如 http://127.0.0.1:8091（留空=默认；通常与配音同一台机）" />
        </NFormItem>

        <NFormItem label="人脸服务地址（留空 = 用 worker 本机 insightface）">
          <NInput v-model:value="svcFaceUrl" placeholder="如 http://127.0.0.1:8093（deploy/face/face_server.py）" />
        </NFormItem>
        <NFormItem label="人脸/LatentSync 节点目录（本机人脸检测用，可留空）">
          <NInput v-model:value="svcFaceDir" placeholder="如 D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper" />
        </NFormItem>

        <NDivider />
        <p class="mdl-hint text-secondary">
          <b>整脸口型（EchoMimicV3 / jaw-lip）</b>：喊叫、尖叫、吟唱这类「嘴大张」镜用它替代 LatentSync，
          跑在 GPU 机的 <code>talk_server.py</code>（默认 :8094，网关路径 <code>/talk</code>）。留空 = 自动用 GPU 服务器地址推导。
        </p>
        <div class="mdl-row">
          <NFormItem label="整脸口型服务地址" class="grow">
            <NInput v-model:value="svcTalkUrl" placeholder="留空=自动 &lt;GPU 服务器&gt;/talk（如 http://your-gpu-host:10558/talk）" />
          </NFormItem>
          <NFormItem label="下颌曲线增益 jaw_gain" style="width: 230px">
            <NInputNumber v-model:value="svcTalkJawGain" :min="0" :max="2" :step="0.05" placeholder="1.0" style="width: 150px" />
          </NFormItem>
        </div>
        <p class="mdl-hint text-secondary">
          <code>jaw_gain=1.0</code> 零改动（原生 EchoMimic 输出）；<code>1.25</code> 是增强档（喊叫时下半脸张得更开）。
        </p>

        <NDivider />
        <p class="mdl-hint text-secondary">
          <b>文生图（本机 ComfyUI）</b>：走 GPU 机上的 ComfyUI（当前 Qwen-Image + Lightning 8 步，实测 1344×768 ≈ 40s/张）。
          两个工作流是 <b>worker 机器上的绝对路径</b>（装在哪台机就填哪台的）；留空 = 用 worker 自带默认（老 SDXL 路线）。
        </p>
        <div class="mdl-row">
          <NFormItem label="出图引擎" style="width: 220px">
            <NSelect v-model:value="svcImageEngine" :options="[
              { label: 'comfy（本机 ComfyUI 工作流）', value: 'comfy' },
              { label: '内置（worker 自带 SDXL）', value: 'builtin' },
            ]" />
          </NFormItem>
          <NFormItem label="ComfyUI 地址" class="grow">
            <NInput v-model:value="svcImageComfy" placeholder="留空=自动 &lt;GPU 服务器&gt;:8001" />
          </NFormItem>
        </div>
        <NFormItem label="文生图工作流（API 格式 JSON 绝对路径）">
          <NInput v-model:value="svcImageWorkflow" placeholder="如 /opt/weaveora/qwen_image_txt2img_api.json" />
        </NFormItem>
        <NFormItem label="参考图锚定工作流 editWorkflow（Qwen-Image-Edit；填了就优先走它）">
          <NInput v-model:value="svcImageEdit" placeholder="如 /opt/weaveora/workflows/qwen_image_edit_api.json" />
        </NFormItem>
        <NFormItem label="图生图工作流（关键帧当底图；可留空）">
          <NInput v-model:value="svcImageImg2img" placeholder="如 /opt/weaveora/qwen_image_img2img_api.json" />
        </NFormItem>
        <p class="mdl-hint text-secondary" style="margin: -4px 0 8px">
          优先级：有参考图且有 editWorkflow → <strong>Edit（参考图锚定）</strong>；否则 img2img；再否则 txt2img。
        </p>
        <div class="mdl-row">
          <NFormItem label="主模型名（留空=用工作流里的）" class="grow">
            <NInput v-model:value="svcImageModel" placeholder="如 qwen_image_fp8_e4m3fn.safetensors" />
          </NFormItem>
          <NFormItem label="步数 steps" style="width: 160px">
            <NInputNumber v-model:value="svcImageSteps" :min="1" :max="60" placeholder="40" style="width: 110px" />
          </NFormItem>
          <NFormItem label="cfg（提示词遵从度）" style="width: 200px">
            <NInputNumber v-model:value="svcImageCfg" :min="0" :max="12" :step="0.5" placeholder="4.0 / 留空=工作流默认" style="width: 140px" />
          </NFormItem>
          <NFormItem label="图生图 denoise" style="width: 200px">
            <NInputNumber v-model:value="svcImageDenoise" :min="0.1" :max="1" :step="0.05" placeholder="0.65" style="width: 110px" />
          </NFormItem>
        </div>
      </section>

      <div class="actions">
        <NButton type="primary" size="large" :loading="saving" data-testid="save-engine" @click="save">
          <template #icon><NIcon><Save :size="16" /></NIcon></template>
          保存配置
        </NButton>
      </div>
    </NForm>
  </div>
</template>

<style scoped>
.mdl-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mdl-select { min-width: 260px; flex: 1 1 260px; }
.mdl-hint { margin: 6px 0 0; font-size: 11.5px; }

.page {
  max-width: 760px;
  margin: 0 auto;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: var(--wv-text-2);
  cursor: pointer;
  font-size: 13px;
  margin-bottom: 10px;
  padding: 4px 2px;
}
.title {
  margin: 0 0 4px;
  font-size: 26px;
}
.sub {
  font-size: 13px;
  margin: 0 0 18px;
  line-height: 1.7;
}
.placeholder {
  padding: 60px 0;
  text-align: center;
}
.card {
  border: 1px solid var(--wv-divider);
  background: var(--wv-surface);
  border-radius: 14px;
  padding: 18px 20px;
  margin-bottom: 16px;
}
.card-title {
  font-size: 15px;
  margin: 0 0 12px;
  color: var(--wv-accent-text);
}
.hint {
  font-size: 12.5px;
  margin-bottom: 12px;
  line-height: 1.7;
}
.hint code {
  font-family: var(--wv-font-mono);
  background: var(--wv-surface-raised);
  padding: 0 4px;
  border-radius: 4px;
}
.row {
  display: flex;
  gap: 12px;
}
.grow {
  flex: 1;
}
.actions {
  display: flex;
  justify-content: flex-end;
}
@media (max-width: 640px) {
  .row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
