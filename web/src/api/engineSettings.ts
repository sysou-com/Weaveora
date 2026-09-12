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
