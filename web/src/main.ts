import '@fontsource/fraunces/400.css'
import '@fontsource/fraunces/500.css'
import '@fontsource/fraunces/600.css'
import '@fontsource/figtree/400.css'
import '@fontsource/figtree/500.css'
import '@fontsource/figtree/600.css'
import '@fontsource/figtree/700.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'

import { VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/base.css'


/**
 * P13：分块过期自愈。
 *
 * 发版后浏览器里还开着的旧页面会去拉**旧 hash 的 chunk**（已被替换）→
 * `Failed to fetch dynamically imported module: …/ProjectListView-xxxx.js`，
 * 表现就是「点菜单/返回没反应或很慢」。这里统一兜住：自动整页刷新一次拿新版本，
 * 15 秒内只刷一次，避免死循环。
 */
function reloadOnceOnChunkError(reason: unknown): void {
  const msg = String(reason ?? '')
  if (!/dynamically imported module|Importing a module script failed|Loading chunk .* failed/i.test(msg)) return
  const KEY = 'wv.chunkReloadAt'
  const last = Number(sessionStorage.getItem(KEY) ?? 0)
  if (Date.now() - last < 15_000) {
    console.warn('[chunk] 已刷新过，跳过自动重载：', msg)
    return
  }
  sessionStorage.setItem(KEY, String(Date.now()))
  console.warn('[chunk] 检测到分块过期，自动刷新：', msg)
  window.location.reload()
}

window.addEventListener('vite:preloadError', (e) => {
  e.preventDefault()
  reloadOnceOnChunkError((e as Event & { payload?: unknown }).payload)
})
window.addEventListener('unhandledrejection', (e) => reloadOnceOnChunkError(e.reason))
router.onError((err) => reloadOnceOnChunkError(err))

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(VueQueryPlugin, {
  queryClientConfig: {
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        retry: 1,
        refetchOnWindowFocus: false,
      },
    },
  },
})

app.mount('#app')
