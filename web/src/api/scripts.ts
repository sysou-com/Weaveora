import { request } from './client'
import type { MarkToggle } from './market'
import { WORKSPACE_HEADER } from './projects'
import type {
  AiCondensedResult,
  AiFieldResult,
  AiGuideResult,
  AiNextEpisodeResult,
  AiPolishResult,
  ApplySyncResult,
  ConvertToProjectResult,
  EpisodeSaveResult,
  Script,
  ScriptCard,
  ScriptChange,
  ScriptConflict,
  ScriptEpisode,
  ScriptPage,
} from './types'

/** 我的剧本（/api/v1/scripts）—— 与项目模块同构：X-Workspace-Id 隔离 + 集市端点。 */

function q(path: string, page: number, size: number): string {
  const s = new URLSearchParams({ page: String(page), size: String(size) })
  return `${path}?${s.toString()}`
}

/** 新建剧本后的请求体（标题/类型必填；其余可空，字段上限 8000 字） */
export interface ScriptInput {
  title: string
  genre: string
  characters?: string | null
  story?: string | null
  conflict?: string | null
  plotStructure?: string | null
  language?: string | null
  stageDirections?: string | null
}

/** 保存一集 */
export interface EpisodeInput {
  title?: string | null
  content?: string | null
  summary?: string | null
  aiPolished?: boolean
  /** true（默认）= 保存后刷新「精简的故事」并做一致性检查 */
  syncPrevious?: boolean
  /** 【B】本集节拍提纲（AI 生成后原样带回，随集保存） */
  outline?: string[] | null
}

// ---------------------------------------------------------------- 剧本 CRUD

