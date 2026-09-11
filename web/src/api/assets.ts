import { request, API_BASE } from './client'
import { clearTokens, loadTokens } from './session'
import { WORKSPACE_HEADER } from './projects'
import type { AssetRef } from './types'

/** 401 = 会话失效 → 清 token 并发全局事件（路由守卫据此跳 /login） */
function onUnauthorized(): void {
  clearTokens()
  window.dispatchEvent(new CustomEvent('weaveora:session-expired'))
}

/** 上传参考图（W2C，multipart） */
export async function uploadReference(workspaceId: string, projectId: string, file: File): Promise<AssetRef> {
  const { accessToken } = loadTokens()
  const fd = new FormData()
  fd.append('file', file)
  const resp = await fetch(`${API_BASE}/api/v1/projects/${projectId}/assets`, {
    method: 'POST',
    headers: {
      [WORKSPACE_HEADER]: workspaceId,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: fd,
  })
  if (!resp.ok) {
    if (resp.status === 401) onUnauthorized()
    let msg = `上传失败（HTTP ${resp.status}）`
    try {
      const e = (await resp.json()) as { message?: string }
      if (e.message) msg = e.message
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as AssetRef
}

/**
 * P8：导入配音 —— 上传用户自己配好的那一段声音。
 *
 * 服务端会落成 kind=voice 的资产，并写入 line_index/at_sec/subject 快照，
 * 与生成产物同格式 —— 混音/导出无需区分来源，同一条语音也会被更新覆盖。
 */
export async function uploadVoiceLine(
  workspaceId: string,
  projectId: string,
  params: { file: File; shotNo: number; lineIndex: number; atSec: number; subject?: string },
): Promise<AssetRef> {
  const { accessToken } = loadTokens()
  const fd = new FormData()
  fd.append('file', params.file)
  fd.append('shotNo', String(params.shotNo))
  fd.append('lineIndex', String(params.lineIndex))
  fd.append('atSec', String(params.atSec))
  if (params.subject) fd.append('subject', params.subject)
  const resp = await fetch(`${API_BASE}/api/v1/projects/${projectId}/voice-lines`, {
    method: 'POST',
    headers: {
      [WORKSPACE_HEADER]: workspaceId,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: fd,
  })
  if (!resp.ok) {
    if (resp.status === 401) onUnauthorized()
    let msg = `上传失败（HTTP ${resp.status}）`
    try {
      const e = (await resp.json()) as { message?: string }
      if (e.message) msg = e.message
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as AssetRef
}

/** P9：克隆音色创建结果 */
export interface VoicePresetCreated {
  rawAssetId: string
  presetAssetId: string
  durationSec: number
  warnings: string[]
}

/**
 * P9：上传音色样本 → 服务端 ffmpeg 处理成 CosyVoice 好用的参考音（24kHz 单声道）。
 * 返回原件 + 处理后两个资产 id，前端用它们做 A/B 对比试听。
 */
export async function createVoicePreset(
  workspaceId: string,
  projectId: string,
  params: {
    file: File | Blob
    name?: string
    trimSilence: boolean
    loudnorm: boolean
    denoise: boolean
    limitLength: boolean
    pitchSemitones: number
  },
): Promise<VoicePresetCreated> {
  const { accessToken } = loadTokens()
  const fd = new FormData()
  fd.append('file', params.file, params.file instanceof File ? params.file.name : 'sample.webm')
  if (params.name) fd.append('name', params.name)
  fd.append('trimSilence', String(params.trimSilence))
  fd.append('loudnorm', String(params.loudnorm))
  fd.append('denoise', String(params.denoise))
  fd.append('limitLength', String(params.limitLength))
  fd.append('pitchSemitones', String(params.pitchSemitones))
  const resp = await fetch(`${API_BASE}/api/v1/projects/${projectId}/voice-presets`, {
    method: 'POST',
    headers: {
      [WORKSPACE_HEADER]: workspaceId,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: fd,
  })
  if (!resp.ok) {
    if (resp.status === 401) onUnauthorized()
    let msg = `样本处理失败（HTTP ${resp.status}）`
    try {
      const e = (await resp.json()) as { message?: string }
      if (e.message) msg = e.message
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as VoicePresetCreated
}

/** P9：转写样本（whisper 在 GPU 机器上），返回识别文本；失败返回空串。 */
export async function transcribeVoicePreset(
  workspaceId: string,
  projectId: string,
  assetId: string,
): Promise<string> {
  try {
    const r = await request<{ text: string }>(
      `/api/v1/projects/${projectId}/voice-presets/${assetId}/transcribe`,
      { method: 'POST', headers: { [WORKSPACE_HEADER]: workspaceId } },
    )
    return r.text ?? ''
  } catch {
    return ''
  }
}

/** P9-B：把音色样本直接当本行配音（服务端复制，不用重新上传）。 */
export async function useSampleAsLineVoice(
  workspaceId: string,
  projectId: string,
  params: { shotNo: number; lineIndex: number; atSec: number; subject?: string; srcAssetId: string },
): Promise<{ assetId: string; durationSec: number }> {
  const q = new URLSearchParams({
    srcAssetId: params.srcAssetId,
    atSec: String(params.atSec),
    ...(params.subject ? { subject: params.subject } : {}),
  })
  return request<{ assetId: string; durationSec: number }>(
    `/api/v1/projects/${projectId}/shots/${params.shotNo}/lines/${params.lineIndex}/voice-from-sample?${q}`,
    { method: 'POST', headers: { [WORKSPACE_HEADER]: workspaceId } },
  )
}

/** 项目资产列表（含参考图与 Job 产物） */
export async function listAssets(workspaceId: string, projectId: string): Promise<AssetRef[]> {
  return request<AssetRef[]>(`/api/v1/projects/${projectId}/assets`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** 下载为 Blob（用于 <img> 预览，带鉴权头） */
export async function fetchAssetBlob(workspaceId: string, assetId: string): Promise<Blob | null> {
  const { accessToken } = loadTokens()
  const resp = await fetch(`${API_BASE}/api/v1/assets/${assetId}/download`, {
    headers: {
      [WORKSPACE_HEADER]: workspaceId,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
  })
  if (!resp.ok) {
    if (resp.status === 401) onUnauthorized()
    return null
  }
  return resp.blob()
}

/** 删除所选资产（删行 + 删存储文件） */
export async function deleteAssets(
  workspaceId: string,
  projectId: string,
  assetIds: string[],
): Promise<{ deleted: number }> {
  return request<{ deleted: number }>(`/api/v1/projects/${projectId}/assets/delete`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { assetIds },
  })
}
