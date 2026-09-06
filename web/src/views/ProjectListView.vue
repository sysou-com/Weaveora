<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight, Plus } from 'lucide-vue-next'
import { NButton, NIcon, NSkeleton } from 'naive-ui'
import { computed, onUnmounted, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'

import { listAssets, fetchAssetBlob } from '@/api/assets'
import { listProjects } from '@/api/projects'
import type { Project } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, formatDateShort, modeLabel } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')

const { data: projects, isPending, isError, refetch } = useQuery({
  queryKey: computed(() => ['projects', workspaceId.value]),
  queryFn: () => listProjects(workspaceId.value),
  enabled: computed(() => workspaceId.value !== ''),
})

const count = computed(() => projects.value?.length ?? 0)

// 卡片缩略图：每个项目取“资产库最新一条”产物预览；无资产→系统默认图标
interface Thumb {
  state: 'none' | 'loading' | 'ok'
  mime?: string
  url?: string
}
const thumbs = reactive<Record<string, Thumb>>({})
const thumbsLoading = new Set<string>()

async function loadThumb(pid: string, ws: string): Promise<void> {
  if (thumbsLoading.has(pid)) return
  thumbsLoading.add(pid)
  thumbs[pid] = { state: 'loading' }
  try {
    const list = await listAssets(ws, pid)
    // 缩略图只用图片类资产（视频在资产库同样无预览帧/黑屏）；无图则回落系统默认图标
    const img = list.find((a) => (a.mime ?? '').startsWith('image/'))
    if (!img) {
      thumbs[pid] = { state: 'none' }
      return
    }
    const blob = await fetchAssetBlob(ws, img.id)
    if (!blob) {
      thumbs[pid] = { state: 'none' }
      return
    }
    thumbs[pid] = { state: 'ok', mime: img.mime, url: URL.createObjectURL(blob) }
  } catch {
    thumbs[pid] = { state: 'none' }
  } finally {
    thumbsLoading.delete(pid)
  }
}

