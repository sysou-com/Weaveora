<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NSpin, NTag, useMessage } from 'naive-ui'
import { confirmRunStage, getRunStatus, prepareRun, replanRun, retryRunStage } from '@/api/runs'
import type { RunStage, RunStatus } from '@/api/runs'

/**
 * 制作流程面板（P0/P1，2026-09-29）——「只点确定/下一步」。
 *
 * 后端负责全部业务规则（引擎能力拆镜、台词时长、阶段门禁、建任务）；
 * 本组件只做：展示五阶段状态 + 一个主按钮 + 准备/重试。
 */
const props = defineProps<{
  workspaceId: string
  projectId: string
  revisionId: string
  disabled?: boolean
}>()

const emit = defineEmits<{ changed: [] }>()

const message = useMessage()
const status = ref<RunStatus | null>(null)
const loading = ref(false)
const busy = ref<string>('')
let timer: ReturnType<typeof setTimeout> | null = null
/** 自动「优化时长」只跑一次（避免循环）。 */
let autoPrepared = false

const enabled = computed(
  () => props.workspaceId !== '' && props.projectId !== '' && props.revisionId !== '',
)

const STATE_LABEL: Record<string, string> = {
  done: '已完成',
  running: '进行中',
  ready: '待开始',
  failed: '有失败',
  blocked: '等待上一步',
}

const STATE_TYPE: Record<string, 'success' | 'info' | 'warning' | 'error' | 'default'> = {
  done: 'success',
  running: 'info',
  ready: 'default',
  failed: 'error',
  blocked: 'warning',
}

const engineLabel = computed(() => {
  const s = status.value
  if (!s) return ''
  const name = s.motionEngine === 'ltx25' ? 'LTX-2.5' : s.motionEngine === 'wan22' ? 'Wan2.2' : s.motionEngine
  return `${name} · ${s.maxShotSec}s/镜 · ${s.nativeFps}fps`
})

const nextStage = computed(() => {
  const key = status.value?.nextStage
  if (!key) return null
  return status.value?.stages.find((s) => s.stage === key) ?? null
})

const allDone = computed(() => !!status.value && !status.value.nextStage)

const confirmLabel = computed(() => {
  const s = nextStage.value
  if (!s) return '全部完成'
  return `确定并生成：${s.label}`
})

async function load(silent = false): Promise<void> {
  if (!enabled.value) {
    status.value = null
    return
  }
  if (!silent) loading.value = true
  try {
    status.value = await getRunStatus(props.workspaceId, props.projectId, props.revisionId)
    schedulePoll()
    // 自动把镜头时长按「引擎能力 + 台词时长」重排一次（不跑 LLM 写台词，很快）
    if (!autoPrepared && status.value.isApprovedRevision && !status.value.timingReady
        && status.value.shotCount > 0) {
      autoPrepared = true
      void autoPrepareTiming()
    }
  } catch (e) {
    if (!silent) message.error(e instanceof Error ? e.message : '读取制作流程失败')
  } finally {
    loading.value = false
  }
}

function schedulePoll(): void {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  const running = (status.value?.stages ?? []).some((s) => s.state === 'running')
  if (!running) return
  timer = setTimeout(() => void load(true), 8000)
}

async function autoPrepareTiming(): Promise<void> {
  try {
    await replanRun(props.workspaceId, props.projectId, props.revisionId)
    await load(true)
    emit('changed')
  } catch {
    // 自动优化失败不打扰用户：手动点「准备」会再试一次并给出错误
  }
}

async function prepare(): Promise<void> {
  busy.value = 'prepare'
  try {
    const r = await prepareRun(props.workspaceId, props.projectId, props.revisionId, true)
    const parts = [`镜头 ${r.shotCount} 个 · 规划 ${r.plannedSec}s / 目标 ${r.targetSec}s`]
    if (r.voiceBindingsAdded) parts.push(`新增音色 ${r.voiceBindingsAdded}`)
    if (r.linesAdded) parts.push(`生成台词 ${r.linesAdded} 段`)
    message.success(parts.join('｜'))
    for (const n of r.notes ?? []) message.info(n, { duration: 6000 })
    await load(true)
    emit('changed')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '准备失败')
  } finally {
    busy.value = ''
  }
}

