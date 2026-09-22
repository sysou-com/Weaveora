import { request } from './client'
import { WORKSPACE_HEADER } from './projects'
import type { DirectorPlan, GenerateResult, RevisionDetail, RevisionSummary } from './types'

/** POST /api/v1/projects/{id}/director/generate —— briefId 必填，mode 可覆盖（image|video） */
export async function generateDirector(
  workspaceId: string,
  projectId: string,
  input: { briefId: string; mode?: string; promptLang?: 'zh' | 'en' },
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
export interface RewriteFrame {
  label?: string | null
  composition?: string | null
  positivePrompt?: string | null
  negativePrompt?: string | null
}

export interface RewriteResult {
  positive_prompt: string
  negative_prompt: string
  /** 运镜关键帧（P2）：本镜是 2–4 帧运镜镜头时逐帧返回，顺序与传入 frames 一致 */
  keyframes?: Array<{ positive_prompt: string; negative_prompt: string }>
}

export async function rewritePromptFromZh(
  workspaceId: string,
  projectId: string,
  rawText: string,
  originalPositive?: string,
  originalNegative?: string,
  lang: 'zh' | 'en' = 'en',
  frames?: RewriteFrame[],
  opts?: { useTemplate?: boolean; templateScope?: 'image' | 'shot' },
): Promise<RewriteResult> {
  return request<RewriteResult>(`/api/v1/projects/${projectId}/director/rewrite-prompt`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    // lang：'zh' → LLM 返回**中文**正/负向词（含每帧）；'en' → 英文
    // frames：运镜关键帧必须一起传，否则 keyframes[].positive_prompt 不会被重写
    // useTemplate：把**官方口径的提示词模板**随请求带给 LLM（前端「使用模板」勾选框，默认 true）
    // templateScope：'image' = 只写出图正词；'shot' = 分镜（positive_prompt 是图生视频正词 + keyframes[] 是出图正词）
    body: {
      rawText,
      originalPositive,
      originalNegative,
      lang,
      ...(frames && frames.length ? { frames } : {}),
      ...(opts?.useTemplate !== undefined ? { useTemplate: opts.useTemplate } : {}),
      ...(opts?.templateScope ? { templateScope: opts.templateScope } : {}),
    },
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

/** P13：一键抽取剧情主体（人物/载具/物件/场景）；已有主体保留、只补新的 */
export async function extractSubjects(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<{ source: string; added: string[]; subjects: Array<{ name: string; kind: string; aliases: string[] }> }> {
  return request(`/api/v1/projects/${projectId}/revisions/${revisionId}/subjects/extract`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/**
 * P13b/P14：定妆图的**默认**正/负向提示词（弹框预填）。
 *
 * 后端从**方案 subjects[]** 读 kind 与人物档案（性别/年龄/…），所以必须传 revisionId ——
 * 保证「弹框里看到的就是 createPortraitJob 真正会用的那份」。
 */
export async function portraitPromptDefaults(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  subject: string,
  refCount: number,
): Promise<{
  positivePrompt: string
  negativePrompt: string
  kind?: string
  traits?: Record<string, string>
}> {
  const qs = new URLSearchParams({ subject, refCount: String(refCount) })
  return request(`/api/v1/projects/${projectId}/revisions/${revisionId}/portrait-prompt?${qs.toString()}`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** P13：只更新主体元数据（别名 / 参与勾选 / 定妆照）—— 就地生效，不另存版本、不需重新确认 */
export async function patchSubjectMeta(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  subjects: Array<{
    name: string
    kind?: string
    aliases?: string[]
    enabled?: boolean
    portraitAssetId?: string
    portraitVersion?: number
    /** ★ P14：主体设定（人物档案）。带 hasTraits=true 时后端整份替换（允许清空） */
    hasTraits?: boolean
    gender?: string
    age?: string
    height?: string
    build?: string
    personality?: string
    appearance?: string
  }>,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}/subjects/meta`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { subjects },
  })
}

/** P13：就地保存方案（不另存版本、不改确认态）—— 逐条重生成/试听配音用，避免反复要求确认 */
export async function patchPlanInPlace(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  plan: DirectorPlan,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}/plan/inplace`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { plan },
  })
}
