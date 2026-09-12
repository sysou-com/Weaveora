<script setup lang="ts">
import { ArrowLeft, Save } from 'lucide-vue-next'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, useMessage } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getEngineSettings, refreshModelPreset, saveEngineSettings, saveModelPreset } from '@/api/engineSettings'
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

// P12：模型调用参数说明 + 全局参数（画质等）
const imageSchema = ref<ModelSchema | null>(null)
const imageSchemaError = ref<string | null>(null)
const gatewayRefsMax = ref<number | null>(null)
const gatewaySample = ref('')
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

/** 把当前配置存进模型库（并设为当前生效） */
async function savePreset(kind: 'image' | 'video'): Promise<void> {
  const model = kind === 'image' ? imageCloudModel.value : videoCloudModel.value
  if (!model) {
    message.warning('先填/选一个模型名')
    return
  }
  presetBusy.value = true
  try {
    // 先把界面上的参数与网关设置落库，再存条目（条目里会带上最新参数与参数说明）
    await saveEngineSettings({
      imageCloudBaseUrl: kind === 'image' ? imageCloudBaseUrl.value || null : undefined,
      imageCloudModel: kind === 'image' ? imageCloudModel.value || null : undefined,
      videoCloudModel: kind === 'video' ? videoCloudModel.value || null : undefined,
      imageParams: kind === 'image' ? imageParams.value : undefined,
      videoParams: kind === 'video' ? videoParams.value : undefined,
      gatewayRefsMax: kind === 'image' ? gatewayRefsMax.value : undefined,
      gatewaySample: kind === 'image' ? gatewaySample.value || null : undefined,
    })
    const s = await saveModelPreset({
      kind,
      baseUrl: kind === 'image' ? imageCloudBaseUrl.value : null,
      model,
      params: kind === 'image' ? imageParams.value : videoParams.value,
      gatewayRefsMax: kind === 'image' ? gatewayRefsMax.value : null,
      gatewaySample: kind === 'image' ? gatewaySample.value : null,
      apply: true,
    })
    applySettings(s)
    message.success(`已保存到模型库：${model}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    presetBusy.value = false
  }
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
      imageParams: imageParams.value,
      videoParams: videoParams.value,
      gatewayRefsMax: gatewayRefsMax.value,
      gatewaySample: gatewaySample.value || null,
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
            <NButton size="small" :loading="presetBusy" data-testid="btn-save-model"
                     @click="savePreset('image')">保存到模型库</NButton>
            <NButton size="small" secondary :loading="presetBusy" data-testid="btn-refresh-model"
                     @click="refreshPreset('image')">刷新参数说明</NButton>
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
          <div class="gw-row">
            <span class="gw-label">参考图上限（张）</span>
            <NInputNumber v-model:value="gatewayRefsMax" :min="0" :max="50" size="small"
                          style="width: 130px" placeholder="如 14" />
            <span class="gw-note text-secondary">超出会被自动裁剪并告警；0/空 = 用内置默认</span>
          </div>

          <!-- P12：网关没有逐参数规范（方舟只给模态/任务类型），让用户粘贴示例来自动识别字段 -->
          <p class="gw-text">
            网关（如火山方舟）只提供模型列表（模态 / 任务类型），<b>没有逐参数规范</b>，所以无法自动获取参数。
            粘一段<b>示例请求</b>（curl 或 JSON body）→ 自动识别参考图字段、尺寸字段等参数名。
          </p>
          <NInput v-model:value="gatewaySample" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="例：curl https://ark.cn-beijing.volces.com/api/v3/images/generations -H 'Authorization: Bearer …' -d '{&quot;model&quot;:&quot;doubao-seedream-5-0-260128&quot;,&quot;prompt&quot;:&quot;a cat&quot;,&quot;image&quot;:[&quot;data:image/jpeg;base64,…&quot;],&quot;size&quot;:&quot;2K&quot;,&quot;watermark&quot;:false}'" />
          <div class="gw-row">
            <NButton size="small" type="primary" :loading="saving" data-testid="btn-parse-sample"
                     @click="save">保存并解析示例</NButton>
            <span class="gw-note text-secondary">保存后自动探测网关模型信息 + 解析示例生成的参数表</span>
          </div>

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
            <NButton size="small" :loading="presetBusy" @click="savePreset('video')">保存到模型库</NButton>
            <NButton size="small" secondary :loading="presetBusy" @click="refreshPreset('video')">刷新参数说明</NButton>
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
