import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * §8 信息架构（W1 子集 + 详情占位，防死链；后续里程碑逐条点亮）。
 * 路由守卫：无 token → /login；有 token 则静默补 /me 并选工作区。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/app',
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录 · 织影 Weaveora' },
    },
    {
      path: '/app',
      component: () => import('@/layouts/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'projects',
          component: () => import('@/views/ProjectListView.vue'),
          meta: { title: '项目 · 织影 Weaveora' },
        },
        {
          path: 'projects/new',
          name: 'project-new',
          component: () => import('@/views/ProjectNewView.vue'),
          meta: { title: '新建项目 · 织影 Weaveora' },
        },
        {
          path: 'projects/:projectId',
          name: 'project-detail',
          component: () => import('@/views/ProjectDetailView.vue'),
          meta: { title: '项目 · 织影 Weaveora' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/app',
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录且有完整会话才允许跳过登录页
    if (to.name === 'login' && auth.hasSession() && auth.user) {
      return { name: 'projects' }
    }
    return true
  }

  if (!auth.hasSession()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.ensureUser()
    } catch {
      auth.clearSession()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? '织影 Weaveora'
  document.title = title
})

// 会话过期事件（refresh 失败等）→ 若在受保护页则回登录页
window.addEventListener('weaveora:session-expired', () => {
  const auth = useAuthStore()
  auth.clearSession()
  if (router.currentRoute.value.meta.requiresAuth) {
    void router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

export default router
