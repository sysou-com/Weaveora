<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ArrowRight, BookOpen, Plus, X } from 'lucide-vue-next'
import { NButton, NIcon, NSkeleton, useMessage } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  deleteScripts,
  listOwnScriptPage,
  listScriptMarketPage,
  listScriptPendingPage,
  reviewScripts,
  shareScript,
  toggleScriptMark,
} from '@/api/scripts'
import type { ScriptCard } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateShort } from '@/utils/format'
import { formatChars } from '@/utils/script'

/**
 * 我的剧本（复制「我的项目」页结构，术语改为剧本）：
 * ① 我的剧本（管理/分享/删除）② 集市待审（管理员）③ 剧本精选。
 * 剧本没有图片资产 → 卡片是**文本卡**（类型 / 集数 / 字数 / 摘要）。
 */
const ADMIN_EMAIL = 'sysou.com@outlook.com'
const PAGE_SIZE = 8

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const queryClient = useQueryClient()

const workspaceId = computed(() => auth.activeWorkspaceId ?? '')
const isAdmin = computed(() => auth.user?.email?.toLowerCase() === ADMIN_EMAIL)

// ---------- 我的剧本 ----------
const ownPage = ref(0)
const { data: own, isPending: ownPending } = useQuery({
  queryKey: computed(() => ['own-scripts', workspaceId.value, ownPage.value]),
  queryFn: () => listOwnScriptPage(workspaceId.value, ownPage.value, PAGE_SIZE),
  enabled: computed(() => workspaceId.value !== ''),
})
const ownTotal = computed(() => own.value?.total ?? 0)
const ownPages = computed(() => Math.max(1, Math.ceil(ownTotal.value / PAGE_SIZE)))

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
  if (!window.confirm(`删除所选 ${ids.length} 个剧本？不可恢复。`)) return
  try {
    const r = await deleteScripts(workspaceId.value, ids)
    message.success(`已删除 ${r.deleted} 个`)
    selected.value = []
    managing.value = false
    await queryClient.invalidateQueries({ queryKey: ['own-scripts'] })
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}
async function shareSelected(): Promise<void> {
  const ids = [...selected.value]
  if (!ids.length) return
  try {
    for (const id of ids) {
      await shareScript(workspaceId.value, id)
    }
    message.success(`已提交 ${ids.length} 个剧本到剧本精选，等待管理员审批`)
    selected.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['own-scripts'] }),
      queryClient.invalidateQueries({ queryKey: ['pending-scripts'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '分享失败')
  }
}

// ---------- 剧本精选 ----------
const marketPage = ref(0)
const { data: market, isPending: marketPending } = useQuery({
  queryKey: ['market-scripts', marketPage],
  queryFn: () => listScriptMarketPage(marketPage.value, PAGE_SIZE),
})
const marketTotal = computed(() => market.value?.total ?? 0)
const marketPages = computed(() => Math.max(1, Math.ceil(marketTotal.value / PAGE_SIZE)))

