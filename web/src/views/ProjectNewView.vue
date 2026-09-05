<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ArrowLeft, Film, Image as ImageIcon, Layers, Sparkles } from 'lucide-vue-next'
import { NButton, NForm, NFormItem, NIcon, NInput, NSelect, useMessage } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { createProject } from '@/api/projects'
import type { ProjectMode } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { ASPECT_OPTIONS, DEFAULT_VIDEO_DURATION, VIDEO_DURATIONS, aspectNote } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const queryClient = useQueryClient()

const title = ref('')
const mode = ref<ProjectMode>('image')
const aspectRatio = ref('16:9')
const durationSec = ref<number | null>(DEFAULT_VIDEO_DURATION)

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')
const showDuration = computed(() => mode.value === 'video')

const modeOptions: Array<{ value: ProjectMode; label: string; hint: string }> = [
  { value: 'image', label: '图片', hint: '一张成片 / 批量一致素材' },
  { value: 'video', label: '视频', hint: '剧本 · 分镜 · 短片' },
  { value: 'mixed', label: '混合', hint: '静帧与视频混排' },
]

function pickMode(m: ProjectMode): void {
  mode.value = m
}

const summary = computed(() => {
  const parts: string[] = []
  if (mode.value === 'image') parts.push('图片流')
  if (mode.value === 'video') parts.push('视频流 · 分镜先行')
  if (mode.value === 'mixed') parts.push('图片 + 视频')
  const ar = ASPECT_OPTIONS.find((a) => a.value === aspectRatio.value)
  if (ar) parts.push(ar.label)
  if (mode.value === 'video' && durationSec.value) parts.push(`${durationSec.value} 秒成片`)
  return parts.join(' / ')
})

/** 画幅小矩形预览（长边 56px） */
function frameStyle(aspect: string): Record<string, string> {
  const [w, h] = aspect.split(':').map((n) => Number(n))
  if (!w || !h) return {}
  const longSide = 54
  const ratio = h / w
  const css: Record<string, string> = {}
  if (ratio >= 1) {
    css.width = `${Math.round(longSide / ratio)}px`
    css.height = `${longSide}px`
  } else {
    css.width = `${longSide}px`
    css.height = `${Math.round(longSide * ratio)}px`
  }
  return css
}

const mutation = useMutation({
  mutationFn: () =>
    createProject(workspaceId.value, {
      title: title.value.trim(),
      mode: mode.value,
      aspectRatio: aspectRatio.value,
      durationSec: mode.value === 'video' ? durationSec.value : null,
    }),
  onSuccess: (project) => {
    void queryClient.invalidateQueries({ queryKey: ['projects'] })
    message.success(`已创建「${project.title}」`)
    void router.push({ name: 'project-detail', params: { projectId: project.id } })
  },
  onError: (e) => {
    message.error(e instanceof Error ? e.message : '创建失败，请稍后再试')
  },
})

