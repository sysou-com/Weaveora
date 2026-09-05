import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchMe, login as apiLogin, logout as apiLogout, register as apiRegister } from '@/api/auth'
import { ApiError } from '@/api/client'
import { clearTokens, loadActiveWorkspace, loadTokens, saveActiveWorkspace, saveTokens } from '@/api/session'
import type { Me } from '@/api/types'

/** 认证 / 工作区会话（§18.1：路由守卫 + X-Workspace-Id）。 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<Me | null>(null)
  const loading = ref(false)
  const activeWorkspaceId = ref<string | null>(loadActiveWorkspace())

  /** 会话是否持有 access token —— 必须是函数（每次都读 localStorage）：
   *  不能用 computed 包装，否则无响应式依赖时首次取值即永久缓存（守卫/登录页会拿到陈旧值）。 */
  function hasSession(): boolean {
    return loadTokens().accessToken !== null
  }

  const isAuthenticated = computed(() => hasSession() && user.value !== null)

  function persistWorkspace(id: string): void {
    activeWorkspaceId.value = id
    saveActiveWorkspace(id)
  }

  /** 拉取 /me 并选定工作区（默认沿用上次选择，否则取第一个；必填 X-Workspace-Id 依赖它）。 */
  async function ensureUser(): Promise<Me> {
    if (user.value) return user.value
    loading.value = true
    try {
      const me = await fetchMe()
      user.value = me
      const first = me.workspaces[0]?.id
      if (!first) {
        clearTokens()
        throw new ApiError('NO_WORKSPACE', '账号尚未关联任何工作区', 403)
      }
      if (!activeWorkspaceId.value || !me.workspaces.some((w) => w.id === activeWorkspaceId.value)) {
        persistWorkspace(first)
      }
      return me
    } finally {
      loading.value = false
    }
  }

  async function applyTokens(accessToken: string, refreshToken: string): Promise<Me> {
    saveTokens(accessToken, refreshToken)
    user.value = null
    return ensureUser()
  }

  async function login(account: string, password: string): Promise<Me> {
    const pair = await apiLogin(account, password)
    return applyTokens(pair.accessToken, pair.refreshToken)
  }

  async function register(input: { email?: string; password: string; displayName?: string }): Promise<Me> {
    const pair = await apiRegister(input)
    return applyTokens(pair.accessToken, pair.refreshToken)
  }

  /** 应用启动恢复：有 token 就静默验证；失败清会话。 */
  async function restore(): Promise<void> {
    if (!hasSession()) return
    try {
      await ensureUser()
    } catch {
      clearSession()
    }
  }

  function clearSession(): void {
    clearTokens()
    user.value = null
  }

  async function logout(): Promise<void> {
    await apiLogout()
    clearSession()
  }

  return {
    user,
    loading,
    activeWorkspaceId,
    hasSession,
    isAuthenticated,
    ensureUser,
    restore,
    login,
    register,
    logout,
    clearSession,
  }
})