const overlayMode = ref<'market' | 'pending' | ''>('')
const overlayCard = ref<ScriptCard | null>(null)
function openMarket(p: ScriptCard): void {
  overlayCard.value = p
  overlayMode.value = 'market'
}
function openPending(p: ScriptCard): void {
  overlayCard.value = p
  overlayMode.value = 'pending'
}
function closeOverlay(): void {
  overlayCard.value = null
  overlayMode.value = ''
}
function openReadonly(id: string): void {
  void router.push({ name: 'script-market', params: { scriptId: id } })
}
async function reviewOne(id: string, approved: boolean): Promise<void> {
  try {
    await reviewScripts([id], approved)
    message.success(approved ? '已通过' : '已驳回')
    closeOverlay()
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-scripts'] }),
      queryClient.invalidateQueries({ queryKey: ['market-scripts'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  }
}
async function deleteOnePending(id: string): Promise<void> {
  if (!window.confirm('删除该待审剧本？不可恢复。')) return
  try {
    await deleteScripts(workspaceId.value, [id])
    message.success('已删除')
    closeOverlay()
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-scripts'] }),
      queryClient.invalidateQueries({ queryKey: ['market-scripts'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- 管理审批（管理员） ----------
const pendingPage = ref(0)
const pendingSel = ref<string[]>([])
const { data: pending } = useQuery({
  queryKey: ['pending-scripts', pendingPage],
  queryFn: () => listScriptPendingPage(pendingPage.value, PAGE_SIZE),
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
  if (!window.confirm(`删除所选 ${ids.length} 个待审剧本？不可恢复。`)) return
  try {
    const r = await deleteScripts(workspaceId.value, ids)
    message.success(`已删除 ${r.deleted} 个`)
    pendingSel.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-scripts'] }),
      queryClient.invalidateQueries({ queryKey: ['market-scripts'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}
async function reviewSel(approved: boolean): Promise<void> {
  const ids = [...pendingSel.value]
  if (!ids.length) return
  try {
    const r = await reviewScripts(ids, approved)
    message.success(`${approved ? '通过' : '驳回'} ${r.reviewed} 个`)
    pendingSel.value = []
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['pending-scripts'] }),
      queryClient.invalidateQueries({ queryKey: ['market-scripts'] }),
    ])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '审批失败')
  }
}

// ---------- 点赞/收藏 ----------
async function doMark(id: string, kind: 'like' | 'fav'): Promise<void> {
  try {
    const r = await toggleScriptMark(id, kind)
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
    if (overlayCard.value?.id === id) {
      if (r.kind === 'like') {
        overlayCard.value.likeCount = r.count
        overlayCard.value.liked = r.active
      } else {
        overlayCard.value.favoriteCount = r.count
        overlayCard.value.favorited = r.active
      }
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

// ---------- 跳转 / 通用 ----------
function openScript(card: { id: string }): void {
  void router.push({ name: 'script-detail', params: { scriptId: card.id } })
}
function goNew(): void {
  void router.push({ name: 'script-new' })
}
const shareLabel = (s: string | null): string =>
  s === 'pending' ? '待审' : s === 'approved' ? '精选中' : s === 'rejected' ? '已驳回' : ''
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div class="head-copy">
        <p class="eyebrow font-mono">SCRIPTS</p>
        <h1 class="title">我的剧本</h1>
        <p class="desc text-secondary">
          {{ isAdmin ? '管理你的剧本与剧本精选' : '从要素到成集 · 内测阶段由管理员开通创作' }}
        </p>
      </div>
      <div class="head-actions">
        <NButton v-if="isAdmin" type="primary" data-testid="btn-new-script-top" @click="goNew">
          <template #icon><NIcon><Plus :size="16" /></NIcon></template>
          新建剧本
        </NButton>
      </div>
    </header>

    <!-- ============ 一、我的剧本 ============ -->
    <section class="zone" data-testid="zone-own-scripts">
      <div class="zone-head">
        <h2 class="zone-title">我的剧本</h2>
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
          <button v-else type="button" class="op" data-testid="btn-manage-scripts" @click="managing = true">管理</button>
        </div>
      </div>

      <div v-if="ownPending" class="grid">
        <div v-for="i in 4" :key="i" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      </div>
      <template v-else-if="ownTotal > 0">
        <div class="grid">
          <div v-for="p in own?.items ?? []" :key="p.id" class="card" :class="{ managing }"
               :data-testid="`script-card-${p.id.slice(0, 8)}`">
            <label v-if="managing" class="pick" :title="selected.includes(p.id) ? '取消' : '选择'">
              <input type="checkbox" :checked="selected.includes(p.id)" @change="toggleOne(p.id)" />
            </label>
            <button type="button" class="card-inner" @click="managing ? toggleOne(p.id) : openScript(p)">
              <div class="card-top">
                <span class="card-mode font-mono">{{ p.genre }}</span>
                <span v-if="p.shareStatus" class="chip-share font-mono">{{ shareLabel(p.shareStatus) }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">
                {{ p.episodeCount }} 集 · 约 {{ formatChars(p.charCount) }}
              </p>
              <p class="card-excerpt text-secondary">{{ p.excerpt || '（还没有内容，进入详情开始创作）' }}</p>
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
          {{ isAdmin ? '还没有剧本，新建第一个开始创作。' : '内测阶段暂未开放新建剧本，请联系管理员开通（sysou.com@outlook.com）。' }}
        </p>
        <NButton v-if="isAdmin" type="primary" @click="goNew">
          <template #icon><NIcon><Plus :size="15" /></NIcon></template>
          新建剧本
        </NButton>
      </div>
    </section>

    <!-- ============ 二、集市待审（仅管理员） ============ -->
    <section v-if="isAdmin" class="zone" data-testid="zone-pending-scripts">
      <div class="zone-head">
        <h2 class="zone-title">剧本待审</h2>
        <span class="zone-hint font-mono">{{ pending?.total ?? 0 }} 个</span>
        <div class="zone-actions">
          <button class="op"
                  @click="pendingSel = pendingSel.length === (pending?.items ?? []).length && (pending?.items ?? []).length ? [] : (pending?.items ?? []).map((p) => p.id)">
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
              <div class="card-top">
                <span class="card-mode font-mono">{{ p.genre }}</span>
                <span class="chip-share font-mono">{{ p.shareStatus === 'rejected' ? '已驳回' : '待审' }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">{{ p.episodeCount }} 集 · 约 {{ formatChars(p.charCount) }}</p>
              <div class="card-foot">
                <span class="card-date font-mono">{{ p.ownerName || '匿名' }}</span>
                <span class="card-date font-mono">{{ formatDateShort(p.updatedAt) }}</span>
              </div>
            </button>
            <button type="button" class="op view" @click.stop="openPending(p)">查看</button>
          </div>
        </div>
        <nav v-if="pendingPages > 1" class="pager">
          <button class="op" :disabled="pendingPage === 0" @click="pendingPage--">上一页</button>
          <span class="pager-info font-mono">{{ pendingPage + 1 }} / {{ pendingPages }}</span>
          <button class="op" :disabled="pendingPage >= pendingPages - 1" @click="pendingPage++">下一页</button>
        </nav>
      </template>
      <div v-else class="empty-state"><p class="text-secondary">暂无待审剧本。</p></div>
    </section>

    <!-- ============ 三、剧本精选 ============ -->
    <section class="zone" data-testid="zone-script-market">
      <div class="zone-head">
        <h2 class="zone-title">剧本精选</h2>
        <span class="zone-hint text-secondary">管理员审核通过的分享剧本</span>
      </div>
      <div v-if="marketPending" class="grid">
        <div v-for="i in 4" :key="i" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>
      </div>
      <template v-else-if="marketTotal > 0">
        <div class="grid">
          <div v-for="p in market?.items ?? []" :key="p.id" class="card market">
            <button type="button" class="card-inner" @click="openMarket(p)">
              <div class="card-top">
                <span class="card-mode font-mono">{{ p.genre }}</span>
                <span class="card-date font-mono">{{ p.ownerName || '匿名' }}</span>
              </div>
              <h3 class="card-title">{{ p.title }}</h3>
              <p class="card-meta font-mono text-secondary">{{ p.episodeCount }} 集 · 约 {{ formatChars(p.charCount) }}</p>
              <p class="card-excerpt text-secondary">{{ p.excerpt }}</p>
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
        <p class="text-secondary">剧本精选还没有内容——分享、管理员审核通过后会展示在这里。</p>
      </div>
    </section>

    <!-- 详情浮层：只读 / 待审操作 -->
    <div v-if="overlayCard" class="overlay" @click.self="closeOverlay">
      <div class="overlay-card">
        <button type="button" class="op x" @click="closeOverlay"><X :size="14" /></button>
        <h3 class="overlay-title font-display">{{ overlayCard.title }}</h3>
        <p class="overlay-meta font-mono">
          {{ overlayCard.genre }} · {{ overlayCard.episodeCount }} 集 · 约 {{ formatChars(overlayCard.charCount) }}
          · 分享者：{{ overlayCard.ownerName || '匿名' }}
        </p>
        <div class="overlay-excerpt">
          <NIcon size="18" class="thumb-ic"><BookOpen /></NIcon>
          <p class="text-secondary">{{ overlayCard.excerpt || '（暂无摘要）' }}</p>
        </div>
        <p class="overlay-note text-secondary">
          {{ overlayMode === 'pending'
            ? '审批后即上架/驳回；删除将连同该剧本一起移除。'
            : '剧本精选为只读浏览；可点赞/收藏，不能编辑。' }}
        </p>
        <div class="overlay-ops">
          <template v-if="overlayMode === 'pending'">
            <button type="button" class="op primary" @click="reviewOne(overlayCard.id, true)">通过</button>
            <button type="button" class="op danger" @click="reviewOne(overlayCard.id, false)">驳回</button>
            <button type="button" class="op danger" @click="deleteOnePending(overlayCard.id)">删除</button>
            <button type="button" class="op" @click="openReadonly(overlayCard.id)">查看完整剧本(只读)</button>
          </template>
          <template v-else>
            <button type="button" class="op" :class="{ on: overlayCard.liked }" @click="doMark(overlayCard.id, 'like')">
              {{ overlayCard.liked ? '♥' : '♡' }} 赞 {{ overlayCard.likeCount }}
            </button>
            <button type="button" class="op" :class="{ on: overlayCard.favorited }" @click="doMark(overlayCard.id, 'fav')">
              {{ overlayCard.favorited ? '★' : '☆' }} 收藏 {{ overlayCard.favoriteCount }}
            </button>
            <button type="button" class="op primary" @click="openReadonly(overlayCard.id)">查看完整剧本(只读)</button>
          </template>
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
  position: relative; border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m);
  background: var(--wv-surface); overflow: hidden;
  transition: border-color var(--wv-dur) var(--wv-ease), background var(--wv-dur) var(--wv-ease);
}
.card-inner {
  appearance: none; border: none; background: none; width: 100%;
  padding: 14px 16px 12px; display: flex; flex-direction: column; gap: 10px;
  color: var(--wv-text); cursor: pointer; text-align: left;
}
.mark-bar { display: flex; align-items: center; gap: 6px; padding: 0 12px 10px; }
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

.card-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.card-mode { font-size: 11px; letter-spacing: 0.16em; color: var(--wv-accent-text); }
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
.card-excerpt {
  margin: 0; font-size: 12px; line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.card-foot {
  margin-top: auto; padding-top: 8px; border-top: 1px solid var(--wv-divider);
  display: flex; justify-content: space-between; align-items: center;
}
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

.empty-state {
  display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 34px 20px;
  border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m);
}
.skel {
  min-height: 190px; padding: 20px; background: var(--wv-surface);
  border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m);
}
.overlay-ops { display: flex; gap: 8px; flex-wrap: wrap; }
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
.overlay-excerpt {
  display: flex; gap: 12px; padding: 14px; border-radius: var(--wv-radius-s);
  background: var(--wv-surface-sunken); border: 1px solid var(--wv-line);
}
.overlay-excerpt p { margin: 0; font-size: 13px; line-height: 1.7; }
.thumb-ic { color: var(--wv-text-4); flex: none; margin-top: 2px; }
</style>
