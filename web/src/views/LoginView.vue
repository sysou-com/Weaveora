<script setup lang="ts">
import { ArrowRight, Clapperboard } from 'lucide-vue-next'
import { NAlert, NButton, NIcon, NInput } from 'naive-ui'
import type { InputHTMLAttributes } from 'vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

type Panel = 'login' | 'register'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const panel = ref<Panel>(route.query.panel === 'register' ? 'register' : 'login')
const busy = ref(false)
const errorText = ref<string | null>(null)

const account = ref('')
const password = ref('')
const regEmail = ref('')
const regDisplay = ref('')
const regPassword = ref('')
const regConfirm = ref('')

const redirectTo = (): string => {
  const q = route.query.redirect
  return typeof q === 'string' && q.startsWith('/') ? q : '/app'
}

/** NInput 的 input-props 透传 data-testid（直接对象字面量会被 TS 拦截 data-* 属性） */
function testAttrs(id: string): InputHTMLAttributes {
  return { 'data-testid': id } as InputHTMLAttributes
}

function switchPanel(to: Panel): void {
  if (panel.value === to) return
  panel.value = to
  errorText.value = null
}

function setError(e: unknown): void {
  if (e instanceof ApiError) {
    errorText.value = e.message
  } else if (e instanceof Error) {
    errorText.value = e.message
  } else {
    errorText.value = '请求失败，请稍后再试'
  }
}

async function submitLogin(): Promise<void> {
  if (busy.value) return
  errorText.value = null
  if (!account.value.trim()) {
    errorText.value = '请输入邮箱或手机号'
    return
  }
  if (!password.value) {
    errorText.value = '请输入密码'
    return
  }
  busy.value = true
  try {
    await auth.login(account.value.trim(), password.value)
    await router.replace(redirectTo())
  } catch (e) {
    setError(e)
  } finally {
    busy.value = false
  }
}

