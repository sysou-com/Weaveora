import { request } from './client'
import type { Brief } from './types'
import { WORKSPACE_HEADER } from './projects'

/** GET /api/v1/projects/{id}/briefs —— 最新在前 */
export async function listBriefs(workspaceId: string, projectId: string): Promise<Brief[]> {
  return request<Brief[]>(`/api/v1/projects/${projectId}/briefs`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/projects/{id}/briefs —— raw_text 10–2000 字；mode 缺省沿用项目；referenceAssetIds ≤4 */
export async function createBrief(
  workspaceId: string,
  projectId: string,
  input: {
    rawText: string
    mode?: string
    constraints?: Record<string, unknown>
    referenceAssetIds?: string[]
  },
): Promise<Brief> {
  return request<Brief>(`/api/v1/projects/${projectId}/briefs`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}