async function confirmNext(): Promise<void> {
  const s = nextStage.value
  if (!s) return
  busy.value = 'confirm'
  try {
    status.value = await confirmRunStage(props.workspaceId, props.projectId, props.revisionId, s.stage)
    message.success(`已确认「${s.label}」并下发任务`)
    schedulePoll()
    emit('changed')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    busy.value = ''
  }
}

async function retry(s: RunStage): Promise<void> {
  busy.value = `retry-${s.stage}`
  try {
    status.value = await retryRunStage(props.workspaceId, props.projectId, props.revisionId, s.stage)
    message.success(`已重试「${s.label}」的失败任务`)
    schedulePoll()
    emit('changed')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重试失败')
  } finally {
    busy.value = ''
  }
}

watch(
  () => [props.workspaceId, props.projectId, props.revisionId] as const,
  () => {
    autoPrepared = false
    void load()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <section v-if="enabled" class="run-panel" data-testid="production-run-panel">
    <div class="run-head">
      <span class="font-mono eyebrow">制作流程 · 一键成片</span>
      <span v-if="status" class="run-engine text-secondary font-mono">{{ engineLabel }}</span>
      <NTag v-if="status" size="small" :bordered="false" type="default">
        {{ status.plannedSec }}s / 目标 {{ status.targetSec }}s · {{ status.shotCount }} 镜
      </NTag>
      <NTag v-if="status && !status.timingReady" size="small" type="warning" :bordered="false">
        时长未按台词优化
      </NTag>
    </div>

    <NAlert v-if="disabled" type="warning" :bordered="false" size="small" class="run-alert">
      请先在分镜台确认这一版方案，制作流程才会绑定确认稿。
    </NAlert>

    <NSpin :show="loading">
      <ol v-if="status" class="stages">
        <li v-for="s in status.stages" :key="s.stage" class="stage" :class="`is-${s.state}`">
          <div class="stage-row">
            <span class="stage-label">{{ s.label }}</span>
            <NTag size="tiny" :bordered="false" :type="STATE_TYPE[s.state] ?? 'default'">
              {{ STATE_LABEL[s.state] ?? s.state }}
            </NTag>
            <span class="stage-count font-mono">{{ s.done }}/{{ s.total }}</span>
            <NButton
              v-if="s.state === 'failed'"
              size="tiny"
              secondary
              :loading="busy === `retry-${s.stage}`"
              @click="retry(s)"
            >
              重试失败
            </NButton>
          </div>
          <div class="stage-hint text-secondary">{{ s.hint }}</div>
        </li>
      </ol>

      <div class="run-actions">
        <NButton
          size="small"
          secondary
          :loading="busy === 'prepare'"
          :disabled="disabled"
          data-testid="run-prepare"
          @click="prepare"
        >
          准备（按剧情自动台词 + 按引擎能力/台词时长优化分镜）
        </NButton>
        <NButton
          type="primary"
          :loading="busy === 'confirm'"
          :disabled="disabled || allDone"
          data-testid="run-next"
          @click="confirmNext"
        >
          {{ confirmLabel }}
        </NButton>
        <span v-if="allDone" class="done-note">已完成全流程，可到「导出」下载成片</span>
      </div>
    </NSpin>
  </section>
</template>

<style scoped>
.run-panel {
  margin: 14px 0;
  padding: 14px 16px;
  border: 1px solid var(--wv-border, #24221f);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.02);
}
.run-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.run-alert {
  margin-bottom: 10px;
}
.stages {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: grid;
  gap: 8px;
}
.stage {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--wv-border, #24221f);
}
.stage.is-done {
  opacity: 0.72;
}
.stage.is-blocked {
  opacity: 0.6;
}
.stage-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stage-label {
  font-weight: 600;
  min-width: 88px;
}
.stage-count {
  font-size: 12px;
  color: #a39e93;
}
.stage-hint {
  font-size: 12px;
  margin-top: 2px;
}
.run-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.done-note {
  font-size: 12px;
  color: #7aa87a;
}
</style>
