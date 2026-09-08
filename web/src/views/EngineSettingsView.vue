<script setup lang="ts">
import { ArrowLeft, Save } from 'lucide-vue-next'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, useMessage } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getEngineSettings, saveEngineSettings } from '@/api/engineSettings'
import type { EngineKind } from '@/api/types'

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
          示例 BaseURL：<code>https://your-gateway.example.com</code>（将调用 <code>{base}/v1/images/generations</code>）
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
        <NFormItem label="模型名">
          <NInput v-model:value="imageCloudModel" placeholder="如 dall-e-3 / sd-xl / 网关模型名（留空用默认）" />
        </NFormItem>
      </section>

      <section v-if="videoEngine === 'cloud'" class="card">
        <h2 class="card-title">③ 视频云 API（Replicate 通道）</h2>
        <NAlert type="info" :show-icon="false" class="hint">
          通过 Replicate 调用视频模型（Kling / Minimax 等）。API Token 密文保存，页面不回显明文。
        </NAlert>
        <NFormItem :label="videoCloudApiKeyMask ? `Replicate Token（已设置 ${videoCloudApiKeyMask}，重填覆盖）` : 'Replicate Token'">
          <NInput v-model:value="videoCloudApiKey" type="password" show-password-on="click" placeholder="r8_…" data-testid="vid-key" />
        </NFormItem>
        <NFormItem label="视频模型">
          <NInput v-model:value="videoCloudModel" placeholder="如 minimax/video-01（留空用默认）" />
        </NFormItem>
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
