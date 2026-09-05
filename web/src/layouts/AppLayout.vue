<script setup lang="ts">
import { LogOut, Plus } from 'lucide-vue-next'
import { NDropdown, NIcon, NButton, type DropdownOption } from 'naive-ui'
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const userName = computed(() => auth.user?.displayName ?? '…')
const email = computed(() => auth.user?.email ?? '')
const hasWorkspace = computed(() => (auth.activeWorkspaceId ? true : false))

const menuOptions = computed<DropdownOption[]>(() => {
  const opts: DropdownOption[] = [{ label: userName.value, key: 'user', disabled: true }]
  if (email.value) opts.push({ label: email.value, key: 'email', disabled: true })
  opts.push(
    { type: 'divider', key: 'd1' } as DropdownOption,
    { label: '退出登录', key: 'logout' },
  )
  return opts
})

async function onMenuSelect(key: string): Promise<void> {
  if (key === 'logout') {
    await auth.logout()
    void router.push({ name: 'login' })
  }
}

function goProjects(): void {
  void router.push({ name: 'projects' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <button type="button" class="brand" aria-label="织影 首页" @click="goProjects">
        <BrandMark :size="26" tone="bone" />
        <span class="brand-name font-display">织影</span>
        <span class="brand-en font-mono">WEAVEORA</span>
      </button>

      <div class="topbar-right">
        <NButton
          v-if="hasWorkspace"
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
