import { API_BASE, request } from './client'
import { loadTokens } from './session'
import { WORKSPACE_HEADER } from './projects'

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
  if (!resp.ok) return null
  return resp.blob()
}

/** 秒 → mm:ss 时间码 */
export function timecode(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}
