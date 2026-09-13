<script setup lang="ts">
import { ArrowLeft, Save } from 'lucide-vue-next'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, useMessage } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
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
        </div>
      </section>

      <section class="card" data-testid="svc-card">
        <h2 class="card-title">{{ cloudActive ? '⑤' : '③' }} 服务地址（换 GPU 机器只改这里）</h2>
        <p class="mdl-hint text-secondary">
          这些服务原先只由 worker 机器的环境变量决定（TTS :8091 / 音乐 :8092 / ComfyUI :8188 / 对口型工作流路径）。
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
