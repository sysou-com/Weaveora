import { API_BASE, request } from './client'
import { loadTokens } from './session'
import { WORKSPACE_HEADER } from './projects'
import type { ProjectCard, ProjectPage } from './types'

/** 项目列表/集市 API（分页 8/页，更新时间倒序；内测管理） */

function q(path: string, page: number, size: number): string {
  const s = new URLSearchParams({ page: String(page), size: String(size) })
  return `${path}?${s.toString()}`
}

/** 我的项目（分页） */
export async function listOwnPage(
  workspaceId: string,
  page = 0,
  size = 8,
): Promise<ProjectPage> {
  return request<ProjectPage>(q('/api/v1/projects/own', page, size), {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** 项目集市（已上架，非本人） */
export async function listMarketPage(page = 0, size = 8): Promise<ProjectPage> {
  return request<ProjectPage>(q('/api/v1/projects/marketplace', page, size))
}

/** 管理待审（管理员） */
export async function listPendingPage(page = 0, size = 8): Promise<ProjectPage> {
  return request<ProjectPage>(q('/api/v1/projects/marketplace/pending', page, size))
}

/** 管理：批量删除 */
export async function deleteProjects(
  workspaceId: string,
  projectIds: string[],
): Promise<{ deleted: number }> {
  return request<{ deleted: number }>('/api/v1/projects/delete', {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { projectIds },
  })
}

/** 提交分享（进入待审） */
export async function shareProject(
  workspaceId: string,
  projectId: string,
): Promise<ProjectCard> {
  return request<ProjectCard>(`/api/v1/projects/${projectId}/share`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: {},
  })
}

/** 管理：集市审批（批量 通过/驳回） */
export async function reviewProjects(
  projectIds: string[],
  approved: boolean,
): Promise<{ reviewed: number }> {
  return request<{ reviewed: number }>('/api/v1/projects/marketplace/review', {
    method: 'POST',
    body: { projectIds, approved },
  })
}

/** 集市只读详情卡片 */
export async function marketProject(projectId: string): Promise<ProjectCard> {
  return request<ProjectCard>(`/api/v1/projects/marketplace/${projectId}`)
}

/** 集市预览图（跨工作区，无需同区成员；带鉴权头） */
export async function fetchMarketPreview(projectId: string): Promise<Blob | null> {
  const { accessToken } = loadTokens()
  const resp = await fetch(`${API_BASE}/api/v1/projects/marketplace/${projectId}/preview`, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
  })
  if (!resp.ok) return null
  return resp.blob()
}

/** 点赞/收藏切换（like|fav）→ 返回 { kind, active, count } */
export interface MarkToggle {
  kind: 'like' | 'fav'
  active: boolean
  count: number
}

export async function toggleMark(projectId: string, kind: 'like' | 'fav'): Promise<MarkToggle> {
  return request<MarkToggle>(`/api/v1/projects/marketplace/${projectId}/toggle/${kind}`, {
    method: 'POST',
    body: {},
  })
}
