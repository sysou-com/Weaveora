<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useMessage } from 'naive-ui'

import { adminFailJob, adminQueueJobs } from '@/api/admin'
import { JOB_STATE_LABEL } from '@/api/jobs'
import type { JobRecord } from '@/api/types'

const message = useMessage()
const rows = ref<JobRecord[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await adminQueueJobs()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败（需管理员）')
  } finally {
    loading.value = false
  }
}
async function failOne(id: string): Promise<void> {
  if (!window.confirm('让该任务失败以解除队列阻塞？')) return
  await adminFailJob(id)
  await load()
}
onMounted(() => {
  void load()
  timer = setInterval(() => void load(), 5000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
function fmt(d: string): string {
  return d ? new Date(d).toLocaleString() : ''
}
</script>

<template>
  <div class="page">
    <header class="head">
      <p class="eyebrow font-mono">ADMIN</p>
      <h1 class="title">任务队列（管理员）</h1>
      <p class="desc text-secondary">
        查看全部排队/运行中任务；对长时间卡住的任务可手工标失败。系统也会每 15 分钟自动回收超时任务。
      </p>
    </header>
    <div v-if="!rows.length && !loading" class="empty text-secondary">当前没有排队/运行中任务。</div>
    <div class="list">
      <div v-for="j in rows" :key="j.id" class="row">
        <span class="font-mono k">{{ j.kind }}</span>
        <span :class="['st', j.state]">{{ JOB_STATE_LABEL[j.state] ?? j.state }} {{ j.progress }}%</span>
        <span class="meta font-mono">{{ j.id.slice(0, 13) }}… · {{ fmt(j.createdAt) }}</span>
        <button type="button" class="op danger" @click="failOne(j.id)">标为失败</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 18px; }
.head {}
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: .34em; color: var(--wv-text-4); }
.title { font-size: 28px; }
.desc { margin: 8px 0 0; font-size: 13.5px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.row {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px;
  background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: 10px;
}
.k { font-size: 11px; color: var(--wv-text-3); width: 44px; }
.st { font-size: 12px; min-width: 120px; }
.st.queued { color: var(--wv-text-2); }
.st.running { color: var(--wv-accent-text); }
.meta { font-size: 11px; color: var(--wv-text-4); flex: 1; }
.op { appearance: none; border: 1px solid var(--wv-line-strong); background: none; color: var(--wv-text-3); font-size: 12px; padding: 5px 10px; border-radius: 6px; cursor: pointer; }
.op:hover { color: var(--wv-text); background: var(--wv-surface-raised); }
.op.danger { color: #d98a78; border-color: rgba(196,92,74,.55); }
.empty { padding: 24px; text-align: center; border: 1px dashed var(--wv-line-strong); border-radius: 12px; }
</style>
