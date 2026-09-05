import type { ApiErrorBody } from './types'
import { clearTokens, loadTokens, saveTokens } from './session'

/**
 * 轻量 fetch 客户端：
 * - base 规则：VITE_API_BASE_URL 优先；否则取 Vite BASE_URL（生产以 --base=/weaveora/ 构建时自动得 '/weaveora'）
 * - dev（BASE_URL='/'）→ '' 同源，走 Vite 反代 /api → :8080
 * - 请求路径形如 '/api/v1/...'，与 base 拼接后为 '/weaveora/api/v1/...'（nginx 剥前缀反代后端）
 * - 自动携带 Authorization: Bearer <access>；401 时单次续期重试；失败清会话
 */

export const API_BASE: string = resolveApiBase()

function resolveApiBase(): string {
  const explicit = import.meta.env.VITE_API_BASE_URL
  if (explicit !== undefined && explicit !== '') return explicit.replace(/\/$/, '')
  const base = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, '')
  return base === '' ? '' : base
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly traceId?: string

  constructor(code: string, message: string, status: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  /** JSON body；透传 undefined 表示无 body */
  body?: unknown
  /** 附加请求头（如 X-Workspace-Id） */
  headers?: Record<string, string>
  /** 是否自动附带 Bearer access token（默认 true） */
  auth?: boolean
  /** 401 时是否允许尝试 refresh（默认 true） */
  allowRefresh?: boolean
}

const AUTH_PREFIX = '/api/v1/auth/'

async function parseError(resp: Response): Promise<ApiError> {
  let body: ApiErrorBody | undefined
  try {
    body = (await resp.json()) as ApiErrorBody
  } catch {
    body = undefined
  }
  const message = body?.message ?? `请求失败（HTTP ${resp.status}）`
  return new ApiError(body?.code ?? 'UNKNOWN', message, resp.status, body?.traceId)
}

/** 续期并保存新 token；失败返回 null（调用方决定是否清会话）。 */
export async function refreshSession(): Promise<boolean> {
  const { refreshToken } = loadTokens()
  if (!refreshToken) return false
  try {
    const resp = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!resp.ok) return false
    const data = (await resp.json()) as { accessToken: string; refreshToken: string }
    saveTokens(data.accessToken, data.refreshToken)
    return true
  } catch {
    return false
  }
}

/** 会话过期 → 清 token；路由守卫监听 storage 变化即可跳登录。 */
export function expireSession(): void {
  clearTokens()
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    headers = {},
    auth = true,
    allowRefresh = true,
  } = options

  const buildInit = (): RequestInit => {
    const init: RequestInit = { method, headers: { ...headers } }
    if (body !== undefined) {
      ;(init.headers as Record<string, string>)['Content-Type'] = 'application/json'
      init.body = JSON.stringify(body)
    }
    if (auth) {
      const { accessToken } = loadTokens()
      if (accessToken) (init.headers as Record<string, string>).Authorization = `Bearer ${accessToken}`
    }
    return init
  }

  const doFetch = async (): Promise<Response> => fetch(`${API_BASE}${path}`, buildInit())

  let resp = await doFetch()
  const isAuthCall = path.startsWith(AUTH_PREFIX)

  if (resp.status === 401 && !isAuthCall && allowRefresh) {
    const refreshed = await refreshSession()
    if (refreshed) {
      resp = await doFetch()
    } else {
      expireSession()
      window.dispatchEvent(new CustomEvent('weaveora:session-expired'))
      throw await parseError(resp)
    }
  }

  if (!resp.ok) {
    throw await parseError(resp)
  }
  if (resp.status === 204) return undefined as T
  return (await resp.json()) as T
}
