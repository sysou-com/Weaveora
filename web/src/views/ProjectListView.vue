<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowRight,
  Film,
  Plus,
  X,
} from 'lucide-vue-next'
import { NButton, NIcon, NSkeleton, useMessage } from 'naive-ui'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  deleteProjects,
  fetchMarketPreview,
  listMarketPage,
  listOwnPage,
  listPendingPage,
  reviewProjects,
  shareProject,
  toggleMark,
} from '@/api/market'
import type { ProjectCard } from '@/api/types'
import { marketThumb, ownThumb } from '@/utils/thumbs'
import { useAuthStore } from '@/stores/auth'
import { aspectNote, formatDateShort, modeLabel } from '@/utils/format'

const ADMIN_EMAIL = 'sysou.com@outlook.com'
const PAGE_SIZE = 8

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const queryClient = useQueryClient()

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')
const isAdmin = computed(
  () => auth.user?.email?.toLowerCase() === ADMIN_EMAIL,
)

// ---------- 我的项目（分页） ----------
const ownPage = ref(0)
const { data: own, isPending: ownPending } = useQuery({
  queryKey: computed(() => ['own-projects', workspaceId.value, ownPage.value]),
  queryFn: () => listOwnPage(workspaceId.value, ownPage.value, PAGE_SIZE),
  enabled: computed(() => workspaceId.value !== ''),
})
const ownTotal = computed(() => own.value?.total ?? 0)
const ownPages = computed(() => Math.max(1, Math.ceil(ownTotal.value / PAGE_SIZE)))

