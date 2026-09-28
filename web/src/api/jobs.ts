import { request } from './client'
import { WORKSPACE_HEADER } from './projects'
import type { EngineStatus, JobRecord } from './types'

/**
 * GET /api/v1/engine/status —— 引擎在线状态（只读）。
 *
 * <p>用途：GPU 不在线时**不让生成报错**（用户 2026-09-17 口径：给提示就行，用户还要继续处理
 * 分镜动作/提示词），前端拿 `notice` 展示一条黄色提示即可。
 */
export async function getEngineStatus(): Promise<EngineStatus> {
  return request<EngineStatus>('/api/v1/engine/status')
}

/** POST /api/v1/projects/{id}/jobs —— 需已整版确认（approved revision） */export async function createJobs(
  workspaceId: string,
  projectId: string,
  input: {
    revisionId: string
    shotId?: string | null
    kind: 'still' | 'clip' | 'voice' | 'bgm' | 'portrait' | 'lipsync'
    count?: number
    frames?: number | null
    /** true=试听任务（配音/配乐试听） */
    preview?: boolean
    /** P8：voice 任务只重生成该镜的第 N 段语音（不传=全部段落） */
    lineIndex?: number | null
    /** P12：只生成这些镜（不传=全部；显式列出时可包含已封版镜） */
    shotNos?: number[] | null
    /** P12：true = 连已封版镜一起生成 */
    includeLocked?: boolean
    /** P13：kind=portrait 时指定剧情主体名 */
    subject?: string
    /** P13：kind=portrait 时直接指定参考图资产（界面上当前点选的图，无需先保存/确认） */
    refAssetIds?: string[]
    /** P13b：kind=portrait 时用户在弹框里确认过的正/负向提示词（不传=用后端默认模板） */
    positivePrompt?: string
    negativePrompt?: string
    /**
     * P15（2026-09-25 用户裁定）：多主体关键帧的用户确认。
     *
     * <p>由 {@link keyframeFaceCheck} 预检弹框产出 —— 镜内剧情主体 &gt; 2 时，
     * 用户选「景别（过肩/中近景/近景）+ 要显示脸的 ≤2 个主体 + 场景补充」。
     * 后端会把它们写进本次出图的正词（并替掉原正词里冲突的景别词）再建任务。
     */
    keyframeConfirms?: KeyframeConfirmInput[]
  },
): Promise<JobRecord[]> {
  return request<JobRecord[]>(`/api/v1/projects/${projectId}/jobs`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** P15：预检返回的一镜（**只含需要确认的镜**：剧情主体 &gt; 2） */
export interface KeyframeFaceCheckRow {
  shotNo: number
  shotId: string
  subjectCount: number
  subjects: string[]
  /** 方案里现有的景别字段值（可能为空） */
  shotSize?: string | null
  /** 建议景别词典值：over_the_shoulder / medium_close / close_up */
  suggestedShotSizes: string[]
  /** 后端给的一句人话（跟随该镜正词语言） */
  message: string
  constraintPreview?: string
}

/** P15：用户在弹框里确认的选择（与后端 KeyframeConfirm 一一对应） */
export interface KeyframeConfirmInput {
  shotNo: number
  /** 运镜镜头第几帧（不传 = 该镜全部帧） */
  keyframeIndex?: number | null
  shotSize?: string
  visibleSubjects?: string[]
  sceneNote?: string
}

/**
 * POST /api/v1/projects/{id}/keyframe-face-check —— 关键帧「人脸数量 &gt; 2」预检（**只读**，不建任务）。
 *
 * <p>用户口径：「在每一镜的关键帧的时候检查剧情主体数量大于 2 个则提示关键图中人脸数量大于 2」。
 * 返回结果为空 = 不需要确认，可直接建任务。
 */
export async function keyframeFaceCheck(
  workspaceId: string,
  projectId: string,
  input: { revisionId: string; shotId?: string | null; kind?: string; shotNos?: number[] | null },
): Promise<KeyframeFaceCheckRow[]> {
  return request<KeyframeFaceCheckRow[]>(`/api/v1/projects/${projectId}/keyframe-face-check`, {
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

/** 重试所选失败/已取消任务（§20.2：返回新入队 job） */
export async function retryJobs(
  workspaceId: string,
  projectId: string,
  jobIds: string[],
): Promise<JobRecord[]> {
  return request<JobRecord[]>(`/api/v1/projects/${projectId}/jobs/retry`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { jobIds },
  })
}

/** 删除所选失败/已取消任务记录 */
export async function deleteJobs(
  workspaceId: string,
  projectId: string,
  jobIds: string[],
): Promise<{ deleted: number }> {
  return request<{ deleted: number }>(`/api/v1/projects/${projectId}/jobs/delete`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { jobIds },
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

/** POST /api/v1/jobs/{jobId}/rerun —— 单条任务重生成（终态均可，含成功；自动换 seed） */
export async function rerunJob(workspaceId: string, jobId: string): Promise<JobRecord> {
  return request<JobRecord>(`/api/v1/jobs/${jobId}/rerun`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}