function submit(): void {
  if (!title.value.trim()) {
    message.error('请给项目起个名字（将用于成片命名）')
    return
  }
  mutation.mutate()
}
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="router.back()">
      <NIcon size="15"><ArrowLeft /></NIcon>
      <span>返回项目列表</span>
    </button>

    <div class="new-layout">
      <section class="panel">
        <header class="panel-head">
          <p class="eyebrow font-mono">NEW PROJECT</p>
          <h1 class="panel-title">新建项目</h1>
          <p class="panel-desc text-secondary">先定画框与流程，Brief 与导演方案在下一步展开。</p>
        </header>

        <NForm class="form" :show-feedback="false">
          <NFormItem label="项目标题" path="title">
            <NInput
              v-model:value="title"
              size="large"
              placeholder="例如：雨夜纸船 / 巴洛克图书馆 / 新品主视觉"
              :maxlength="100"
              show-count
              data-testid="new-project-title"
            />
          </NFormItem>

          <NFormItem label="创作类型">
            <div class="mode-grid" role="radiogroup" aria-label="创作类型">
              <button
                v-for="opt in modeOptions"
                :key="opt.value"
                type="button"
                role="radio"
                :aria-checked="mode === opt.value"
                :class="['mode-card', { active: mode === opt.value }]"
                @click="pickMode(opt.value)"
              >
                <span class="mode-icon">
                  <NIcon v-if="opt.value === 'image'"><ImageIcon :size="18" /></NIcon>
                  <NIcon v-else-if="opt.value === 'video'"><Film :size="18" /></NIcon>
                  <NIcon v-else><Layers :size="18" /></NIcon>
                </span>
                <span class="mode-name">{{ opt.label }}</span>
                <span class="mode-hint">{{ opt.hint }}</span>
              </button>
            </div>
          </NFormItem>

          <NFormItem label="画幅">
            <NSelect
              v-model:value="aspectRatio"
              :options="ASPECT_OPTIONS"
              size="large"
              data-testid="new-project-aspect"
            />
          </NFormItem>

          <NFormItem v-if="showDuration" label="目标时长">
            <NSelect
              v-model:value="durationSec"
              :options="VIDEO_DURATIONS.map((d) => ({ label: d.label, value: d.value }))"
              size="large"
              data-testid="new-project-duration"
            />
          </NFormItem>

          <p class="summary font-mono text-secondary">
            <Sparkles :size="13" style="vertical-align: -2px" />
            &nbsp;{{ summary }}
          </p>

          <NButton
            type="primary"
            size="large"
            block
            class="create"
            :loading="mutation.isPending.value"
            :disabled="!workspaceId"
            data-testid="create-project-submit"
            @click="submit"
          >
            {{ workspaceId ? '创建项目' : '正在初始化工作区…' }}
          </NButton>
        </NForm>
      </section>

      <aside class="side">
        <div class="side-card">
          <p class="side-title font-mono">画框参考</p>
          <div v-for="a in ASPECT_OPTIONS" :key="a.value" class="frame-row">
            <span :class="['frame', { on: aspectRatio === a.value }]" :style="frameStyle(a.value)" aria-hidden="true" />
            <span class="frame-label">{{ a.value }} · {{ aspectNote(a.value) }}</span>
          </div>
        </div>

        <div class="side-card dim">
          <p class="side-title font-mono">W1 · 当前能力</p>
          <ul class="todo">
            <li><span class="ok">✓</span> 登录 / 注册 · 工作区隔离</li>
            <li><span class="ok">✓</span> 创建空项目</li>
            <li class="next"><span>→</span> W2 导演层：一句话 → 可编辑方案</li>
            <li class="next"><span>→</span> W3 确认闸门 → 生成任务</li>
          </ul>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 22px;
  max-width: 880px;
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 6px 10px;
  margin-left: -10px;
  background: none;
  border: none;
  border-radius: var(--wv-radius-s);
  color: var(--wv-text-3);
  font-size: 13.5px;
  cursor: pointer;
  transition: color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.back:hover {
  color: var(--wv-text);
  background: var(--wv-surface);
}

.new-layout {
  display: grid;
  grid-template-columns: 1fr 240px;
  gap: 26px;
  align-items: start;
}

.panel {
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 26px 28px 30px;
}

.panel-head {
  margin-bottom: 22px;
}
.eyebrow {
  margin: 0 0 6px;
  font-size: 11px;
  letter-spacing: 0.34em;
  color: var(--wv-text-4);
}
.panel-title {
  font-size: 26px;
}
.panel-desc {
  margin: 8px 0 0;
  font-size: 13.5px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
}
.mode-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px 16px 14px;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  color: var(--wv-text-2);
  cursor: pointer;
  text-align: left;
  transition:
    border-color var(--wv-dur) var(--wv-ease),
    background var(--wv-dur) var(--wv-ease);
}
.mode-card:hover {
  border-color: var(--wv-line-strong);
}
.mode-card.active {
  border-color: var(--wv-accent);
  background: var(--wv-accent-soft);
}
.mode-icon {
  color: var(--wv-text-3);
  margin-bottom: 4px;
}
.mode-card.active .mode-icon {
  color: var(--wv-accent);
}
.mode-name {
  font-size: 15px;
  font-weight: 600;
}
.mode-hint {
  font-size: 12px;
  color: var(--wv-text-4);
}

.summary {
  margin: 2px 0 0;
  font-size: 12px;
}
.create {
  margin-top: 6px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.side-card {
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  padding: 18px 18px 16px;
}
.side-card.dim {
  background: transparent;
}
.side-title {
  margin: 0 0 14px;
  font-size: 11px;
  letter-spacing: 0.2em;
  color: var(--wv-text-4);
}

.frame-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 5px 0;
}
.frame {
  display: inline-block;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  border-radius: 3px;
  flex: none;
  transition: border-color var(--wv-dur) var(--wv-ease);
}
.frame.on {
  border-color: var(--wv-accent);
  background: var(--wv-accent-soft);
}
.frame-label {
  font-size: 12px;
  color: var(--wv-text-3);
  font-family: var(--wv-font-mono);
}

.todo {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.todo li {
  font-size: 12.5px;
  color: var(--wv-text-3);
  display: flex;
  gap: 8px;
  line-height: 1.5;
}
.todo li span {
  flex: none;
  color: var(--wv-text-4);
  font-family: var(--wv-font-mono);
}
.todo li .ok {
  color: var(--wv-success);
}
.todo li.next {
  color: var(--wv-text-4);
}

@media (max-width: 860px) {
  .new-layout {
    grid-template-columns: 1fr;
  }
  .side {
    display: none;
  }
}
</style>
