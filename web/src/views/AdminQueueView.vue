<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

import { adminFailJob, adminQueueJobs } from '@/api/admin'
import { JOB_STATE_LABEL } from '@/api/jobs'
import type { JobRecord } from '@/api/types'

const rows = ref<JobRecord[]>([])
const loading = ref(false)
/** ★ 2026-09-23：拉取失败时必须**清空旧行并显式告警** ——
 *  用户实测「队列里一条任务显示进行中挂了好几小时」，而库里 0 条；
 *  原因就是这个 catch：以前只弹一个 toast，`rows` 保留上一次的成功结果 ⇒
 *  登录过期/接口报错后，旧快照里的“生成中”会永久留在页面上。 */
const loadError = ref('')
let timer: ReturnType<typeof setInterval> | undefined

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await adminQueueJobs()
    loadError.value = ''
  } catch (e) {
    rows.value = []
    loadError.value = e instanceof Error ? e.message : '加载失败（需管理员）'
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
    <div v-if="loadError" class="empty" style="color: #c45c4a">
      队列数据获取失败：{{ loadError }}
      <span class="text-secondary">（旧数据已清空 —— 很可能登录已过期，请重新登录后再看）</span>
    </div>
    <div v-else-if="!rows.length && !loading" class="empty text-secondary">当前没有排队/运行中任务。</div>
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
