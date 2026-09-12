import { request } from './client'
import type { EngineSettings, EngineSettingsInput } from './types'

/** GET /api/v1/me/engine-settings —— 当前用户生成引擎配置（密钥仅打码值） */
export async function getEngineSettings(): Promise<EngineSettings> {
  return request<EngineSettings>('/api/v1/me/engine-settings')
}

/** PUT /api/v1/me/engine-settings —— 更新（apiKey/password 留空=保留原值） */
export async function saveEngineSettings(input: EngineSettingsInput): Promise<EngineSettings> {
  return request<EngineSettings>('/api/v1/me/engine-settings', {
    method: 'PUT',
    body: input,
  })
}

/** P12：主动刷新模型调用参数说明（配/换模型后拉取；kind 省略=图片+视频都刷） */
export async function refreshModelSchemas(kind?: 'image' | 'video'): Promise<EngineSettings> {
  const q = kind ? `?kind=${kind}` : ''
  return request<EngineSettings>(`/api/v1/me/engine-settings/refresh-models${q}`, { method: 'POST' })
}

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
