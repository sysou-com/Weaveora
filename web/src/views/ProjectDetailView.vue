<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArrowLeft, Construction, RotateCw } from 'lucide-vue-next'
import { NButton, NIcon, NSkeleton } from 'naive-ui'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getProject } from '@/api/projects'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, formatDate, modeLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')
const projectId = computed(() => String(route.params.projectId ?? ''))

const { data: project, isPending, isError, refetch } = useQuery({
  queryKey: computed(() => ['project', workspaceId.value, projectId.value]),
  queryFn: () => getProject(workspaceId.value, projectId.value),
  enabled: computed(() => workspaceId.value !== '' && projectId.value !== ''),
})
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="router.push({ name: 'projects' })">
      <NIcon size="15"><ArrowLeft /></NIcon>
      <span>返回项目列表</span>
    </button>

    <div v-if="isPending" class="detail-head">
      <NSkeleton height="34px" width="42%" text />
      <NSkeleton height="14px" width="26%" text style="margin-top: 12px" />
    </div>

    <div v-else-if="isError" class="error-card">
      <p class="text-secondary">项目不存在或无权访问。</p>
      <NButton size="small" @click="() => refetch()"><template #icon><NIcon><RotateCw :size="14" /></NIcon></template>重试</NButton>
    </div>

    <template v-else-if="project">
      <header class="detail-head">
        <p class="eyebrow font-mono">
          {{ modeLabel(project.mode) }} · {{ project.aspectRatio }} {{ aspectNote(project.aspectRatio)
          }}<template v-if="project.mode === 'video' && project.durationSec"> · {{ project.durationSec }}s</template>
        </p>
        <h1 class="detail-title font-display">{{ project.title }}</h1>
        <p class="detail-meta font-mono text-secondary">
          {{ project.status }} · 创建于 {{ formatDate(project.createdAt) }} · {{ project.id }}
        </p>
      </header>

      <section class="placeholder">
        <div class="placeholder-icon">
          <NIcon size="26"><Construction /></NIcon>
        </div>
        <h2 class="placeholder-title">项目已就绪，导演台正在路上</h2>
        <p class="placeholder-copy text-secondary">
          W2 将在这里点亮导演层：一句话 Brief → 可编辑的提示词 / 剧本 / 分镜 → 人工确认后再生成。
          当前里程碑（W1）已完成账号与空项目的全链路落库。
        </p>
        <div class="placeholder-actions">
          <NButton size="small" @click="router.push({ name: 'projects' })">回项目列表</NButton>
          <NButton size="small" secondary disabled title="W2 开放">写 Brief（W2）</NButton>
        </div>
      </section>
    </template>
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

.detail-head {
  padding-bottom: 4px;
}
.eyebrow {
  margin: 0 0 10px;
  font-size: 11px;
  letter-spacing: 0.3em;
  color: var(--wv-text-4);
  text-transform: uppercase;
}
.detail-title {
  font-size: 34px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}
.detail-meta {
  margin: 10px 0 0;
  font-size: 12px;
  word-break: break-all;
}

.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 14px;
  padding: 52px 26px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
}
.placeholder-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: 1px solid var(--wv-line);
  color: var(--wv-accent);
  background: var(--wv-accent-soft);
}
.placeholder-title {
  font-size: 20px;
}
.placeholder-copy {
  max-width: 460px;
  margin: 0;
  font-size: 13.5px;
  line-height: 1.9;
}
.placeholder-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}
</style>
