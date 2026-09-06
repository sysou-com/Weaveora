<script setup lang="ts">
import { NDropdown, NModal, type DropdownOption } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'
import MarketHomeView from '@/views/MarketHomeView.vue'

const auth = useAuthStore()
const router = useRouter()
const logged = computed(() => auth.hasSession())
const showContact = ref(false)

const moreOptions: DropdownOption[] = [
  { label: '关于', key: 'about' },
  { label: '联系开发者', key: 'contact' },
]
async function onMoreSelect(key: string): Promise<void> {
  if (key === 'about') {
    void router.push({ name: 'about' })
  } else if (key === 'contact') {
    showContact.value = true
  }
}
const contact = { qq: '358532433', email: 'sysou.com@outlook.com' }
function login(): void {
  void router.push({ name: 'login' })
}
function register(): void {
  void router.push({ name: 'login', query: { panel: 'register' } })
}
</script>

<template>
  <div class="pub-shell">
    <header class="pub-topbar">
      <div class="pub-left">
        <button type="button" class="brand" @click="router.push({ name: 'pub-home' })">
          <BrandMark :size="24" tone="bone" />
          <span class="brand-name font-display">织影</span>
          <span class="brand-en font-mono">WEAVEORA</span>
        </button>
      </div>
      <nav class="pubnav">
        <button type="button" class="nav-link on" @click="router.push({ name: 'pub-home' })">首页</button>
        <button type="button" class="nav-link" @click="router.push({ name: 'guide' })">使用指南</button>
        <NDropdown :options="moreOptions" trigger="click" @select="onMoreSelect">
          <button type="button" class="nav-link more">更多 <span class="caret">▾</span></button>
        </NDropdown>
      </nav>
      <div class="pub-right">
        <template v-if="!logged">
          <button type="button" class="ghost" @click="login">登录</button>
          <button type="button" class="primary" @click="register">注册</button>
        </template>
        <button v-else type="button" class="primary" @click="router.push({ name: 'home' })">进入工作台</button>
      </div>
    </header>

    <main class="pub-main">
      <MarketHomeView />
    </main>

    <footer class="pub-footer">
      <span class="font-mono">FROM A SENTENCE TO A SHOT.</span>
    </footer>

    <NModal v-model:show="showContact" preset="card" :title="'联系开发者'" style="max-width: 420px">
      <p class="line">QQ：{{ contact.qq }}</p>
      <p class="line">邮箱：<a :href="`mailto:${contact.email}`">{{ contact.email }}</a></p>
    </NModal>
  </div>
</template>

<style scoped>
.pub-shell { min-height: 100vh; display: flex; flex-direction: column; }
.pub-topbar {
  position: sticky; top: 0; z-index: 20; height: 66px;
  display: flex; align-items: center; gap: 18px;
  padding: 0 30px;
  background: rgba(11, 11, 10, 0.86); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--wv-divider);
}
.pub-left { display: flex; align-items: center; }
.brand {
  display: inline-flex; align-items: center; gap: 9px; padding: 4px 6px; margin-left: -6px;
  background: none; border: none; color: var(--wv-text); cursor: pointer; border-radius: var(--wv-radius-s);
}
.brand-name { font-size: 21px; line-height: 1; }
.brand-en { font-size: 9px; letter-spacing: 0.3em; color: var(--wv-text-3); }
.pubnav { display: flex; align-items: center; gap: 6px; margin: 0 auto; }
.nav-link {
  appearance: none; background: none; border: none; position: relative; color: var(--wv-text-2);
  font-size: 15.5px; padding: 10px 15px; border-radius: 9px; cursor: pointer;
}
.nav-link:hover { color: var(--wv-text); background: var(--wv-surface); }
.nav-link.on { color: var(--wv-accent-text); background: var(--wv-accent-soft); }
.nav-link.more { display: inline-flex; align-items: center; gap: 4px; }
.caret { font-size: 10px; color: var(--wv-text-4); }
.pub-right { display: flex; align-items: center; gap: 10px; }
.ghost {
  appearance: none; background: none; border: 1px solid var(--wv-line-strong); color: var(--wv-text-2);
  font-size: 13px; padding: 7px 16px; border-radius: 8px; cursor: pointer;
}
.ghost:hover { border-color: var(--wv-accent-strong); color: var(--wv-text); }
.primary {
  appearance: none; border: none; background: var(--wv-accent); color: #0b0b0a;
  font-size: 13px; padding: 7px 16px; border-radius: 8px; cursor: pointer;
}
.primary:hover { background: var(--wv-accent-strong); }
.pub-main { flex: 1; width: 100%; max-width: 1120px; margin: 0 auto; padding: 34px 28px 56px; }
.pub-footer {
  padding: 16px 28px 24px; text-align: center; color: var(--wv-text-4);
  font-size: 10px; letter-spacing: 0.42em; border-top: 1px solid var(--wv-divider);
}
.line { margin: 0 0 8px; font-size: 14px; line-height: 1.8; }
.line a { color: var(--wv-accent-text); }
@media (max-width: 860px) { .pubnav { display: none; } }
</style>
