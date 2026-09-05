import { request } from './client'
import type { CreateProjectInput, Project } from './types'

/** 工作区隔离请求头（后端 ProjectController 必填，缺则 VALIDATION；§18.1 §22） */
export const WORKSPACE_HEADER = 'X-Workspace-Id'

/** GET /api/v1/projects —— 返回数组（§17 分页对象暂未启用，后端直接返回 List） */
export async function listProjects(workspaceId: string): Promise<Project[]> {
  return request<Project[]>('/api/v1/projects', {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** POST /api/v1/projects */
export async function createProject(workspaceId: string, input: CreateProjectInput): Promise<Project> {
  return request<Project>('/api/v1/projects', {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: input,
  })
}

/** GET /api/v1/projects/{id} */
export async function getProject(workspaceId: string, projectId: string): Promise<Project> {
  return request<Project>(`/api/v1/projects/${projectId}`, {
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}

/** PATCH /api/v1/projects/{id} —— W1 仅重命名入口，UI 暂不暴露 */
export async function renameProject(workspaceId: string, projectId: string, title: string): Promise<Project> {
  return request<Project>(`/api/v1/projects/${projectId}`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { title },
  })
}
