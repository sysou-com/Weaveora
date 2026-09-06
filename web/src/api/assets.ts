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
