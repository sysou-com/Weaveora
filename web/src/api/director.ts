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

/** POST director/rewrite-prompt —— 中文描述 → LLM 重写正/负提示词（供确认后应用） */
export interface RewriteResult {
  positive_prompt: string
  negative_prompt: string
}

export async function rewritePromptFromZh(
  workspaceId: string,
  projectId: string,
  rawText: string,
  originalPositive?: string,
  originalNegative?: string,
): Promise<RewriteResult> {
  return request<RewriteResult>(`/api/v1/projects/${projectId}/director/rewrite-prompt`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { rawText, originalPositive, originalNegative },
  })
}

/* ---------------- P11 AI 音频助手 ---------------- */

/** AI 生成的一条台词（时间已由后端按字数与镜头时长铺排好） */
export interface AiFittedLine {
  at_sec: number
  end_sec: number
  text: string
  kind: 'narration' | 'dialogue'
  subject?: string | null
  speed: number
}

export interface AiLinesResult {
  source: 'llm' | 'stub'
  shots: Array<{ shotNo: number; shotDurationSec: number; lines: AiFittedLine[] }>
  notes: string[]
}

/** POST revisions/{rid}/ai-lines —— 分析画面+人物 → 1~3 段台词（模拟对话） */
export async function aiGenerateLines(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  shotNo: number | null,
  replace: boolean,
): Promise<AiLinesResult> {
  return request<AiLinesResult>(`/api/v1/projects/${projectId}/revisions/${revisionId}/ai-lines`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { shotNo, replace },
  })
}

/** AI 给出的一段配乐 */
export interface AiMusicCue {
  id: string
  start_sec: number
  end_sec: number
  mood: string
  gain_db: number
  fade_in_sec: number
  fade_out_sec: number
  loop: boolean
  duck: boolean
}

export interface AiMusicResult {
  source: 'llm' | 'stub'
  music: AiMusicCue[]
  notes: string[]
}

/** POST revisions/{rid}/ai-music —— 依据剧情给出 2~5 段「时间段 + 情绪」 */
export async function aiGenerateMusic(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<AiMusicResult> {
  return request<AiMusicResult>(`/api/v1/projects/${projectId}/revisions/${revisionId}/ai-music`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}
