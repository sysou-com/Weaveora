<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Film, X } from 'lucide-vue-next'
import { NIcon, NSkeleton, useMessage } from 'naive-ui'
import { computed, reactive, ref, watch } from 'vue'

import { fetchMarketPreview, listMarketPage, toggleMark } from '@/api/market'
import type { ProjectCard } from '@/api/types'
import { marketThumb } from '@/utils/thumbs'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, formatDateShort, modeLabel } from '@/utils/format'

const PAGE_SIZE = 8
const auth = useAuthStore()
const message = useMessage()
const logged = auth.hasSession()

const page = ref(0)
const { data: market, isPending } = useQuery({
  queryKey: ['home-market', page],
  queryFn: () => listMarketPage(page.value, PAGE_SIZE),
})
const total = computed(() => market.value?.total ?? 0)
const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

const thumbs = reactive<Record<string, string>>({})
watch(
  () => market.value?.items,
  (items) => {
    for (const p of items ?? []) {
      if (thumbs[p.id]) continue
      void marketThumb(p.id).then((t) => {
        if (t) thumbs[p.id] = t.url
      })
    }
  },
  { immediate: true },
)

// 只读详情（含视频/图资产点击可由详情展开——本页先以卡片信息+预览图为主）
const detail = ref<ProjectCard | null>(null)

