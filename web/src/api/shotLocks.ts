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

/** P13：运动帧数可用区间（按引擎区分：本机 GPU 由显存决定，云 API 由模型决定）。 */
export interface VideoLimits {
  engine: string
  minFrames: number
  maxFrames: number
  /** ★ P2：**实际交付帧率**（按出片引擎归一：必须能被原生帧率整除，否则导出会复制帧拉齐 = 顿挫） */
  fps: number
  /** ★ P2：方案 `edit_plan.fps` 里写着的值（可能 ≠ fps，UI 要能看出来并提醒） */
  planFps?: number
  /** ★ P5：LTX-2.5 时间轴 ×2（24→48fps）是否开 */
  fpsX2?: boolean
  defaultDeliverFps?: number
  /** 原生帧率（Wan2.2=16 / LTX-2.5=24）——「帧上限→秒数」必须用它除（2026-09-22 修：不能用项目成片帧率） */
  nativeFps?: number
  /** ★ 2026-09-23：当前出片引擎（wan22 | ltx25），由后端 services.motion.engine 判定 */
  motionEngine?: string
  maxClipSec: number
  gpuMaxFrames: number
  cloudMaxFrames: number
  source: string
}

export async function getVideoLimits(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<VideoLimits | null> {
  try {
    return await request<VideoLimits>(
      `/api/v1/projects/${projectId}/video-limits?revisionId=${revisionId}`,
      { headers: { [WORKSPACE_HEADER]: workspaceId } },
    )
  } catch {
    return null // 取不到就用界面默认（32–96），不阻塞生成
  }
}
