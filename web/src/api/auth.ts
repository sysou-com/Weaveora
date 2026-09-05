import { request } from './client'
import type { Me, TokenPair } from './types'

/** POST /api/v1/auth/register —— 注册（邮箱或手机号二选一；后端建默认工作区并送额度） */
export async function register(input: {
  email?: string
  phone?: string
  password: string
  displayName?: string
}): Promise<TokenPair> {
  return request<TokenPair>('/api/v1/auth/register', { method: 'POST', body: input, auth: false })
}

/** POST /api/v1/auth/login —— account = 邮箱或手机号 */
export async function login(account: string, password: string): Promise<TokenPair> {
  return request<TokenPair>('/api/v1/auth/login', {
    method: 'POST',
    body: { account, password },
    auth: false,
  })
}

/** POST /api/v1/auth/logout —— 无状态 JWT，占位/审计入口，客户端直接丢 token */
export async function logout(): Promise<void> {
  await request<void>('/api/v1/auth/logout', { method: 'POST', auth: false }).catch(() => undefined)
}

/** GET /api/v1/me */
export async function fetchMe(): Promise<Me> {
  return request<Me>('/api/v1/me')
}
