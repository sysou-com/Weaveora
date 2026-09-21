import { request } from './client'
import type {
  EngineSettings,
  EngineSettingsInput,
  GpuAddressSyncInput,
  GpuAddressSyncResult,
  WorkerEnvStatus,
} from './types'

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

/**
 * 一键同步 GPU 地址：把「所有指向旧 GPU 机器的 IP:端口」换成新值（含 services 里显式填过的 URL）。
 *
 * 可选 `applyWorkerEnv=true` 时**同时**改写 worker 机器的 env（回退值）并重启 worker
 * （有 queued/running 任务时后端会拒绝重启，只回说明）。
 * 返回替换清单 + 「仍是 IP 字面量」的改漏清单 + env 结果 + 同步后的完整配置。
 */
export async function syncGpuAddress(input: GpuAddressSyncInput): Promise<GpuAddressSyncResult> {
  return request<GpuAddressSyncResult>('/api/v1/me/engine-settings/sync-address', {
    method: 'POST',
    body: input,
  })
}

/** worker 机器 env 的当前回退值（页面用来对比“页面地址 vs worker 回退值”） */
export async function getWorkerEnvStatus(): Promise<WorkerEnvStatus> {
  return request<WorkerEnvStatus>('/api/v1/me/engine-settings/worker-env')
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
