import { request } from './client'
import { WORKSPACE_HEADER } from './projects'
import type { JobRecord } from './types'

/** POST /api/v1/projects/{id}/jobs —— 需已整版确认（approved revision） */
export async function createJobs(
  workspaceId: string,
  projectId: string,
  input: { revisionId: string; shotId?: string | null; kind: 'still' | 'clip'; count?: number },
): Promise<JobRecord[]> {
  return request<JobRecord[]>(`/api/v1/projects/${projectId}/jobs`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

export async function listJobs(workspaceId: string, projectId: string): Promise<JobRecord[]> {
  return request<JobRecord[]>(`/api/v1/projects/${projectId}/jobs`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

export async function cancelJob(workspaceId: string, jobId: string): Promise<JobRecord> {
  return request<JobRecord>(`/api/v1/jobs/${jobId}/cancel`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** 只允许登出的文本编码，无逻辑。 */
export const JOB_STATE_LABEL: Record<string, string> = {
  queued: '排队',
  running: '生成中',
  succeeded: '完成',
  failed: '失败',
  cancelled: '已取消',
}