watch(
  () => projects.value,
  (list) => {
    const ws = workspaceId.value
    if (!ws) return
    for (const p of list ?? []) {
      void loadThumb(p.id, ws)
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  for (const t of Object.values(thumbs)) {
    if (t.url) URL.revokeObjectURL(t.url)
  }
})

function openProject(project: Project): void {
  void router.push({ name: 'project-detail', params: { projectId: project.id } })
}

function goNew(): void {
  void router.push({ name: 'project-new' })
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div class="head-copy">
        <p class="eyebrow font-mono">PROJECTS</p>
        <h1 class="title">项目</h1>
        <p class="desc text-secondary">
          {{ count > 0 ? `${count} 个项目正在放映厅里等待开机` : '你的创作工作台' }}
        </p>
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="isPending" class="grid">
      <div v-for="i in 6" :key="i" class="skel">
        <NSkeleton height="18px" width="55%" text />
        <NSkeleton height="14px" width="38%" text style="margin-top: 14px" />
        <NSkeleton height="12px" width="65%" text style="margin-top: auto" />
      </div>
    </div>

    <!-- 错误态 -->
    <div v-else-if="isError" class="error-card">
      <p class="text-secondary">项目列表加载失败，请确认后端已启动。</p>
      <NButton size="small" @click="() => refetch()">重试</NButton>
    </div>

    <!-- 空状态（§9.4：用一句话降低白屏恐惧） -->
    <div v-else-if="count === 0" class="empty-state">
      <svg class="empty-frame" width="92" height="92" viewBox="0 0 96 96" fill="none" aria-hidden="true">
        <rect x="12" y="22" width="72" height="52" rx="9" stroke="#6F6A60" stroke-width="2" />
        <rect x="22" y="32" width="52" height="32" rx="4" stroke="#3A362F" stroke-width="2" />
        <path d="M34 48L60 48" stroke="#8FB9B4" stroke-width="2" stroke-linecap="round" />
        <path d="M20 48H29M67 48H76" stroke="#6F6A60" stroke-width="2" stroke-linecap="round" />
      </svg>
      <h2 class="empty-title">还没有项目</h2>
      <p class="empty-copy text-secondary">
        新建一个项目，用一句话开始——导演层会把它织成画面与分镜。<br />
        风格模板、确认闸门与任务流将在 W2–W3 相继点亮。
      </p>
      <NButton type="primary" size="large" data-testid="btn-new-project-empty" @click="goNew">
        <template #icon>
          <NIcon><Plus :size="16" /></NIcon>
        </template>
        新建第一个项目
      </NButton>
    </div>

    <!-- 项目卡片墙 -->
    <div v-else class="grid">
      <button
        v-for="p in projects"
        :key="p.id"
        type="button"
        class="card"
        :data-testid="`project-card-${p.mode}`"
        @click="openProject(p)"
      >
        <div class="thumb" data-testid="project-thumb">
          <img v-if="thumbs[p.id]?.state === 'ok' && thumbs[p.id]?.url" :src="thumbs[p.id]?.url" class="thumb-media" alt="" loading="lazy" />
          <div v-else class="thumb-ph">
            <svg width="34" height="34" viewBox="0 0 96 96" fill="none" aria-hidden="true">
              <rect x="12" y="22" width="72" height="52" rx="9" stroke="#6F6A60" stroke-width="3" />
              <rect x="22" y="32" width="52" height="32" rx="4" stroke="#3A362F" stroke-width="3" />
              <path d="M20 47H76" stroke="#3A362F" stroke-width="3" />
              <path d="M40 32L40 64M56 32L56 64" stroke="#8FB9B4" stroke-width="3" />
            </svg>
            <span v-if="thumbs[p.id]?.state === 'loading'" class="thumb-load font-mono">加载预览…</span>
            <span v-else class="thumb-load font-mono">待生成</span>
          </div>
        </div>
        <div class="card-top">
          <span class="card-mode font-mono">{{ modeLabel(p.mode) }}</span>
          <span class="card-status font-mono" :title="p.status">{{ p.status }}</span>
        </div>
        <h2 class="card-title font-display">{{ p.title }}</h2>
        <p class="card-meta font-mono text-secondary">
          {{ p.aspectRatio }} {{ aspectNote(p.aspectRatio) }}
          <template v-if="p.mode === 'video' && p.durationSec">· {{ p.durationSec }}s</template>
        </p>
        <div class="card-foot">
          <span class="card-date font-mono">{{ formatDateShort(p.createdAt) }}</span>
          <NIcon size="16" class="card-arrow"><ArrowRight /></NIcon>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  margin: 0 0 8px;
  font-size: 11px;
  letter-spacing: 0.34em;
  color: var(--wv-text-4);
}
.title {
  font-size: 30px;
  line-height: 1.2;
}
.desc {
  margin: 8px 0 0;
  font-size: 14px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(252px, 1fr));
  gap: 16px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 168px;
  padding: 18px 18px 14px;
  text-align: left;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  color: var(--wv-text);
  cursor: pointer;
  transition:
    border-color var(--wv-dur) var(--wv-ease),
    background var(--wv-dur) var(--wv-ease),
    transform var(--wv-dur) var(--wv-ease);
}
.card:hover {
  background: var(--wv-surface-raised);
  border-color: color-mix(in srgb, var(--wv-accent) 38%, var(--wv-line));
  transform: translateY(-1px);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 卡片缩略图：最新资产预览 / 默认图标 */
.thumb {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  overflow: hidden;
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb-media {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-video {
  background: #000;
}
.thumb-ph {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--wv-text-4);
}
.thumb-load {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-text-4);
}
.card-mode {
  font-size: 11px;
  letter-spacing: 0.18em;
  color: var(--wv-accent-text);
  text-transform: uppercase;
}
.card-status {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--wv-text-4);
  text-transform: uppercase;
}

.card-title {
  font-size: 18px;
  line-height: 1.35;
  color: var(--wv-text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  margin: 0;
  font-size: 12px;
}

.card-foot {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--wv-divider);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-date {
  font-size: 11px;
  color: var(--wv-text-4);
}
.card-arrow {
  color: var(--wv-text-4);
  transition:
    color var(--wv-dur) var(--wv-ease),
    transform var(--wv-dur) var(--wv-ease);
}
.card:hover .card-arrow {
  color: var(--wv-accent);
  transform: translateX(2px);
}

.skel {
  min-height: 168px;
  padding: 20px;
  background: var(--wv-surface);
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.error-card {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 40px;
  border: 1px dashed var(--wv-line);
  border-radius: var(--wv-radius-m);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 14px;
  padding: 56px 20px;
  border: 1px dashed var(--wv-line-strong);
  border-radius: var(--wv-radius-m);
}
.empty-frame {
  margin-bottom: 6px;
}
.empty-title {
  font-size: 22px;
}
.empty-copy {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.9;
}
</style>
