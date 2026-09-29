import { request } from './client'
import { WORKSPACE_HEADER } from './projects'

/** 制作流程的单个阶段（P0/P1）。state: done | running | ready | failed | blocked */
export interface RunStage {
  stage: string
  label: string
  state: string
  total: number
  done: number
  failed: number
  hint: string
  approvedAt?: string | null
  canConfirm: boolean
}

export interface RunStatus {
  projectId: string
  revisionId: string
  revisionNo: number
  projectStatus: string
  isApprovedRevision: boolean
  targetSec: number
  plannedSec: number
  shotCount: number
  maxShotSec: number
  nativeFps: number
  motionEngine: string
  engineRoute: string
  timingMode: string
  oversizePolicy: string
  timingReady: boolean
  stages: RunStage[]
  nextStage: string | null
}

export interface PrepareResult {
  voiceBindingsAdded: number
  linesAdded: number
  shotCount: number
  plannedSec: number
  targetSec: number
  notes: string[]
}

const h = (workspaceId: string) => ({ [WORKSPACE_HEADER]: workspaceId })

/** GET /api/v1/projects/{id}/runs/status —— 制作流程总览 */
export async function getRunStatus(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<RunStatus> {
  return request<RunStatus>(
    `/api/v1/projects/${projectId}/runs/status?revisionId=${encodeURIComponent(revisionId)}`,
    { headers: h(workspaceId) },
  )
}

/** POST /api/v1/projects/{id}/runs/prepare —— 自动音色 + 自动台词 + 按台词时长/引擎能力重排镜头时长 */
export async function prepareRun(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  lines = true,
): Promise<PrepareResult> {
  return request<PrepareResult>(`/api/v1/projects/${projectId}/runs/prepare`, {
    method: 'POST',
    headers: h(workspaceId),
    body: { revisionId, lines },
  })
}

/** POST /api/v1/projects/{id}/runs/replan —— 只按台词时长/引擎能力重排镜头时长（确定性、不跑 LLM） */
export async function replanRun(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<PrepareResult> {
  return request<PrepareResult>(`/api/v1/projects/${projectId}/runs/replan`, {
    method: 'POST',
    headers: h(workspaceId),
    body: { revisionId },
  })
}

/** POST /api/v1/projects/{id}/runs/stages/{stage}/confirm —— 点「确定/下一步」 */
export async function confirmRunStage(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  stage: string,
): Promise<RunStatus> {
  return request<RunStatus>(`/api/v1/projects/${projectId}/runs/stages/${stage}/confirm`, {
    method: 'POST',
    headers: h(workspaceId),
    body: { revisionId },
  })
}

/** POST /api/v1/projects/{id}/runs/stages/{stage}/retry —— 重试该阶段失败任务 */
export async function retryRunStage(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  stage: string,
): Promise<RunStatus> {
  return request<RunStatus>(`/api/v1/projects/${projectId}/runs/stages/${stage}/retry`, {
    method: 'POST',
    headers: h(workspaceId),
    body: { revisionId },
  })
}
