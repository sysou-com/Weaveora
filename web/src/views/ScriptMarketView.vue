<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArrowLeft, Info } from 'lucide-vue-next'
import { NCollapse, NCollapseItem, NIcon, NSkeleton, useMessage } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { marketScript, marketScriptEpisodes, toggleScriptMark } from '@/api/scripts'
import type { ScriptCard } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateShort } from '@/utils/format'
import { SCRIPT_FIELDS, formatChars } from '@/utils/script'

/**
 * 剧本精选 · 只读详情（公开路由 /script-market/:scriptId）。
 * 游客可读；点赞/收藏需登录。与「我的剧本 → 剧情详情」同结构，但不可编辑、不可 AI。
 */
const route = useRoute()
const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const scriptId = computed(() => String(route.params.scriptId ?? ''))
const logged = auth.hasSession()

const { data: card, isPending } = useQuery({
  queryKey: computed(() => ['script-market', scriptId.value]),
  queryFn: () => marketScript(scriptId.value),
  enabled: computed(() => scriptId.value !== ''),
})

const { data: episodes, isPending: epsPending } = useQuery({
  queryKey: computed(() => ['script-market-episodes', scriptId.value]),
  queryFn: () => marketScriptEpisodes(scriptId.value),
  enabled: computed(() => scriptId.value !== ''),
})

const marked = ref<ScriptCard | null>(null)
const view = computed(() => marked.value ?? card.value ?? null)

async function doMark(kind: 'like' | 'fav'): Promise<void> {
  const c = view.value
  if (!c) return
  try {
    const r = await toggleScriptMark(c.id, kind)
    const next: ScriptCard = { ...c }
    if (r.kind === 'like') {
      next.likeCount = r.count
      next.liked = r.active
    } else {
      next.favoriteCount = r.count
      next.favorited = r.active
    }
    marked.value = next
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <button type="button" class="back" @click="router.back()">
        <NIcon size="15"><ArrowLeft /></NIcon>
        <span>返回</span>
      </button>
      <span class="badge font-mono">剧本精选 · 只读</span>
    </header>

    <div v-if="isPending" class="skel"><NSkeleton text /><NSkeleton text style="margin-top:10px" /></div>

    <template v-else-if="view">
      <header class="head">
        <p class="eyebrow font-mono">{{ view.genre }}</p>
        <h1 class="title">{{ view.title }}</h1>
        <p class="meta font-mono text-secondary">
          {{ view.episodeCount }} 集 · 约 {{ formatChars(view.charCount) }}
          · 分享者：{{ view.ownerName || '匿名' }} · 更新 {{ formatDateShort(view.updatedAt) }}
        </p>
        <div v-if="logged" class="marks">
          <button type="button" class="op" :class="{ on: view.liked }" @click="doMark('like')">
            {{ view.liked ? '♥' : '♡' }} 赞 {{ view.likeCount }}
          </button>
          <button type="button" class="op" :class="{ on: view.favorited }" @click="doMark('fav')">
            {{ view.favorited ? '★' : '☆' }} 收藏 {{ view.favoriteCount }}
          </button>
        </div>
        <p v-else class="login-hint text-secondary">登录后可点赞与收藏。</p>
      </header>

      <section class="block">
        <h2 class="block-title">剧本要素</h2>
        <NCollapse :default-expanded-names="['characters', 'story']">
          <NCollapseItem v-for="f in SCRIPT_FIELDS" :key="f.key" :name="f.key" :title="f.label">
            <p class="body">{{ (view as unknown as Record<string, string>)[f.key] || '（未填写）' }}</p>
          </NCollapseItem>
        </NCollapse>
      </section>

      <section class="block">
        <h2 class="block-title">分集</h2>
        <div v-if="epsPending" class="skel"><NSkeleton text /></div>
        <NCollapse v-else-if="(episodes ?? []).length" :default-expanded-names="[(episodes ?? [])[0]?.id ?? '']">
          <NCollapseItem
            v-for="e in episodes ?? []"
            :key="e.id"
            :name="e.id"
            :title="`第 ${e.episodeNo} 集 · ${e.title}`"
          >
            <p v-if="e.summary" class="summary text-secondary">摘要：{{ e.summary }}</p>
            <p class="body">{{ e.content || '（本集暂无正文）' }}</p>
          </NCollapseItem>
        </NCollapse>
        <p v-else class="text-secondary empty">该剧本还没有发布任何一集。</p>
      </section>

      <p class="note text-secondary">
        <NIcon size="13" class="note-ic"><Info /></NIcon>
        剧本精选为只读浏览，不能编辑或生成；创作请前往「我的剧本」。
      </p>
    </template>

    <div v-else class="empty-state"><p class="text-secondary">剧本不存在或未上架。</p></div>
  </div>
</template>

<style scoped>
.page {
  max-width: 880px; margin: 0 auto; padding: 28px 24px 64px;
  display: flex; flex-direction: column; gap: 22px;
}
.topbar { display: flex; align-items: center; gap: 12px; }
.back {
  display: inline-flex; align-items: center; gap: 6px; padding: 6px 10px; margin-left: -10px;
  background: none; border: none; border-radius: var(--wv-radius-s);
  color: var(--wv-text-3); font-size: 13.5px; cursor: pointer;
}
.back:hover { color: var(--wv-text); background: var(--wv-surface); }
.badge {
  font-size: 10px; letter-spacing: 0.2em; color: var(--wv-accent-text);
  background: var(--wv-accent-soft); padding: 4px 8px; border-radius: 6px;
}
.head { display: flex; flex-direction: column; gap: 8px; }
.eyebrow { margin: 0; font-size: 11px; letter-spacing: 0.3em; color: var(--wv-text-4); }
.title { margin: 0; font-size: 30px; line-height: 1.25; }
.meta { margin: 0; font-size: 12px; }
.marks { display: flex; gap: 8px; margin-top: 4px; }
.login-hint { margin: 0; font-size: 12px; }
.block {
  border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m);
  background: var(--wv-surface); padding: 18px 20px;
}
.block-title { margin: 0 0 12px; font-size: 17px; font-family: var(--wv-font-display); }
.summary { margin: 0 0 10px; font-size: 12.5px; }
.body { margin: 0; font-size: 14px; line-height: 1.9; white-space: pre-wrap; word-break: break-word; }
.empty { margin: 0; font-size: 13px; }
.note { display: flex; gap: 6px; margin: 0; font-size: 12px; }
.note-ic { margin-top: 3px; flex: none; color: var(--wv-text-4); }
.skel { padding: 20px; background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); }
.empty-state { padding: 40px; text-align: center; border: 1px dashed var(--wv-line-strong); border-radius: var(--wv-radius-m); }
.op {
  appearance: none; border: 1px solid var(--wv-line-strong); background: transparent;
  color: var(--wv-text-3); font-size: 12px; line-height: 1; padding: 6px 12px;
  border-radius: 6px; cursor: pointer;
}
.op:hover { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.on { color: var(--wv-accent-text); border-color: var(--wv-accent-strong); background: var(--wv-accent-soft); }
</style>
