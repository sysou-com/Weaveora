import { API_BASE, request } from './client'
import { clearTokens, loadTokens } from './session'
import { WORKSPACE_HEADER } from './projects'
import type { AssetRef as AssetLike } from './types'

export interface ExportInfo {
  id: string
  projectId: string
  revisionId: string
  downloadUrl: string
  createdAt: string
}

/** POST /projects/{id}/exports —— 需已确认的视频 revision */
export async function createExport(workspaceId: string, projectId: string, revisionId: string): Promise<ExportInfo> {
  return request<ExportInfo>(`/api/v1/projects/${projectId}/exports`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { revisionId },
  })
}

/** 下载导出 zip（带鉴权头） */
export async function fetchExportBlob(workspaceId: string, exportId: string): Promise<Blob | null> {
  const { accessToken } = loadTokens()
  const resp = await fetch(`${API_BASE}/api/v1/exports/${exportId}/download`, {
    headers: {
      [WORKSPACE_HEADER]: workspaceId,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
  })
  if (!resp.ok) {
    if (resp.status === 401) {
      clearTokens()
      window.dispatchEvent(new CustomEvent('weaveora:session-expired'))
    }
    return null
  }
  return resp.blob()
}

/** 秒 → mm:ss 时间码 */

/** POST /projects/{id}/render —— ffmpeg 合成 master mp4（kind=master 资产）；transition: cut|fade */
export async function renderMaster(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  transition: 'cut' | 'fade' = 'fade',
): Promise<AssetLike> {
  return request<AssetLike>(`/api/v1/projects/${projectId}/render`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { revisionId, transition },
  })
}

export function timecode(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}
