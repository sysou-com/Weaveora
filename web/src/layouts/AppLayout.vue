<script setup lang="ts">
import { LogOut, Plus } from 'lucide-vue-next'
import { NDropdown, NIcon, NButton, NModal, type DropdownOption } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const ADMIN_EMAIL = 'sysou.com@outlook.com'

const userName = computed(() => auth.user?.displayName ?? '…')
const email = computed(() => auth.user?.email ?? '')
const hasWorkspace = computed(() => (auth.activeWorkspaceId ? true : false))
const isAdmin = computed(() => auth.user?.email?.toLowerCase() === ADMIN_EMAIL)
const showContact = ref(false)

const moreOptions: DropdownOption[] = [
  { label: '关于', key: 'about' },
  { label: '联系开发者', key: 'contact' },
]

const menuOptions = computed<DropdownOption[]>(() => {
  const opts: DropdownOption[] = [{ label: userName.value, key: 'user', disabled: true }]
  if (email.value) opts.push({ label: email.value, key: 'email', disabled: true })
  opts.push(
    { type: 'divider', key: 'd1' } as DropdownOption,
    { label: '退出登录', key: 'logout' },
  )
  return opts
})

function navActive(name: string): boolean {
  const cur = String(route.name ?? '')
  return cur === name
}

async function onMenuSelect(key: string): Promise<void> {
  if (key === 'logout') {
    await auth.logout()
    void router.push({ name: 'login' })
  }
}

async function onMoreSelect(key: string): Promise<void> {
  if (key === 'about') {
    void router.push({ name: 'about' })
  } else if (key === 'contact') {
    showContact.value = true
  }
}

function goProjects(): void {
  void router.push({ name: 'home' })
}

const contact = { qq: '358532433', email: 'sysou.com@outlook.com' }
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <button type="button" class="brand" aria-label="织影 首页" @click="goProjects">
        <BrandMark :size="26" tone="bone" />
        <span class="brand-name font-display">织影</span>
        <span class="brand-en font-mono">WEAVEORA</span>
      </button>

      <nav class="topnav" aria-label="主导航">
        <button type="button" class="nav-link" :class="{ on: navActive('home') }" @click="router.push({ name: 'home' })">首页</button>
        <button type="button" class="nav-link" :class="{ on: navActive('projects') }" @click="router.push({ name: 'projects' })">我的项目</button>
        <button type="button" class="nav-link" :class="{ on: navActive('guide') }" @click="router.push({ name: 'guide' })">使用指南</button>
        <NDropdown :options="moreOptions" trigger="click" @select="onMoreSelect">
          <button type="button" class="nav-link more">更多 <span class="caret">▾</span></button>
        </NDropdown>
      </nav>

      <div class="topbar-right">
        <NButton
          v-if="hasWorkspace && isAdmin"
          size="medium"
          type="primary"
          data-testid="btn-new-project"
          @click="router.push({ name: 'project-new' })"
        >
          <template #icon>
            <NIcon><Plus :size="16" /></NIcon>
          </template>
          新建项目
        </NButton>

        <NDropdown :options="menuOptions" trigger="click" @select="onMenuSelect">
          <button type="button" class="user-chip" aria-label="账号菜单">
            <span class="user-avatar">{{ userName.slice(0, 1).toUpperCase() }}</span>
            <span class="user-name">{{ userName }}</span>
            <NIcon size="14" class="user-chev">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="m6 9 6 6 6-6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </NIcon>
          </button>
        </NDropdown>

        <button
          type="button"
          class="icon-btn"
          title="退出登录"
          aria-label="退出登录"
          @click="auth.logout().then(() => router.push({ name: 'login' }))"
        >
          <NIcon size="17"><LogOut /></NIcon>
        </button>
      </div>
    </header>

    <!-- 联系开发者 -->
    <NModal v-model:show="showContact" preset="card" :title="'联系开发者'" style="max-width: 420px">
      <p class="contact-line">QQ：{{ contact.qq }}</p>
      <p class="contact-line">邮箱：<a :href="`mailto:${contact.email}`">{{ contact.email }}</a></p>
    </NModal>

    <main class="app-main">
      <router-view />
    </main>

    <footer class="app-footer">
      <span class="font-mono">FROM A SENTENCE TO A SHOT.</span>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 28px;
  background: rgba(11, 11, 10, 0.86);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--wv-divider);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 4px 8px;
  margin-left: -8px;
  background: none;
  border: none;
  color: var(--wv-text);
  cursor: pointer;
  border-radius: var(--wv-radius-s);
}
.brand:hover {
  background: var(--wv-surface);
}
.brand-name {
  font-size: 19px;
  line-height: 1;
  letter-spacing: 0.02em;
}
.brand-en {
  font-size: 10px;
  letter-spacing: 0.32em;
  color: var(--wv-text-3);
  margin-left: -2px;
  transform: translateY(1px);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.topnav {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 auto;
}
.nav-link {
  appearance: none;
  background: none;
  border: none;
  color: var(--wv-text-3);
  font-size: 14px;
  line-height: 1;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.nav-link:hover {
  color: var(--wv-text);
  background: var(--wv-surface);
}
.nav-link.on {
  color: var(--wv-accent-text);
  background: var(--wv-accent-soft);
}
.nav-link.more { display: inline-flex; align-items: center; gap: 4px; }
.nav-link .caret { font-size: 10px; color: var(--wv-text-4); }
.contact-line { margin: 0 0 8px; font-size: 14px; line-height: 1.8; }
.contact-line a { color: var(--wv-accent-text); }

@media (max-width: 860px) {
  .topnav { display: none; }
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 6px;
  background: none;
  border: 1px solid transparent;
  border-radius: 999px;
  color: var(--wv-text);
  cursor: pointer;
  transition: background var(--wv-dur) var(--wv-ease), border-color var(--wv-dur) var(--wv-ease);
}
.user-chip:hover {
  background: var(--wv-surface);
  border-color: var(--wv-line);
}
.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--wv-surface-raised);
  border: 1px solid var(--wv-line);
  color: var(--wv-accent-text);
  font-family: var(--wv-font-display);
  font-size: 14px;
}
.user-name {
  font-size: 13.5px;
  color: var(--wv-text-2);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-chev {
  color: var(--wv-text-4);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--wv-radius-s);
  background: none;
  border: none;
  color: var(--wv-text-3);
  cursor: pointer;
  transition: background var(--wv-dur) var(--wv-ease), color var(--wv-dur) var(--wv-ease);
}
.icon-btn:hover {
  background: var(--wv-surface);
  color: var(--wv-danger);
}

.app-main {
  flex: 1;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 36px 28px 64px;
}

.app-footer {
  padding: 18px 28px 26px;
  text-align: center;
  color: var(--wv-text-4);
  font-size: 10px;
  letter-spacing: 0.42em;
  border-top: 1px solid var(--wv-divider);
}
</style>
