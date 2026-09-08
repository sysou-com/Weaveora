import { request } from './client'
import type { StyleTemplate } from './types'

/** GET /api/v1/style-templates —— 系统内置风格模板目录（新建项目可选） */
export async function fetchStyleTemplates(): Promise<StyleTemplate[]> {
  return request<StyleTemplate[]>('/api/v1/style-templates')
}
