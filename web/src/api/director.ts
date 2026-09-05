import { request } from './client'
import { WORKSPACE_HEADER } from './projects'
import type { DirectorPlan, GenerateResult, RevisionDetail, RevisionSummary } from './types'

/** POST /api/v1/projects/{id}/director/generate —— briefId 必填，mode 可覆盖（image|video） */
export async function generateDirector(
  workspaceId: string,
  projectId: string,
  input: { briefId: string; mode?: string },
): Promise<GenerateResult> {
  return request<GenerateResult>(`/api/v1/projects/${projectId}/director/generate`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** GET /api/v1/projects/{id}/revisions —— 最新在前 */
export async function listRevisions(workspaceId: string, projectId: string): Promise<RevisionSummary[]> {
  return request<RevisionSummary[]>(`/api/v1/projects/${projectId}/revisions`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** GET /api/v1/projects/{id}/revisions/{rid} */
export async function getRevision(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** PATCH /api/v1/projects/{id}/revisions/{rid} —— 整份编辑后方案（仅未确认版本） */
export async function patchRevision(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  plan: DirectorPlan,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { plan },
  })
}

/** POST /api/v1/projects/{id}/revisions/{rid}/approve —— 全片确认 */
export async function approveRevision(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<{ revisionId: string; approved: boolean; projectStatus: string }> {
  return request(`/api/v1/projects/${projectId}/revisions/${revisionId}/approve`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/projects/{id}/shots/{shotId}/approve —— 单镜确认（§9.2） */
export async function approveShot(
  workspaceId: string,
  projectId: string,
  shotId: string,
): Promise<{ revisionId: string; shotId: string; shotNo: number; status: string }> {
  return request(`/api/v1/projects/${projectId}/shots/${shotId}/approve`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}
