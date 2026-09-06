import { request } from './client'
import type { JobRecord } from './types'

/** 管理员：查看全部 queued/running */
export async function adminQueueJobs(): Promise<JobRecord[]> {
  return request<JobRecord[]>('/api/v1/admin/queue/jobs')
}

/** 管理员：手工让任务失败 */
export async function adminFailJob(jobId: string): Promise<{ failed: number }> {
  return request<{ failed: number }>(`/api/v1/admin/queue/jobs/${jobId}/fail`, {
    method: 'POST',
    body: {},
  })
}
