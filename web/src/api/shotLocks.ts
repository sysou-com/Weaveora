import { request } from './client'
import { WORKSPACE_HEADER } from './projects'

/**
 * P12 分镜封版（shot-locks）。
 *
 * 语义：某镜的资源（关键帧 / motion / 配音）已满足要求 → 打「封版」；
 * 之后**批量**生成会自动跳过它（单镜显式生成不受限制）。
 * 按 `(project, shot_no)` 存，跨版本稳定（shot_drafts 每次确认都会重建行 id）。
 */
export async function listShotLocks(workspaceId: string, projectId: string): Promise<number[]> {
  const r = await request<{ shotNos: number[] }>(`/api/v1/projects/${projectId}/shot-locks`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
  return r.shotNos ?? []
}

/** 批量设置/取消封版；返回操作后的完整封版镜号列表。 */
export async function setShotLocks(
  workspaceId: string,
  projectId: string,
  shotNos: number[],
  locked: boolean,
  note?: string,
): Promise<number[]> {
  const r = await request<{ shotNos: number[] }>(`/api/v1/projects/${projectId}/shot-locks`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { shotNos, locked, ...(note ? { note } : {}) },
  })
  return r.shotNos ?? []
}
