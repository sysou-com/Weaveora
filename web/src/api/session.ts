/** Token 本地存取。MVP 用 localStorage（无 httpOnly cookie 基础设施）；刷新令牌随 access 一并保存。 */
const ACCESS_KEY = 'weaveora.access'
const REFRESH_KEY = 'weaveora.refresh'
const WORKSPACE_KEY = 'weaveora.activeWorkspace'

export function loadTokens(): { accessToken: string | null; refreshToken: string | null } {
  try {
    return {
      accessToken: localStorage.getItem(ACCESS_KEY),
      refreshToken: localStorage.getItem(REFRESH_KEY),
    }
  } catch {
    return { accessToken: null, refreshToken: null }
  }
}

export function saveTokens(accessToken: string, refreshToken: string): void {
  try {
    localStorage.setItem(ACCESS_KEY, accessToken)
    localStorage.setItem(REFRESH_KEY, refreshToken)
  } catch {
    // 隐私模式等场景下忽略
  }
}

export function clearTokens(): void {
  try {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  } catch {
    // ignore
  }
}

export function loadActiveWorkspace(): string | null {
  try {
    return localStorage.getItem(WORKSPACE_KEY)
  } catch {
    return null
  }
}

export function saveActiveWorkspace(id: string): void {
  try {
    localStorage.setItem(WORKSPACE_KEY, id)
  } catch {
    // ignore
  }
}