// 管理态（选择 + 删除/分享）
const managing = ref(false)
const selected = ref<string[]>([])
function toggleAll(toggle: boolean): void {
  selected.value = toggle ? (own.value?.items ?? []).map((p) => p.id) : []
}
function toggleOne(id: string): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((x) => x !== id)
    : [...selected.value, id]
}
async function removeSelected(): Promise<void> {
  const ids = [...selected.value]
  if (!ids.length) return
  if (!window.confirm(`删除所选 ${ids.length} 个项目？不可恢复。`)) return
  try {
    const r = await deleteProjects(workspaceId.value, ids)
    message.success(`已删除 ${r.deleted} 个`)
    selected.value = []
    managing.value = false
    await queryClient.invalidateQueries({ queryKey: ['own-projects'] })
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}
async function shareSelected(): Promise<void> {
  const ids = [...selected.value]
  if (!ids.length) return
  try {
    for (const id of ids) {
      await shareProject(workspaceId.value, id)
    }
    message.success(`已提交 ${ids.length} 个项目到集市，等待管理员审批`)
    selected.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['own-projects'] }),
      queryClient.invalidateQueries({ queryKey: ['pending-projects'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '分享失败')
  }
}

// ---------- 集市（分页） ----------
const marketPage = ref(0)
const { data: market, isPending: marketPending } = useQuery({
  queryKey: ['market-projects', marketPage],
  queryFn: () => listMarketPage(marketPage.value, PAGE_SIZE),
})
const marketTotal = computed(() => market.value?.total ?? 0)
const marketPages = computed(() => Math.max(1, Math.ceil(marketTotal.value / PAGE_SIZE)))

// 集市只读详情浮层（market） / 待审详情+操作（pending）
const viewingMarket = ref<ProjectCard | null>(null)
const viewingPending = ref<ProjectCard | null>(null)
const overlayMode = ref<'market' | 'pending' | ''>('')
const overlayCard = computed(() =>
  overlayMode.value === 'pending' ? viewingPending.value : viewingMarket.value,
)
function openMarket(p: ProjectCard): void {
  viewingMarket.value = p
  overlayMode.value = 'market'
}
function openPending(p: ProjectCard): void {
  viewingPending.value = p
  overlayMode.value = 'pending'
}
function closeOverlay(): void {
  viewingMarket.value = null
  viewingPending.value = null
  overlayMode.value = ''
}
async function reviewOne(id: string, approved: boolean): Promise<void> {
  try {
    await reviewProjects([id], approved)
    message.success(approved ? '已通过' : '已驳回')
    closeOverlay()
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-projects'] }),
      queryClient.invalidateQueries({ queryKey: ['market-projects'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  }
}
async function deleteOnePending(id: string): Promise<void> {
  if (!window.confirm('删除该待审项目？不可恢复。')) return
  try {
    await deleteProjects(workspaceId.value, [id])
    message.success('已删除')
    closeOverlay()
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-projects'] }),
      queryClient.invalidateQueries({ queryKey: ['market-projects'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- 管理审批（管理员） ----------
const pendingPage = ref(0)
const pendingSel = ref<string[]>([])
const { data: pending } = useQuery({
  queryKey: ['pending-projects', pendingPage],
  queryFn: () => listPendingPage(pendingPage.value, PAGE_SIZE),
  enabled: isAdmin,
})
const pendingTotal = computed(() => pending.value?.total ?? 0)
const pendingPages = computed(() => Math.max(1, Math.ceil(pendingTotal.value / PAGE_SIZE)))
function togglePending(id: string): void {
  pendingSel.value = pendingSel.value.includes(id)
    ? pendingSel.value.filter((x) => x !== id)
    : [...pendingSel.value, id]
}
async function deletePendingSel(): Promise<void> {
  const ids = [...pendingSel.value]
  if (!ids.length) return
  if (!window.confirm(`删除所选 ${ids.length} 个待审分享（连同其项目）？不可恢复。`)) return
  try {
    const r = await deleteProjects(workspaceId.value, ids)
    message.success(`已删除 ${r.deleted} 个`)
    pendingSel.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-projects'] }),
      queryClient.invalidateQueries({ queryKey: ['market-projects'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}
async function reviewSel(approved: boolean): Promise<void> {
  const ids = [...pendingSel.value]
  if (!ids.length) return
  try {
    const r = await reviewProjects(ids, approved)
    message.success(`${approved ? '通过' : '驳回'} ${r.reviewed} 个`)
    pendingSel.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-projects'] }),
      queryClient.invalidateQueries({ queryKey: ['market-projects'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  }
}

// 点赞/收藏：切换并同步当前页数据（浮层引同一对象，自动更新）
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
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function downloadMarketPreview(id: string): Promise<void> {
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

// ---------- 缩略图（缓存，后台拉取） ----------
const ownThumbs = reactive<Record<string, { url: string; mime: string }>>({})
const marketThumbs = reactive<Record<string, string>>({})
const pendingThumbs = reactive<Record<string, string>>({})

function thumbOf(id: string): string {
  return ownThumbs[id]?.url ?? marketThumbs[id] ?? pendingThumbs[id] ?? ''
}
watch(
  () => pending.value?.items,
  (items) => {
    for (const p of items ?? []) {
      if (pendingThumbs[p.id]) continue
      void marketThumb(p.id).then((t) => {
        if (t) pendingThumbs[p.id] = t.url
      })
    }
  },
  { immediate: true },
)
watch(
  () => own.value?.items,
  (items) => {
    for (const p of items ?? []) {
      if (ownThumbs[p.id]) continue
      void ownThumb(workspaceId.value, p.id).then((t) => {
        if (t) ownThumbs[p.id] = t
      })
    }
  },
  { immediate: true },
)
watch(
  () => market.value?.items,
  (items) => {
    for (const p of items ?? []) {
      if (marketThumbs[p.id]) continue
      void marketThumb(p.id).then((t) => {
        if (t) marketThumbs[p.id] = t.url
      })
    }
  },
  { immediate: true },
)

// ---------- 跳转 / 通用 ----------
function openProject(card: { id: string }): void {
  void router.push({ name: 'project-detail', params: { projectId: card.id } })
}
function goNew(): void {
  void router.push({ name: 'project-new' })
}

const shareLabel = (s: string | null): string =>
  s === 'pending' ? '待审' : s === 'approved' ? '集市中' : s === 'rejected' ? '已驳回' : ''
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div class="head-copy">
        <p class="eyebrow font-mono">PROJECTS</p>
        <h1 class="title">项目</h1>
        <p class="desc text-secondary">
          {{ isAdmin ? '管理你的项目与项目精选' : '创作工作台 · 内测阶段由管理员开通创作' }}
        </p>
      </div>
      <div class="head-actions">
        <NButton v-if="isAdmin" type="primary" data-testid="btn-new-project-top" @click="goNew">
          <template #icon><NIcon><Plus :size="16" /></NIcon></template>
          新建项目
        </NButton>
      </div>
    </header>

    <!-- ============ 一、我的项目 ============ -->
    <section class="zone" data-testid="zone-own">
      <div class="zone-head">
        <h2 class="zone-title">我的项目</h2>
        <span class="zone-hint font-mono">{{ ownTotal }} 个</span>
        <div class="zone-actions">
          <template v-if="managing">
            <button type="button" class="op" @click="toggleAll(selected.length !== (own?.items ?? []).length)">
              {{ selected.length === (own?.items ?? []).length && (own?.items ?? []).length ? '取消全选' : '全选' }}
            </button>
            <button type="button" class="op primary" :disabled="!selected.length" @click="shareSelected">分享</button>
            <button type="button" class="op danger" :disabled="!selected.length" @click="removeSelected">删除</button>
            <button type="button" class="op" @click="managing = false; selected = []">完成</button>
          </template>
          <button v-else type="button" class="op" data-testid="btn-manage-projects"
                  @click="managing = true">管理</button>
        </div>
      </div>

      <div v-if="ownPending" class="grid">
        <div v-for="i in 4" :key="i" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      </div>
      <template v-else-if="ownTotal > 0">
        <div class="grid">
          <div
            v-for="p in own?.items ?? []"
            :key="p.id"
            class="card"
            :class="{ managing }"
            :data-testid="`project-card-${p.mode}`"
          >
            <label v-if="managing" class="pick" :title="selected.includes(p.id) ? '取消' : '选择'">
              <input type="checkbox" :checked="selected.includes(p.id)" @change="toggleOne(p.id)" />
            </label>
            <button type="button" class="card-inner" @click="managing ? toggleOne(p.id) : openProject(p)">
              <div class="thumb">
                <img v-if="ownThumbs[p.id]" :src="ownThumbs[p.id].url" class="thumb-media" alt="" loading="lazy" />
                <div v-else class="thumb-ph">
                  <svg width="34" height="34" viewBox="0 0 96 96" fill="none" aria-hidden="true">
                    <rect x="12" y="22" width="72" height="52" rx="9" stroke="#6F6A60" stroke-width="3" />
                    <rect x="22" y="32" width="52" height="32" rx="4" stroke="#3A362F" stroke-width="3" />
                    <path d="M20 47H76" stroke="#3A362F" stroke-width="3" />
                    <path d="M40 32L40 64M56 32L56 64" stroke="#8FB9B4" stroke-width="3" />
                  </svg>
                </div>
              </div>
              <div class="card-top">
                <span class="card-mode font-mono">{{ modeLabel(p.mode) }}</span>
                <span v-if="p.shareStatus" class="chip-share font-mono">{{ shareLabel(p.shareStatus) }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">
                {{ p.aspectRatio }} {{ aspectNote(p.aspectRatio) }}
                <template v-if="p.mode === 'video' && p.durationSec">· {{ p.durationSec }}s</template>
              </p>
              <div class="card-foot">
                <span class="card-date font-mono">更新 {{ formatDateShort(p.updatedAt) }}</span>
                <NIcon size="16" class="card-arrow"><ArrowRight /></NIcon>
              </div>
            </button>
          </div>
        </div>
        <nav v-if="ownPages > 1" class="pager">
          <button class="op" :disabled="ownPage === 0" @click="ownPage--">上一页</button>
          <span class="pager-info font-mono">{{ ownPage + 1 }} / {{ ownPages }}</span>
          <button class="op" :disabled="ownPage >= ownPages - 1" @click="ownPage++">下一页</button>
        </nav>
      </template>
      <div v-else class="empty-state">
        <p class="text-secondary">
          {{ isAdmin ? '还没有项目，新建第一个开始创作。' : '内测阶段暂未开放新建项目，请联系管理员开通（sysou.com@outlook.com）。' }}
        </p>
        <NButton v-if="isAdmin" type="primary" @click="goNew">
          <template #icon><NIcon><Plus :size="15" /></NIcon></template>
          新建项目
        </NButton>
      </div>
    </section>

    <!-- ============ 二、管理审批（仅管理员，置于集市上方） ============ -->
    <section v-if="isAdmin" class="zone" data-testid="zone-pending">
      <div class="zone-head">
        <h2 class="zone-title">集市待审</h2>
        <span class="zone-hint font-mono">{{ pending?.total ?? 0 }} 个</span>
        <div class="zone-actions">
          <button class="op"
                  @click="pendingSel = pendingSel.length === (pending?.items ?? []).length && (pending?.items ?? []).length ? [] : (pending?.items ?? []).map((p: ProjectCard) => p.id)">
            {{ pendingSel.length === (pending?.items ?? []).length && (pending?.items ?? []).length ? '取消全选' : '全选' }}
          </button>
          <button class="op primary" :disabled="!pendingSel.length" @click="reviewSel(true)">通过</button>
          <button class="op danger" :disabled="!pendingSel.length" @click="reviewSel(false)">驳回</button>
          <button class="op danger" :disabled="!pendingSel.length" @click="deletePendingSel">删除</button>
        </div>
      </div>
      <template v-if="(pending?.items ?? []).length">
        <div class="grid">
          <div v-for="p in pending?.items ?? []" :key="p.id" class="card" :class="{ sel: pendingSel.includes(p.id) }">
            <label class="pick" :title="pendingSel.includes(p.id) ? '取消' : '选择'">
              <input type="checkbox" :checked="pendingSel.includes(p.id)" @change="togglePending(p.id)" />
            </label>
            <button type="button" class="card-inner" @click="togglePending(p.id)">
              <div class="thumb">
                <img v-if="pendingThumbs[p.id]" :src="pendingThumbs[p.id]" class="thumb-media" alt="" loading="lazy" />
                <div v-else class="thumb-ph">
                  <NIcon size="26" class="thumb-ic"><Film /></NIcon>
                </div>
              </div>
              <div class="card-top">
                <span class="card-mode font-mono">{{ modeLabel(p.mode) }}</span>
                <span class="chip-share font-mono">{{ p.shareStatus === 'rejected' ? '已驳回' : '待审' }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">
                {{ p.aspectRatio }} {{ aspectNote(p.aspectRatio) }}
                <template v-if="p.mode === 'video' && p.durationSec">· {{ p.durationSec }}s</template>
              </p>
              <div class="card-foot">
                <span class="card-date font-mono">{{ p.ownerName || '匿名' }}</span>
                <span class="card-date font-mono">{{ formatDateShort(p.updatedAt) }}</span>
              </div>
            </button>
            <button type="button" class="op view" :data-testid="'pending-view-' + p.id.slice(0, 8)" @click.stop="openPending(p)">查看</button>
          </div>
        </div>
        <nav v-if="pendingPages > 1" class="pager">
          <button class="op" :disabled="pendingPage === 0" @click="pendingPage--">上一页</button>
          <span class="pager-info font-mono">{{ pendingPage + 1 }} / {{ pendingPages }}</span>
          <button class="op" :disabled="pendingPage >= pendingPages - 1" @click="pendingPage++">下一页</button>
        </nav>
      </template>
      <div v-else class="empty-state"><p class="text-secondary">暂无待审分享。</p></div>
    </section>

    <!-- ============ 三、项目精选 ============ -->
    <section class="zone" data-testid="zone-market">
      <div class="zone-head">
        <h2 class="zone-title">项目精选</h2>
        <span class="zone-hint text-secondary">管理员审核通过的分享项目</span>
      </div>
      <div v-if="marketPending" class="grid">
        <div v-for="i in 4" :key="i" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      </div>
      <template v-else-if="marketTotal > 0">
        <div class="grid">
          <div v-for="p in market?.items ?? []" :key="p.id" class="card market">
            <button type="button" class="card-inner" @click="openMarket(p)">
              <div class="thumb">
                <img v-if="marketThumbs[p.id]" :src="marketThumbs[p.id]" class="thumb-media" alt="" loading="lazy" />
                <div v-else class="thumb-ph">
                  <NIcon size="26" class="thumb-ic"><Film /></NIcon>
                </div>
              </div>
              <div class="card-top">
                <span class="card-mode font-mono">{{ modeLabel(p.mode) }}</span>
                <span class="card-date font-mono">{{ p.ownerName || '匿名' }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">{{ p.aspectRatio }} {{ aspectNote(p.aspectRatio) }}</p>
              <div class="card-foot">
                <span class="card-date font-mono">{{ formatDateShort(p.updatedAt) }}</span>
                <NIcon size="16" class="card-arrow"><ArrowRight /></NIcon>
              </div>
            </button>
            <div class="mark-bar">
              <button type="button" class="mk" :class="{ on: p.liked }" @click="doMark(p.id, 'like')">
                {{ p.liked ? '♥' : '♡' }} {{ p.likeCount }}
              </button>
              <button type="button" class="mk" :class="{ on: p.favorited }" @click="doMark(p.id, 'fav')">
                {{ p.favorited ? '★' : '☆' }} {{ p.favoriteCount }}
              </button>
            </div>
          </div>
        </div>
        <nav v-if="marketPages > 1" class="pager">
          <button class="op" :disabled="marketPage === 0" @click="marketPage--">上一页</button>
          <span class="pager-info font-mono">{{ marketPage + 1 }} / {{ marketPages }}</span>
          <button class="op" :disabled="marketPage >= marketPages - 1" @click="marketPage++">下一页</button>
        </nav>
      </template>
      <div v-else class="empty-state">
        <p class="text-secondary">集市还没有项目——客户分享、管理员审核通过后就会出现在这里。</p>
      </div>
    </section>

    <!-- 详情浮层：集市只读 / 待审操作 -->
    <div v-if="overlayCard" class="overlay" @click.self="closeOverlay">
      <div class="overlay-card">
        <button type="button" class="op x" @click="closeOverlay"><X :size="14" /></button>
        <h3 class="overlay-title font-display">{{ overlayCard.title }}</h3>
        <p class="overlay-meta font-mono">
          {{ modeLabel(overlayCard.mode) }} · {{ overlayCard.aspectRatio }}
          <template v-if="overlayCard.mode === 'video' && overlayCard.durationSec">
            · {{ overlayCard.durationSec }}s
          </template>
          · 分享者：{{ overlayCard.ownerName || '匿名' }}
        </p>
        <div class="thumb big">
          <img v-if="thumbOf(overlayCard.id)" :src="thumbOf(overlayCard.id)" class="thumb-media" alt="" />
          <div v-else class="thumb-ph"><span class="text-secondary">暂无预览图</span></div>
        </div>
        <p class="overlay-note text-secondary">
          {{ overlayMode === 'pending' ? '审批后即上架/驳回，删除将从集市与待审移除。' : '集市为只读浏览；可点赞/收藏/下载预览图，不能编辑内容。' }}
        </p>
        <div v-if="overlayMode === 'pending'" class="overlay-ops">
          <button type="button" class="op primary" @click="reviewOne(overlayCard.id, true)">通过</button>
          <button type="button" class="op danger" @click="reviewOne(overlayCard.id, false)">驳回</button>
          <button type="button" class="op danger" @click="deleteOnePending(overlayCard.id)">删除</button>
        </div>
        <div v-else-if="overlayMode === 'market'" class="overlay-ops">
          <button type="button" class="op" :class="{ on: overlayCard.liked }" @click="doMark(overlayCard.id, 'like')">
            {{ overlayCard.liked ? '♥' : '♡' }} 赞 {{ overlayCard.likeCount }}
          </button>
          <button type="button" class="op" :class="{ on: overlayCard.favorited }" @click="doMark(overlayCard.id, 'fav')">
            {{ overlayCard.favorited ? '★' : '☆' }} 收藏 {{ overlayCard.favoriteCount }}
          </button>
          <button type="button" class="op" @click="downloadMarketPreview(overlayCard.id)">下载预览图</button>
          <button type="button" class="op primary" @click="router.push({ path: '/market/' + overlayCard.id })">查看全部素材(只读)</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 26px; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: 0.34em; color: var(--wv-text-4); }
.title { font-size: 30px; line-height: 1.2; }
.desc { margin: 8px 0 0; font-size: 14px; }

.zone { display: flex; flex-direction: column; gap: 14px; }
.zone-head { display: flex; align-items: center; gap: 12px; }
.zone-title { margin: 0; font-size: 20px; font-family: var(--wv-font-display); }
.zone-hint { font-size: 11px; color: var(--wv-text-4); }
.zone-actions { margin-left: auto; display: flex; gap: 8px; }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(252px, 1fr)); gap: 16px; }
.card {
  position: relative;
  border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m);
  background: var(--wv-surface);
  overflow: hidden;
  transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.card-inner {
  appearance: none; border: none; background: none; width: 100%;
  padding: 14px 16px 12px; display: flex; flex-direction: column; gap: 10px;
  color: var(--wv-text); cursor: pointer; text-align: left;
}
.mark-bar {
  display: flex; align-items: center; gap: 6px;
  padding: 0 12px 10px;
}
.mk {
  appearance: none; border: none; background: transparent; cursor: pointer;
  color: var(--wv-text-3); font-size: 12px; padding: 2px 6px; border-radius: 6px;
}
.mk:hover { background: var(--wv-surface-sunken); }
.mk.on { color: var(--wv-accent-text); }
.op.on { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); background: var(--wv-accent-soft); }
.card:hover { background: var(--wv-surface-raised); border-color: color-mix(in srgb, var(--wv-accent) 38%, var(--wv-line)); }
.card.managing .card-inner { cursor: default; }
.pick {
  position: absolute; top: 8px; left: 8px; z-index: 3;
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 6px; background: rgba(11,11,10,.7);
}
.pick input { accent-color: var(--wv-accent); cursor: pointer; }
.card.sel { outline: 1px solid var(--wv-accent); outline-offset: 1px; }
.card .op.view {
  position: absolute; top: 8px; right: 8px; z-index: 3;
  background: rgba(11, 11, 10, .72); color: var(--wv-accent-text); border-color: transparent;
  opacity: 0; pointer-events: none; transition: opacity var(--wv-dur) var(--wv-ease);
}
.card:hover .op.view { opacity: 1; pointer-events: auto; }

.thumb {
  width: 100%; aspect-ratio: 16 / 9; border-radius: 8px; overflow: hidden;
  background: var(--wv-surface-sunken); border: 1px solid var(--wv-line);
  display: flex; align-items: center; justify-content: center; pointer-events: none;
}
.thumb.big { aspect-ratio: 16 / 9; }
.thumb-media { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-ph { display: flex; align-items: center; justify-content: center; }
.thumb-ic { color: var(--wv-text-4); }

.card-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.card-mode { font-size: 11px; letter-spacing: 0.16em; color: var(--wv-accent-text); text-transform: uppercase; }
.card-date { font-size: 11px; color: var(--wv-text-4); }
.chip-share {
  font-size: 10px; letter-spacing: 0.1em; padding: 2px 6px; border-radius: 5px;
  color: var(--wv-accent-text); background: var(--wv-accent-soft);
}
.card-title {
  margin: 0; font-size: 17px; line-height: 1.35; color: var(--wv-text);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.card-meta { margin: 0; font-size: 12px; }
.card-foot { margin-top: auto; padding-top: 8px; border-top: 1px solid var(--wv-divider); display: flex; justify-content: space-between; align-items: center; }
.card-arrow { color: var(--wv-text-4); }
.card:hover .card-arrow { color: var(--wv-accent); transform: translateX(2px); }

.pager { display: flex; align-items: center; justify-content: center; gap: 14px; }
.pager-info { font-size: 12px; color: var(--wv-text-3); }
.op {
  appearance: none; border: 1px solid var(--wv-line-strong); background: transparent;
  color: var(--wv-text-3); font-size: 12px; line-height: 1; padding: 6px 12px;
  border-radius: 6px; cursor: pointer;
}
.op:hover:not(:disabled) { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.primary { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); }
.op.danger { color: #d98a78; border-color: rgba(196,92,74,.55); }
.op:disabled { opacity: .4; cursor: default; }
.op.x { position: absolute; top: 10px; right: 10px; display: inline-flex; }

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 34px 20px; border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m); }
.skel { min-height: 190px; padding: 20px; background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); }
.overlay-ops { display: flex; gap: 8px; }

.overlay {
  position: fixed; inset: 0; z-index: 60; background: rgba(11,11,10,.62);
  display: flex; align-items: center; justify-content: center; padding: 24px;
}
.overlay-card {
  position: relative; width: min(560px, 100%); max-height: 86vh; overflow: auto;
  background: var(--wv-surface); border: 1px solid var(--wv-line);
  border-radius: var(--wv-radius-m); padding: 24px; display: flex; flex-direction: column; gap: 14px;
}
.overlay-title { margin: 0; font-size: 22px; }
.overlay-meta { margin: 0; font-size: 12px; color: var(--wv-text-3); }
.overlay-note { margin: 0; font-size: 12px; }
</style>
