<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { NIcon, useMessage } from 'naive-ui'
import { ArrowLeft, Film, X } from 'lucide-vue-next'
import { reactive, ref } from 'vue'

import { fetchMarketAssetBlob, listMarketAssets, marketProject, toggleMark } from '@/api/market'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, formatDateShort, modeLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const message = useMessage()
const logged = auth.hasSession()
const projectId = String(route.params.projectId ?? '')

const meta = ref<Awaited<ReturnType<typeof marketProject>> | null>(null)
const assets = ref<Awaited<ReturnType<typeof listMarketAssets>>>([])
const urls = reactive<Record<string, string>>({})
const loadErr = ref<string | null>(null)

async function load(): Promise<void> {
  try {
    meta.value = await marketProject(projectId)
    assets.value = await listMarketAssets(projectId)
    const batch = assets.value.slice(0, 40)
    for (const a of batch) {
      void fetchMarketAssetBlob(projectId, a.id).then((b) => {
        if (b) urls[a.id] = URL.createObjectURL(b)
      })
    }
  } catch {
    loadErr.value = '该项目不可见或已下架'
  }
}
void load()

function firstFrame(e: Event): void {
  const v = e.target as HTMLVideoElement
  if (v.readyState >= 2) v.currentTime = 0.05
  else v.addEventListener('loadedmetadata', () => { v.currentTime = 0.05 }, { once: true })
}

const player = ref<{ id: string; url: string } | null>(null)

async function doMark(kind: 'like' | 'fav'): Promise<void> {
  if (!meta.value) return
  try {
    const r = await toggleMark(projectId, kind)
    const m = meta.value
    if (r.kind === 'like') {
      m.likeCount = r.count
      m.liked = r.active
    } else {
      m.favoriteCount = r.count
      m.favorited = r.active
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败，请先登录')
  }
}
</script>

<template>
  <div class="page">
    <header class="top">
      <button type="button" class="back" @click="router.back()"><ArrowLeft :size="15" /> 返回</button>
      <h1 class="title font-display">{{ meta?.title ?? '项目详情' }}</h1>
      <p v-if="meta" class="meta font-mono">
        {{ modeLabel(meta.mode) }} · {{ meta.aspectRatio }} {{ aspectNote(meta.aspectRatio) }}
        <template v-if="meta.mode === 'video' && meta.durationSec"> · {{ meta.durationSec }}s</template>
        · 分享者 {{ meta.ownerName || '匿名' }} · 更新 {{ formatDateShort(meta.updatedAt) }}
      </p>
      <p class="note text-secondary">
        集市项目为只读浏览：不含分镜/任务/成片等创作内容，不能编辑或生成。
      </p>
      <div v-if="meta && logged" class="ops">
        <button type="button" class="op" :class="{ on: meta.liked }" @click="doMark('like')">
          {{ meta.liked ? '♥' : '♡' }} 赞 {{ meta.likeCount }}
        </button>
        <button type="button" class="op" :class="{ on: meta.favorited }" @click="doMark('fav')">
          {{ meta.favorited ? '★' : '☆' }} 收藏 {{ meta.favoriteCount }}
        </button>
      </div>
    </header>

    <p v-if="loadErr" class="empty text-secondary">{{ loadErr }}</p>

    <section v-else class="assets">
      <div v-if="!assets.length" class="empty text-secondary">该集市项目还没有可浏览的素材。</div>
      <div v-else class="grid">
        <div v-for="a in assets" :key="a.id" class="item">
          <video
            v-if="a.mime.startsWith('video/') && urls[a.id]"
            :src="urls[a.id]"
            class="media"
            controls
            muted
            playsinline
            preload="metadata"
            @loadedmetadata="firstFrame"
          />
          <img v-else-if="urls[a.id]" :src="urls[a.id]" class="media" alt="" loading="lazy" />
          <div v-else class="ph"><NIcon size="22"><Film /></NIcon></div>
          <div class="meta-line">
            <span class="font-mono">{{ a.kind }}<template v-if="a.width"> · {{ a.width }}×{{ a.height }}</template></span>
            <button v-if="a.mime.startsWith('video/') && urls[a.id]" type="button" class="op small" @click="player = { id: a.id, url: urls[a.id] }">全屏播放</button>
          </div>
        </div>
      </div>
    </section>

    <!-- 沉浸播放 -->
    <div v-if="player" class="overlay" @click.self="player = null">
      <div class="overlay-in">
        <button type="button" class="close" @click="player = null"><X :size="16" /></button>
        <video :src="player.url" class="big" controls autoplay muted playsinline preload="metadata" @loadedmetadata="firstFrame" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 18px; width: 100%; max-width: 1120px; margin: 0 auto; padding: 30px 28px 60px; }
.top { display: flex; flex-direction: column; gap: 10px; }
.back { display: inline-flex; align-items: center; gap: 6px; align-self: flex-start; background: none; border: none; color: var(--wv-text-3); cursor: pointer; font-size: 13px; padding: 4px 6px; border-radius: 6px; }
.back:hover { color: var(--wv-text); background: var(--wv-surface); }
.title { margin: 0; font-size: 28px; }
.meta { margin: 0; font-size: 12px; color: var(--wv-text-3); }
.note { margin: 0; font-size: 12.5px; }
.ops { display: flex; gap: 8px; }
.assets { display: flex; flex-direction: column; gap: 14px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.item { border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); overflow: hidden; background: var(--wv-surface); }
.media { width: 100%; aspect-ratio: 16/9; object-fit: cover; display: block; background: #000; }
img.media { object-fit: cover; }
.ph { width: 100%; aspect-ratio: 16/9; display: flex; align-items: center; justify-content: center; color: var(--wv-text-4); background: var(--wv-surface-sunken); }
.meta-line { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 10px; font-size: 11px; color: var(--wv-text-4); }
.op { appearance: none; border: 1px solid var(--wv-line-strong); background: transparent; color: var(--wv-text-3); font-size: 12px; padding: 6px 12px; border-radius: 6px; cursor: pointer; }
.op.small { padding: 3px 8px; }
.op:hover { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.on { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); background: var(--wv-accent-soft); }
.empty { padding: 30px; text-align: center; border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m); }
.overlay { position: fixed; inset: 0; z-index: 80; background: rgba(8,8,7,.92); display: flex; align-items: center; justify-content: center; padding: 24px; }
.overlay-in { position: relative; width: min(1080px, 100%); }
.big { width: 100%; max-height: 90vh; border-radius: 8px; background: #000; }
.close { position: absolute; top: -34px; right: 0; color: var(--wv-text); background: none; border: 1px solid var(--wv-line); border-radius: 6px; cursor: pointer; padding: 4px 8px; }
</style>