/** GET /api/v1/scripts（数组，内部用） */
export async function listScripts(workspaceId: string): Promise<Script[]> {
  return request<Script[]>('/api/v1/scripts', {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/scripts */
export async function createScript(workspaceId: string, input: ScriptInput): Promise<Script> {
  return request<Script>('/api/v1/scripts', {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** GET /api/v1/scripts/{id} */
export async function getScript(workspaceId: string, scriptId: string): Promise<Script> {
  return request<Script>(`/api/v1/scripts/${scriptId}`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** PATCH /api/v1/scripts/{id}（null/省略 = 不改；空串 = 显式清空） */
export async function patchScript(
  workspaceId: string,
  scriptId: string,
  input: Partial<ScriptInput> & { condensedStory?: string },
): Promise<Script> {
  return request<Script>(`/api/v1/scripts/${scriptId}`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** GET /api/v1/scripts/own（我的剧本，分页） */
export async function listOwnScriptPage(
  workspaceId: string,
  page = 0,
  size = 8,
): Promise<ScriptPage> {
  return request<ScriptPage>(q('/api/v1/scripts/own', page, size), {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/scripts/delete（管理态批量软删） */
export async function deleteScripts(
  workspaceId: string,
  scriptIds: string[],
): Promise<{ deleted: number }> {
  return request<{ deleted: number }>('/api/v1/scripts/delete', {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { scriptIds },
  })
}

// ---------------------------------------------------------------- 剧本集市

/** POST /api/v1/scripts/{id}/share（提交到剧本精选待审） */
export async function shareScript(workspaceId: string, scriptId: string): Promise<ScriptCard> {
  return request<ScriptCard>(`/api/v1/scripts/${scriptId}/share`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: {},
  })
}

/** GET /api/v1/scripts/marketplace（剧本精选，游客可读） */
export async function listScriptMarketPage(page = 0, size = 8): Promise<ScriptPage> {
  return request<ScriptPage>(q('/api/v1/scripts/marketplace', page, size))
}

/** GET /api/v1/scripts/marketplace/pending（管理员） */
export async function listScriptPendingPage(page = 0, size = 8): Promise<ScriptPage> {
  return request<ScriptPage>(q('/api/v1/scripts/marketplace/pending', page, size))
}

/** POST /api/v1/scripts/marketplace/review（管理员批量审批） */
export async function reviewScripts(
  scriptIds: string[],
  approved: boolean,
): Promise<{ reviewed: number }> {
  return request<{ reviewed: number }>('/api/v1/scripts/marketplace/review', {
    method: 'POST',
    body: { scriptIds, approved },
  })
}

/** GET /api/v1/scripts/marketplace/{id} */
export async function marketScript(scriptId: string): Promise<ScriptCard> {
  return request<ScriptCard>(`/api/v1/scripts/marketplace/${scriptId}`)
}

/** GET /api/v1/scripts/marketplace/{id}/episodes（只读集列表） */
export async function marketScriptEpisodes(scriptId: string): Promise<ScriptEpisode[]> {
  return request<ScriptEpisode[]>(`/api/v1/scripts/marketplace/${scriptId}/episodes`)
}

/** POST /api/v1/scripts/marketplace/{id}/toggle/{kind} */
export async function toggleScriptMark(scriptId: string, kind: 'like' | 'fav'): Promise<MarkToggle> {
  return request<MarkToggle>(`/api/v1/scripts/marketplace/${scriptId}/toggle/${kind}`, {
    method: 'POST',
    body: {},
  })
}

// ---------------------------------------------------------------- 集

/** GET /api/v1/scripts/{id}/episodes */
export async function listScriptEpisodes(
  workspaceId: string,
  scriptId: string,
): Promise<ScriptEpisode[]> {
  return request<ScriptEpisode[]>(`/api/v1/scripts/${scriptId}/episodes`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/scripts/{id}/episodes */
export async function createScriptEpisode(
  workspaceId: string,
  scriptId: string,
  input: EpisodeInput,
): Promise<EpisodeSaveResult> {
  return request<EpisodeSaveResult>(`/api/v1/scripts/${scriptId}/episodes`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** PATCH /api/v1/scripts/{id}/episodes/{eid} */
export async function updateScriptEpisode(
  workspaceId: string,
  scriptId: string,
  episodeId: string,
  input: EpisodeInput,
): Promise<EpisodeSaveResult> {
  return request<EpisodeSaveResult>(`/api/v1/scripts/${scriptId}/episodes/${episodeId}`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** DELETE /api/v1/scripts/{id}/episodes/{eid} */
export async function deleteScriptEpisode(
  workspaceId: string,
  scriptId: string,
  episodeId: string,
): Promise<{ deleted: boolean }> {
  return request<{ deleted: boolean }>(`/api/v1/scripts/${scriptId}/episodes/${episodeId}`, {
    method: 'DELETE',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** GET /api/v1/scripts/{id}/changes */
export async function listScriptChanges(
  workspaceId: string,
  scriptId: string,
  limit = 50,
): Promise<ScriptChange[]> {
  return request<ScriptChange[]>(`/api/v1/scripts/${scriptId}/changes?limit=${limit}`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/scripts/{id}/episodes/{eid}/apply-sync（Q4：用户确认后应用一致性改动） */
export async function applyScriptSync(
  workspaceId: string,
  scriptId: string,
  episodeId: string,
  items: ScriptConflict[],
): Promise<ApplySyncResult> {
  return request<ApplySyncResult>(
    `/api/v1/scripts/${scriptId}/episodes/${episodeId}/apply-sync`,
    {
      method: 'POST',
      headers: { [WORKSPACE_HEADER]: workspaceId },
      body: { items },
    },
  )
}

// ---------------------------------------------------------------- AI

/** POST /api/v1/scripts/{id}/ai/field（生成值，不落库；前端 diff 确认后再 PATCH） */
export async function aiScriptField(
  workspaceId: string,
  scriptId: string,
  input: {
    field: string
    mode: 'from_title' | 'from_content'
    hint?: string
    currentValue?: string
    /** 用户设定的目标字数（≤8000；不传=4000） */
    targetChars?: number
    /** true = 强制重新生成提纲（默认复用已存提纲） */
    refreshOutline?: boolean
  },
): Promise<AiFieldResult> {
  return request<AiFieldResult>(`/api/v1/scripts/${scriptId}/ai/field`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** POST /api/v1/scripts/ai/preview-field（新建剧本页：无剧本 ID 的生成，不落库） */
export async function aiScriptFieldPreview(input: {
  title: string
  genre: string
  field: string
  mode: 'from_title' | 'from_content'
  hint?: string
  currentValue?: string
  elements?: Record<string, string>
  /** 用户设定的目标字数（≤8000；不传=4000） */
  targetChars?: number
}): Promise<AiFieldResult> {
  return request<AiFieldResult>('/api/v1/scripts/ai/preview-field', {
    method: 'POST',
    body: input,
  })
}

/** POST /api/v1/scripts/{id}/ai/next-episode */
export async function aiNextEpisode(
  workspaceId: string,
  scriptId: string,
  input: {
    polished: boolean
    titleHint?: string
    instruction?: string
    episodeNo?: number
    /** 本集目标字数（≤8000；不传=4000） */
    targetChars?: number
  },
): Promise<AiNextEpisodeResult> {
  return request<AiNextEpisodeResult>(`/api/v1/scripts/${scriptId}/ai/next-episode`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/**
 * POST /api/v1/scripts/{id}/ai/polish —— 润色**作者自己写的正文**（用户 2026-09-17：「我自己写」也要能 AI 润色）。
 *
 * 与 next-episode 的区别：那个是「按精简的故事另写一集」，这个是「在已有正文上改文笔、不重编剧情」。
 * 不落库，结果由前端填进编辑器。
 */
export async function aiPolish(
  workspaceId: string,
  scriptId: string,
  input: {
    content: string
    instruction?: string
    /** 目标字数；不传/0 = 保持原长度（只改文笔） */
    targetChars?: number
  },
): Promise<AiPolishResult> {
  return request<AiPolishResult>(`/api/v1/scripts/${scriptId}/ai/polish`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** POST /api/v1/scripts/{id}/ai/condensed */
export async function aiCondensed(
  workspaceId: string,
  scriptId: string,
): Promise<AiCondensedResult> {
  return request<AiCondensedResult>(`/api/v1/scripts/${scriptId}/ai/condensed`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: {},
  })
}

/** POST /api/v1/scripts/{id}/ai/guide */
export async function aiGuide(workspaceId: string, scriptId: string): Promise<AiGuideResult> {
  return request<AiGuideResult>(`/api/v1/scripts/${scriptId}/ai/guide`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: {},
  })
}

// ---------------------------------------------------------------- 转成项目

/** POST /api/v1/scripts/{id}/episodes/{eid}/to-project */
export async function convertEpisodeToProject(
  workspaceId: string,
  scriptId: string,
  episodeId: string,
  input: {
    mode: 'image' | 'video'
    aspectRatio: string
    durationSec?: number | null
    shotDurationSec?: number | null
    styleTemplateId?: string | null
    condenseBrief?: boolean
    runDirector?: boolean
  },
): Promise<ConvertToProjectResult> {
  return request<ConvertToProjectResult>(
    `/api/v1/scripts/${scriptId}/episodes/${episodeId}/to-project`,
    {
      method: 'POST',
      headers: { [WORKSPACE_HEADER]: workspaceId },
      body: input,
    },
  )
}
