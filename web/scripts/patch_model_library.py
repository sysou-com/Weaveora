#!/usr/bin/env python3
"""P12 模型库前端补丁：模型名下拉 + 保存到库 + 刷新参数说明。"""
import io

# ---- api ----
p = "web/src/api/engineSettings.ts"
s = io.open(p, encoding="utf-8").read()
if "saveModelPreset" not in s:
    s += '''
/** P12 模型库：新增/更新一个模型条目（顺手刷新参数说明；apply=true 同时设为当前生效） */
export async function saveModelPreset(input: {
  kind: 'image' | 'video'
  baseUrl?: string | null
  model: string
  params?: Record<string, unknown> | null
  gatewayRefsMax?: number | null
  gatewaySample?: string | null
  apply?: boolean
}): Promise<EngineSettings> {
  return request<EngineSettings>('/api/v1/me/engine-settings/models', { method: 'POST', body: input })
}

/** P12 模型库：刷新某条目的参数说明 */
export async function refreshModelPreset(
  kind: 'image' | 'video',
  model: string,
  baseUrl?: string | null,
): Promise<EngineSettings> {
  const q = new URLSearchParams({ kind, model, ...(baseUrl ? { baseUrl } : {}) })
  return request<EngineSettings>(`/api/v1/me/engine-settings/models/refresh?${q}`, { method: 'POST' })
}

/** P12 模型库：删除条目 */
export async function deleteModelPreset(
  kind: 'image' | 'video',
  model: string,
  baseUrl?: string | null,
): Promise<EngineSettings> {
  const q = new URLSearchParams({ kind, model, ...(baseUrl ? { baseUrl } : {}) })
  return request<EngineSettings>(`/api/v1/me/engine-settings/models/delete?${q}`, { method: 'POST' })
}
'''
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("api ok")

# ---- types ----
p = "web/src/api/types.ts"
s = io.open(p, encoding="utf-8").read()
if "imageModelPresets" not in s:
    s = s.replace("""  /** P12：网关通道：已保存的示例请求（curl / JSON body），用于解析参数格式 */
  gatewaySample: string | null
}""", """  /** P12：网关通道：已保存的示例请求（curl / JSON body），用于解析参数格式 */
  gatewaySample: string | null
  /** P12 模型库：已配置的模型条目 */
  imageModelPresets: ModelPreset[] | null
  videoModelPresets: ModelPreset[] | null
}

/** P12 模型库条目（一个已配置过的 baseUrl + 模型 + 参数 + 参数说明） */
export interface ModelPreset {
  baseUrl: string
  model: string
  params: Record<string, unknown> | null
  schema: ModelSchema | null
  schemaAt?: string
  schemaError?: string
  gatewayRefsMax?: number
  gatewaySample?: string
  updatedAt?: string
}""", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("types ok")

# ---- view ----
p = "web/src/views/EngineSettingsView.vue"
s = io.open(p, encoding="utf-8").read()
if "onPickModel" not in s:
    s = s.replace(
        "import { getEngineSettings, saveEngineSettings } from '@/api/engineSettings'",
        "import { getEngineSettings, refreshModelPreset, saveEngineSettings, saveModelPreset } from '@/api/engineSettings'", 1)
    s = s.replace("import type { EngineKind, EngineSettings, ModelSchema } from '@/api/types'",
                  "import type { EngineKind, EngineSettings, ModelPreset, ModelSchema } from '@/api/types'", 1)
    s = s.replace("const gatewaySample = ref('')", """const gatewaySample = ref('')
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
}""", 1)
    s = s.replace("""  imageSchema.value = s.imageModelSchema ?? null""",
                  """  imagePresets.value = s.imageModelPresets ?? []
  videoPresets.value = s.videoModelPresets ?? []
  imageSchema.value = s.imageModelSchema ?? null""", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("view script ok")
else:
    print("view already patched")