async function submitRegister(): Promise<void> {
  if (busy.value) return
  errorText.value = null
  const email = regEmail.value.trim()
  const display = regDisplay.value.trim()
  if (!email) {
    errorText.value = '请输入邮箱'
    return
  }
  if (!/^\S+@\S+\.\S+$/.test(email)) {
    errorText.value = '邮箱格式不正确'
    return
  }
  if (regPassword.value.length < 8 || regPassword.value.length > 72) {
    errorText.value = '密码需为 8–72 位'
    return
  }
  if (regPassword.value !== regConfirm.value) {
    errorText.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  try {
    await auth.register({
      email,
      displayName: display || undefined,
      password: regPassword.value,
    })
    await router.replace(redirectTo())
  } catch (e) {
    setError(e)
  } finally {
    busy.value = false
  }
}

// 已持有 token 但会话未恢复（如直接刷新登录页）→ 静默补 /me 后进应用
onMounted(async () => {
  if (!auth.hasSession()) return
  try {
    await auth.ensureUser()
    await router.replace(redirectTo())
  } catch {
    auth.clearSession()
  }
})
</script><template>
  <div class="login-root">
    <div class="login-stage">
      <header class="login-hero">
        <BrandMark :size="52" tone="teal" />
        <div class="wordmark">
          <h1 class="zh">织影</h1>
          <div class="en font-mono">WEAVEORA</div>
        </div>
        <p class="tagline">
          把一句话，织成画面与短片。<br />
          <span class="sub">From a sentence to a shot.</span>
        </p>
      </header>

      <section class="login-card" aria-label="账号面板">
        <div class="tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="panel === 'login'"
            :class="['tab', { active: panel === 'login' }]"
            @click="switchPanel('login')"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="panel === 'register'"
            :class="['tab', { active: panel === 'register' }]"
            @click="switchPanel('register')"
          >
            注册
          </button>
        </div>

        <form v-if="panel === 'login'" class="form" novalidate @submit.prevent="submitLogin">
          <label class="field">
            <span class="field-label">邮箱或手机号</span>
            <NInput
              v-model:value="account"
              size="large"
              placeholder="you@example.com"
              autocomplete="username"
              :input-props="testAttrs('login-account')"
            />
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <NInput
              v-model:value="password"
              size="large"
              type="password"
              show-password-on="click"
              placeholder="请输入密码"
              autocomplete="current-password"
              :input-props="testAttrs('login-password')"
            />
          </label>

          <NButton
            attr-type="submit"
            size="large"
            type="primary"
            block
            :loading="busy"
            class="submit"
            data-testid="login-submit"
          >
            <template #icon v-if="!busy">
              <NIcon><ArrowRight :size="16" /></NIcon>
            </template>
            进入放映厅
          </NButton>

          <p class="switch-hint">
            还没有账号？
            <button type="button" class="link" @click="switchPanel('register')">创建一个</button>
          </p>
        </form>

        <form v-else class="form" novalidate @submit.prevent="submitRegister">
          <label class="field">
            <span class="field-label">邮箱</span>
            <NInput
              v-model:value="regEmail"
              size="large"
              placeholder="you@example.com"
              autocomplete="email"
              :input-props="testAttrs('reg-email')"
            />
          </label>

          <label class="field">
            <span class="field-label">昵称（可选）</span>
            <NInput
              v-model:value="regDisplay"
              size="large"
              placeholder="怎么称呼你"
              :maxlength="50"
              :input-props="testAttrs('reg-display')"
            />
          </label>

          <div class="field-row">
            <label class="field">
              <span class="field-label">密码（8–72 位）</span>
              <NInput
                v-model:value="regPassword"
                size="large"
                type="password"
                show-password-on="click"
                placeholder="至少 8 位"
                autocomplete="new-password"
                :input-props="testAttrs('reg-password')"
              />
            </label>
            <label class="field">
              <span class="field-label">确认密码</span>
              <NInput
                v-model:value="regConfirm"
                size="large"
                type="password"
                show-password-on="click"
                placeholder="再输入一次"
                autocomplete="new-password"
                :input-props="testAttrs('reg-confirm')"
              />
            </label>
          </div>

          <NButton
            attr-type="submit"
            size="large"
            type="primary"
            block
            :loading="busy"
            class="submit"
            data-testid="register-submit"
          >
            <template #icon v-if="!busy">
              <NIcon><Clapperboard :size="16" /></NIcon>
            </template>
            注册并开始创作
          </NButton>

          <p class="switch-hint">
            已有账号？
            <button type="button" class="link" @click="switchPanel('login')">直接登录</button>
          </p>
        </form>

        <transition name="fade">
          <NAlert
            v-if="errorText"
            type="error"
            :show-icon="false"
            :bordered="false"
            class="error-banner"
            data-testid="form-error"
          >
            {{ errorText }}
          </NAlert>
        </transition>
      </section>

      <footer class="login-foot font-mono">
        <span>AI 导演 · 确认闸门 · 多引擎中立</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.login-root {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background: var(--wv-bg);
}

.login-stage {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.login-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-bottom: 30px;
}

.wordmark {
  margin-top: 14px;
}
.wordmark .zh {
  font-size: 44px;
  line-height: 1.15;
  letter-spacing: 0.16em;
  text-indent: 0.16em; /* 抵消字距产生的视觉偏移，保持居中 */
  color: var(--wv-text);
}
.wordmark .en {
  margin-top: 4px;
  font-size: 11px;
  letter-spacing: 0.5em;
  text-indent: 0.5em;
  color: var(--wv-accent-text);
}

.tagline {
  margin: 18px 0 0;
  font-size: 15px;
  line-height: 1.9;
  color: var(--wv-text-2);
}
.tagline .sub {
  color: var(--wv-text-3);
  font-size: 12px;
  letter-spacing: 0.06em;
}

.login-card {
  width: 100%;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 22px 24px 24px;
}

.tabs {
  display: flex;
  gap: 22px;
  margin: 0 0 20px;
  border-bottom: 1px solid var(--wv-divider);
}
.tab {
  appearance: none;
  background: none;
  border: none;
  padding: 0 0 10px;
  font-size: 15px;
  color: var(--wv-text-3);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color var(--wv-dur) var(--wv-ease);
}
.tab.active {
  color: var(--wv-text);
  border-bottom-color: var(--wv-accent);
}
.tab:hover {
  color: var(--wv-text-2);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}
.field-row {
  display: flex;
  gap: 12px;
}
.field-label {
  font-size: 13px;
  color: var(--wv-text-3);
}

.submit {
  margin-top: 6px;
}

.switch-hint {
  margin: 4px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--wv-text-3);
}
.link {
  appearance: none;
  background: none;
  border: none;
  padding: 0;
  font-size: 13px;
  color: var(--wv-accent-text);
  cursor: pointer;
  transition: color var(--wv-dur) var(--wv-ease);
}
.link:hover {
  color: var(--wv-text);
}

.error-banner {
  margin-top: 14px;
}

.login-foot {
  margin-top: 26px;
  font-size: 10px;
  letter-spacing: 0.3em;
  color: var(--wv-text-4);
  text-align: center;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--wv-dur) var(--wv-ease);
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