async function doMark(id: string, kind: 'like' | 'fav'): Promise<void> {
  try {
    const r = await toggleMark(id, kind)
    const it = (market.value?.items ?? []).find((x) => x.id === id)
    if (it) {
      if (r.kind === 'like') {
        it.likeCount = r.count
        it.liked = r.active
      } else {
        it.favoriteCount = r.count
        it.favorited = r.active
      }
    }
    if (detail.value?.id === id) {
      if (r.kind === 'like') {
        detail.value.likeCount = r.count
        detail.value.liked = r.active
      } else {
        detail.value.favoriteCount = r.count
        detail.value.favorited = r.active
      }
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function downloadPreview(id: string): Promise<void> {
  try {
    const blob = await fetchMarketPreview(id)
    if (!blob) {
      message.warning('暂无预览图')
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `weaveora-${id.slice(0, 8)}.png`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 4000)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '下载失败')
  }
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div class="head-copy">
        <p class="eyebrow font-mono">MARKETPLACE · 项目精选</p>
        <h1 class="title">项目精选</h1>
        <p class="desc text-secondary">
          精选值得一看的作品与素材：创作者分享、管理员审核后在此展示；可浏览、点赞、收藏与下载预览，创作请前往「我的项目」。
        </p>
      </div>
    </header>

    <section class="zone">
      <div v-if="isPending" class="grid">
        <div v-for="i in 4" :key="i" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      </div>
      <template v-else-if="total > 0">
        <div class="grid">
          <div v-for="p in market?.items ?? []" :key="p.id" class="card">
            <button type="button" class="card-inner" @click="detail = p">
              <div class="thumb">
                <img v-if="thumbs[p.id]" :src="thumbs[p.id]" class="thumb-media" alt="" loading="lazy" />
                <div v-else class="thumb-ph"><NIcon size="26" class="thumb-ic"><Film /></NIcon></div>
              </div>
              <div class="card-top">
                <span class="card-mode font-mono">{{ modeLabel(p.mode) }}</span>
                <span class="card-date font-mono">{{ p.ownerName || '匿名' }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">{{ p.aspectRatio }} {{ aspectNote(p.aspectRatio) }}</p>
              <div class="card-foot">
                <span class="card-date font-mono">{{ formatDateShort(p.updatedAt) }}</span>
              </div>
            </button>
            <div v-if="logged" class="mark-bar">
              <button type="button" class="mk" :class="{ on: p.liked }" @click="doMark(p.id, 'like')">
                {{ p.liked ? '♥' : '♡' }} {{ p.likeCount }}
              </button>
              <button type="button" class="mk" :class="{ on: p.favorited }" @click="doMark(p.id, 'fav')">
                {{ p.favorited ? '★' : '☆' }} {{ p.favoriteCount }}
              </button>
            </div>
          </div>
        </div>
        <nav v-if="pages > 1" class="pager">
          <button class="op" :disabled="page === 0" @click="page--">上一页</button>
          <span class="pager-info font-mono">{{ page + 1 }} / {{ pages }}</span>
          <button class="op" :disabled="page >= pages - 1" @click="page++">下一页</button>
        </nav>
      </template>
      <div v-else class="empty-state">
        <p class="text-secondary">集市还没有项目——客户分享、管理员审核通过后会展示在这里。</p>
      </div>
    </section>

    <!-- 只读详情 -->
    <div v-if="detail" class="overlay" @click.self="detail = null">
      <div class="overlay-card">
        <button type="button" class="op x" @click="detail = null"><X :size="14" /></button>
        <h3 class="overlay-title font-display">{{ detail.title }}</h3>
        <p class="overlay-meta font-mono">
          {{ modeLabel(detail.mode) }} · {{ detail.aspectRatio }}
          <template v-if="detail.mode === 'video' && detail.durationSec"> · {{ detail.durationSec }}s</template>
          · 分享者：{{ detail.ownerName || '匿名' }}
        </p>
        <div class="thumb big">
          <img v-if="thumbs[detail.id]" :src="thumbs[detail.id]" class="thumb-media" alt="" />
          <div v-else class="thumb-ph"><span class="text-secondary">暂无预览图</span></div>
        </div>
        <p class="overlay-note text-secondary">集市为只读浏览，不能编辑/生成；可在「我的项目」创作自己的作品。</p>
        <div class="overlay-ops">
          <template v-if="logged">
            <button type="button" class="op" :class="{ on: detail.liked }" @click="doMark(detail.id, 'like')">
              {{ detail.liked ? '♥' : '♡' }} 赞 {{ detail.likeCount }}
            </button>
            <button type="button" class="op" :class="{ on: detail.favorited }" @click="doMark(detail.id, 'fav')">
              {{ detail.favorited ? '★' : '☆' }} 收藏 {{ detail.favoriteCount }}
            </button>
          </template>
          <button type="button" class="op" @click="downloadPreview(detail.id)">下载预览图</button>
          <button type="button" class="op primary" @click="$router.push({ path: '/market/' + detail.id })">
            查看全部素材(只读)
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 22px; }
.page-head { }
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: 0.34em; color: var(--wv-text-4); }
.title { font-size: 30px; line-height: 1.2; }
.desc { margin: 8px 0 0; font-size: 14px; }
.zone { display: flex; flex-direction: column; gap: 14px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(252px, 1fr)); gap: 16px; }
.card { border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); background: var(--wv-surface); overflow: hidden; position: relative; transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease); }
.card:hover { background: var(--wv-surface-raised); border-color: color-mix(in srgb, var(--wv-accent) 38%, var(--wv-line)); }
.card-inner { appearance: none; border: none; background: none; width: 100%; padding: 14px 16px 8px; display: flex; flex-direction: column; gap: 10px; color: var(--wv-text); cursor: pointer; text-align: left; }
.thumb { width: 100%; aspect-ratio: 16/9; border-radius: 8px; overflow: hidden; background: var(--wv-surface-sunken); border: 1px solid var(--wv-line); display: flex; align-items: center; justify-content: center; pointer-events: none; }
.thumb.big { aspect-ratio: 16/9; }
.thumb-media { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-ph { display: flex; align-items: center; justify-content: center; }
.thumb-ic { color: var(--wv-text-4); }
.card-top { display: flex; justify-content: space-between; gap: 8px; align-items: center; }
.card-mode { font-size: 11px; letter-spacing: 0.16em; color: var(--wv-accent-text); text-transform: uppercase; }
.card-date { font-size: 11px; color: var(--wv-text-4); }
.card-title { margin: 0; font-size: 17px; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-meta { margin: 0; font-size: 12px; }
.card-foot { margin-top: auto; padding-top: 6px; border-top: 1px solid var(--wv-divider); display: flex; justify-content: space-between; }
.mark-bar { display: flex; gap: 6px; padding: 0 12px 10px; }
.mk { appearance: none; border: none; background: transparent; cursor: pointer; color: var(--wv-text-3); font-size: 12px; padding: 2px 6px; border-radius: 6px; }
.mk:hover { background: var(--wv-surface-sunken); }
.mk.on { color: var(--wv-accent-text); }
.pager { display: flex; justify-content: center; gap: 14px; align-items: center; }
.pager-info { font-size: 12px; color: var(--wv-text-3); }
.op { appearance: none; border: 1px solid var(--wv-line-strong); background: transparent; color: var(--wv-text-3); font-size: 12px; line-height: 1; padding: 6px 12px; border-radius: 6px; cursor: pointer; }
.op:hover:not(:disabled) { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.on { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); background: var(--wv-accent-soft); }
.op.primary { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); }
.op:disabled { opacity: .4; cursor: default; }
.op.x { position: absolute; top: 10px; right: 10px; }
.skel { min-height: 200px; padding: 20px; background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); }
.empty-state { display: flex; justify-content: center; padding: 40px; border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m); }
.overlay { position: fixed; inset: 0; z-index: 60; background: rgba(11,11,10,.62); display: flex; align-items: center; justify-content: center; padding: 24px; }
.overlay-card { position: relative; width: min(560px, 100%); max-height: 86vh; overflow: auto; background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); padding: 24px; display: flex; flex-direction: column; gap: 14px; }
.overlay-title { margin: 0; font-size: 22px; }
.overlay-meta { margin: 0; font-size: 12px; color: var(--wv-text-3); }
.overlay-note { margin: 0; font-size: 12px; }
.overlay-ops { display: flex; gap: 8px; }
</style>
