<script setup lang="ts">
import { Film } from 'lucide-vue-next'
import { NButton, NIcon, NInput, NInputNumber, NSelect, NSwitch } from 'naive-ui'
import { computed, ref, watch } from 'vue'

import MusicTimeline from '@/components/director/MusicTimeline.vue'
import NarrationTimeline from '@/components/director/NarrationTimeline.vue'
import ShotCard from '@/components/director/ShotCard.vue'
import VoiceBindingsTable from '@/components/director/VoiceBindingsTable.vue'
import type { DirectorShot, ShotRecord, VideoPlan } from '@/api/types'
import { MUSIC_MOOD_PRESETS, VOICE_PRESETS, moodOptions } from '@/utils/audio'

const props = defineProps<{
  plan: VideoPlan
  /** 落库镜头状态（shotNo → status） */
  records?: ShotRecord[]
  /** 整版已确认：锁编辑 */
  disabled?: boolean
  busyShot?: number | null
  /** 配音试听中 */
  previewBusy?: boolean
  /** 试听播放条（父级持有 URL，这里按 slot 把它靠到对应按钮下一行） */
  audioPreview?: {
    url: string
    label: string
    kind: 'voice' | 'bgm'
    slot?: 'voice' | 'line' | 'music'
  } | null
  /** P10：各段配音实际时长（"镜号:段号" → 毫秒），用于字幕对齐提示与超长判定 */
  durations?: Record<string, number>
  /** P12：已封版镜号（资源达标，批量生成会跳过） */
  lockedShots?: number[]
}>()

const emit = defineEmits<{
  approveShot: [shotNo: number]
  aiPrompt: [shot: DirectorShot]
  aiSyncAll: []
  previewVoice: [shotNo?: number]
  /** P12：切换某镜封版 */
  toggleLock: [shotNo: number, locked: boolean]
  previewBgm: []
  /** P8：单条重生成 / 单条试听 / 单条导入配音 */
  genLine: [shotNo: number, lineIndex: number]
  previewLine: [shotNo: number, lineIndex: number]
  importLine: [shotNo: number, lineIndex: number, file: File, atSec: number, subject: string]
  /** P9：打开克隆配音弹窗（preset=设为角色音色；line=也可直接用这段当本行配音） */
  cloneVoice: [ctx: { mode: 'preset' | 'line'; name?: string; shotNo?: number; lineIndex?: number; atSec?: number; subject?: string; replaceId?: string }]
  /** P10：分镜请求调整本镜（延长时长 / 允许溢出）—— 由父级回写以触发脏标记 */
  patchShot: [shotNo: number, patch: { duration_sec?: number; allowNarrationOverflow?: boolean }]
  /** P11：AI 一键生成台词 / 一键配乐 */
  aiLines: [shotNo: number]
  aiMusic: []
  /** P9：删除音色（父级调 API + 清理引用） */
  removePreset: [id: string]
  /** 方案被就地修改（改名等），父级用于触发 dirty */
  'update:plan': []
  closePreview: []
}>()

const hasAction = computed(() =>
  (props.plan.shots ?? []).some((s) => (s.action ?? '').trim().length > 0))

/** P8：是否有任何语音内容（旁白或台词），用于渲染前景提示 */
const hasNarration = computed(() => {
  const anyLine = (props.plan.shots ?? []).some(
    (s) => (s.narration ?? '').trim().length > 0
      || (s.narrations ?? []).some((l) => (l.text ?? '').trim().length > 0),
  )
  return anyLine
})
void hasNarration   // 模板里作为提示用，保留导出给后续联动

/** P8：已知角色名（角色音色绑定 ∪ 参考图主体 ∪ 分镜里的说话人）——给绑定表与分镜面板做下拉 */
const knownSubjects = computed(() => {
  const out = new Set<string>()
  // P13：先把「剧情主体」放进来（一键生成主体抽出的 宝玉/秦可卿 等）。
  // 否则角色绑定表的下拉里**选不到任何角色**，而 AI 台词又提示“请先绑定角色”（自相矛盾）。
  for (const s of (props.plan as unknown as { subjects?: Array<{ name?: string }> }).subjects ?? []) {
    if ((s.name ?? '').trim()) out.add(String(s.name).trim())
  }
  for (const b of props.plan.audio?.voiceBindings ?? []) {
    if ((b.subject ?? '').trim()) out.add(b.subject.trim())
  }
  for (const r of props.plan.referenceAssets ?? []) {
    if ((r.subject ?? '').trim()) out.add((r.subject as string).trim())
  }
  for (const sh of props.plan.shots ?? []) {
    for (const l of sh.narrations ?? []) {
      if ((l.subject ?? '').trim()) out.add((l.subject as string).trim())
    }
  }
  return [...out]
})

/** P8：更新单个分镜（子组件拖拽后回写） */
function onShotUpdate(shot: DirectorShot): void {
  const i = (props.plan.shots ?? []).indexOf(shot)
  if (i >= 0) props.plan.shots[i] = shot
}

/** P9：音色库操作（录音色 / 重录 / 删除都在父级做 API 调用） */

const editingId = ref('')
const editingName = ref('')

function startRename(p: { id: string; name: string }): void {
  editingId.value = p.id
  editingName.value = p.name
}

function commitRename(): void {
  const p = clonePresets.value.find((x) => x.id === editingId.value)
  const nm = editingName.value.trim()
  if (p && nm) p.name = nm
  editingId.value = ''
  emit('update:plan')
}

/** P12：当前“配音音色”下拉选中的是不是一个克隆音色（决定重录/改名/删除是否可用） */
const selectedPreset = computed(() => {
  const v = props.plan.audio?.voice ?? ''
  if (!v.startsWith('clone:')) return undefined
  const id = v.slice('clone:'.length)
  return clonePresets.value.find((p) => p.id === id)
})

function rerecordSelected(): void {
  const p = selectedPreset.value
  if (p) emit('cloneVoice', { mode: 'preset', replaceId: p.id, name: p.name })
}

function renameSelected(): void {
  const p = selectedPreset.value
  if (p) startRename(p)
}

function deleteSelected(): void {
  const p = selectedPreset.value
  if (p) emit('removePreset', p.id)
}

/** 该音色被多少处引用（绑定 + 分镜行内覆盖），删除前提示用 */
function presetUsage(id: string): { subjects: string[]; lines: number } {
  const v = `clone:${id}`
  const subjects: string[] = []
  for (const b of props.plan.audio?.voiceBindings ?? []) {
    if (b.voice === v) subjects.push(b.subject || '(未命名角色)')
  }
  let lines = 0
  for (const sh of props.plan.shots ?? []) {
    for (const l of sh.narrations ?? []) {
      if ((l.voice ?? '') === v) lines++
    }
  }
  return { subjects, lines }
}

/** 音色选项 = 内置 7 个 + 本项目已录的克隆音色（clone:<id>） */
const clonePresets = computed(() => props.plan.audio?.voicePresets ?? [])
const voiceChoices = computed(() => [
  ...VOICE_PRESETS.map((v) => ({ label: v, value: v })),
  ...clonePresets.value.map((p) => ({ label: `🎙 ${p.name}（克隆）`, value: `clone:${p.id}` })),
])

/** P9：角色绑定的语速（subject → speed），下发给分镜用于提示“实际会用多快” */
const speedHints = computed(() => {
  const m: Record<string, number> = {}
  for (const b of props.plan.audio?.voiceBindings ?? []) {
    if (b.subject && typeof b.speed === 'number') m[b.subject] = b.speed
  }
  return m
})

/** 成片总时长 = 各镜时长之和；修改镜头时长后同步 plan.duration_sec。 */
const totalDur = computed(() =>
  (props.plan.shots ?? []).reduce((a, s) => a + (Number(s.duration_sec) || 0), 0))

// P12：逐镜的三个长列表（分镜表 / 镜头时长 / 配音时间轴）默认只展示 5 行，各自可展开
const LIST_PAGE = 5
const shotShown = ref(LIST_PAGE)
const durShown = ref(LIST_PAGE)
const ntShown = ref(LIST_PAGE)
const allShots = computed(() => props.plan.shots ?? [])
const visibleShots = computed(() => allShots.value.slice(0, shotShown.value))
const visibleDurShots = computed(() => allShots.value.slice(0, durShown.value))
const visibleNtShots = computed(() => allShots.value.slice(0, ntShown.value))
watch(
  () => (props.plan.shots ?? []).map((s) => s.duration_sec).join(','),
  () => {
    props.plan.duration_sec = Math.round(totalDur.value * 100) / 100
  },
)

const statusOf = (no: number): string =>
  props.records?.find((r) => r.shotNo === no)?.status ?? 'draft'

const approvedAll = computed(() => props.disabled)

const transitions = ['cut', 'dissolve', 'fade', 'wipe'].map((v) => ({ label: v, value: v }))
</script>

<template>
  <div class="editor-stack" data-testid="video-plan-editor">
    <section class="block">
      <p class="block-label font-mono">主题</p>
      <template v-if="props.plan.script">
        <label class="row">
          <span class="key">主题 theme（中文）</span>
          <NInput v-model:value="props.plan.script.theme" size="small" :disabled="disabled" />
        </label>
      </template>
    </section>

    <section class="block">
      <div class="zh-head">
        <p class="block-label font-mono" style="margin: 0">
          <NIcon size="12" style="vertical-align: -1px"><Film /></NIcon>&nbsp;分镜 / 镜头表
          <span class="hint">（逐镜可改可单镜确认；展开卡片编辑 EN 提示词）</span>
        </p>
        <NButton
          size="small"
          type="primary"
          secondary
          :disabled="!!disabled || !hasAction"
          data-testid="ai-sync-all"
          @click="emit('aiSyncAll')"
        >
          AI 同步提示词（按画面动作）
        </NButton>
      </div>
      <div class="shot-list">
        <ShotCard
          v-for="shot in visibleShots"
          :key="shot.shot_no"
          :shot="shot"
          :status="statusOf(shot.shot_no)"
          :disabled="!!disabled || approvedAll"
          :busy="busyShot === shot.shot_no"
          :preview-busy="previewBusy"
          :locked="(props.lockedShots ?? []).includes(shot.shot_no)"
          @toggle-lock="(no: number, l: boolean) => emit('toggleLock', no, l)"
          @approve="emit('approveShot', $event)"
          @preview-voice="emit('previewVoice', $event)"
        />
        <button
          v-if="allShots.length > shotShown"
          type="button"
          class="more-btn"
          data-testid="btn-more-shots"
          @click="shotShown += LIST_PAGE"
        >
          查看更多（余 {{ allShots.length - shotShown }} 镜）
        </button>
      </div>
    </section>

    <section class="block">
      <p class="block-label font-mono">编辑设定 edit_plan</p>
      <template v-if="props.plan.edit_plan">
      <div class="grid3">
        <label class="row">
          <span class="key">fps</span>
          <NInputNumber v-model:value="props.plan.edit_plan.fps" size="small" :min="24" :max="60" :disabled="disabled" />
        </label>
        <label class="row">
          <span class="key">默认转场</span>
          <select v-model="props.plan.edit_plan.transition_default" class="select" :disabled="disabled">
            <option v-for="t in transitions" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </label>
        <label class="row switch-row">
          <span class="key">字幕</span>
          <NSwitch v-model:value="props.plan.edit_plan.subtitle" size="small" :disabled="disabled" />
        </label>
      </div>
      <!--
        P13：视频模型单次输出上限与呼吸余量 —— 「按配音校准时长」用它决定镜头该多长、要不要切段。
        不同模型不一样（i2v 常见 5s，部分 15s+），所以做成项目级设定。
      -->
      <div class="grid3">
        <label class="row">
          <span class="key" title="你所选视频模型单次能输出的最长秒数；超出就自动切段生成">模型上限(s)</span>
          <NInputNumber
            :value="props.plan.edit_plan.video_model_max_sec ?? 5"
            size="small"
            :min="1"
            :max="60"
            :step="1"
            :disabled="disabled"
            @update:value="(v: number | null) => props.plan.edit_plan && (props.plan.edit_plan.video_model_max_sec = v ?? 5)"
          />
        </label>
        <label class="row">
          <span class="key" title="镜头尾部留白，避免配音贴着画面切走">呼吸余量(s)</span>
          <NInputNumber
            :value="props.plan.edit_plan.tail_sec ?? 0.3"
            size="small"
            :min="0"
            :max="3"
            :step="0.1"
            :disabled="disabled"
            @update:value="(v: number | null) => props.plan.edit_plan && (props.plan.edit_plan.tail_sec = v ?? 0.3)"
          />
        </label>
        <label class="row">
          <span class="key" title="镜长由谁决定：镜长固定(配音顺排溢出下一镜，每镜 1 次调用) / 镜长跟配音">时长模式</span>
          <select
            :value="props.plan.edit_plan.timing_mode ?? 'shot_fixed'"
            class="select"
            :disabled="disabled"
            @change="(e: Event) => props.plan.edit_plan && (props.plan.edit_plan.timing_mode = (e.target as HTMLSelectElement).value as 'shot_fixed' | 'audio_first')"
          >
            <option value="shot_fixed">镜长固定·配音顺排（默认，省调用）</option>
            <option value="audio_first">镜长跟配音（对话/口型）</option>
          </select>
        </label>
        <label class="row">
          <span
            class="key"
            :title="(props.plan.edit_plan.timing_mode ?? 'shot_fixed') === 'shot_fixed'
              ? '当前是镜长固定模式，配音顺排溢出，不需要超长策略（仅 audio_first 生效）'
              : '配音比模型单次上限长时怎么处理；这直接决定云端调用次数（钱）'"
          >超长策略</span>
          <select
            :value="props.plan.edit_plan.oversize_policy ?? 'stretch'"
            class="select"
            :disabled="disabled || (props.plan.edit_plan.timing_mode ?? 'shot_fixed') === 'shot_fixed'"
            @change="(e: Event) => props.plan.edit_plan && (props.plan.edit_plan.oversize_policy = (e.target as HTMLSelectElement).value as 'stretch' | 'overflow' | 'segment')"
          >
            <option value="stretch">本地拉伸（不额外花钱）</option>
            <option value="overflow">配音溢出下一镜（不额外花钱）</option>
            <option value="segment">切段生成（每多一段多一次调用）</option>
          </select>
        </label>
      </div>
      </template>
    </section>

    <section class="block">
      <p class="block-label font-mono">镜头时长（成片总长 {{ totalDur.toFixed(2) }}s）</p>
      <p class="hint-line text-secondary">
        逐镜调节时长后「保存方案」即生效；云端按镜头计费，短镜更省。留空镜将跳过字幕，时长需 ≥1s。
      </p>
      <div class="dur-grid">
        <label v-for="shot in visibleDurShots" :key="shot.shot_no" class="dur-cell">
          <span class="dur-no font-mono">第 {{ shot.shot_no }} 镜</span>
          <span class="dur-input">
            <NInputNumber
              v-model:value="shot.duration_sec"
              :min="1"
              :max="10"
              :step="0.5"
              size="small"
              style="width: 100%"
              :disabled="!!disabled"
            />
            <span class="dur-unit">秒</span>
            <span
              v-if="(shot.segments?.length ?? 0) > 1"
              class="dur-segs font-mono"
              :title="'超过模型单次上限，按配音校准后切成 ' + (shot.segments?.length ?? 0) + ' 段生成（同镜内 cut 拼接）'"
            >{{ shot.segments?.length }} 段</span>
          </span>
        </label>
      </div>
      <button
        v-if="allShots.length > durShown"
        type="button"
        class="more-btn"
        data-testid="btn-more-durs"
        @click="durShown += LIST_PAGE"
      >
        查看更多（余 {{ allShots.length - durShown }} 镜）
      </button>
    </section>

    <!-- P12：声音相关全部收进一张卡片，顺序=音色 → 绑定 → 配音 → 配乐 -->
    <section class="block audio-card" data-testid="audio-card">
      <p class="block-label font-mono">声音（音色 / 角色音色绑定 / 配音 / 配乐）</p>

      <div class="sub-block">
        <p class="sub-label">① 配音音色</p>
        <div class="voice-row">
          <span class="key">配音音色 voice（预设 / 克隆音色 / 参考音频路径）</span>
          <NSelect
            v-model:value="props.plan.audio.voice"
            :options="voiceChoices"
            size="small"
            filterable
            tag
            :disabled="disabled"
            placeholder="中文女"
          />
          <NButton
            size="tiny"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-voice"
            title="用当前音色念一句试听"
            @click="emit('previewVoice', undefined)"
          >
            音色试听
          </NButton>
          <NButton
            size="tiny"
            quaternary
            :disabled="!!disabled || !selectedPreset"
            :data-testid="`preset-rerecord-selected`"
            title="重新录一段替换当前克隆音色"
            @click="rerecordSelected"
          >
            重录
          </NButton>
          <NButton
            size="tiny"
            quaternary
            :disabled="!!disabled || !selectedPreset"
            @click="renameSelected"
          >
            改名
          </NButton>
          <NButton
            size="tiny"
            quaternary
            type="error"
            :disabled="!!disabled || !selectedPreset"
            :data-testid="`preset-delete-selected`"
            @click="deleteSelected"
          >
            删除
          </NButton>
        </div>
        <!-- P12：音色试听的播放器就在「音色试听」按钮下一行 -->
        <div v-if="props.audioPreview && props.audioPreview.slot === 'voice'" class="voice-preview inline" data-testid="audio-preview-voice">
          <span class="font-mono vp-label">🎙 试听 · {{ props.audioPreview.label }}</span>
          <audio :src="props.audioPreview.url" class="vp-audio" controls autoplay preload="auto" />
          <NButton size="tiny" quaternary @click="emit('closePreview')">关闭</NButton>
        </div>
        <p v-if="!selectedPreset" class="hint-line text-secondary">
          上面选一个「克隆」音色后，重录 / 改名 / 删除才可用
        </p>
        <div class="vc-entry">
          <NButton
            size="tiny"
            secondary
            :disabled="!!disabled"
            data-testid="btn-clone-voice"
            title="录一段声音或上传样本，处理成可复用的音色"
            @click="emit('cloneVoice', { mode: 'preset' })"
          >
            🎙 克隆音色（录音 / 上传样本）
          </NButton>
          <span class="text-secondary" style="font-size: 12px">
            录好的音色会出现在上面的下拉里（标「克隆」）
          </span>
        </div>
        <!-- P9 音色库：重录 / 改名 / 删除（录多了可以删，也能重录换掉） -->
        <div v-if="clonePresets.length" class="vc-lib" data-testid="voice-preset-list">
          <div v-for="p in clonePresets" :key="p.id" class="vc-lib-row">
            <span class="vc-lib-dot">🎙</span>
            <template v-if="editingId === p.id">
              <NInput
                v-model:value="editingName"
                size="tiny"
                style="max-width: 160px"
                @keyup.enter="commitRename"
              />
              <NButton size="tiny" type="primary" @click="commitRename">保存</NButton>
              <NButton size="tiny" quaternary @click="editingId = ''">取消</NButton>
            </template>
            <template v-else>
              <span class="vc-lib-name">{{ p.name }}</span>
              <span class="text-secondary font-mono vc-lib-meta">
                {{ Number(p.durationSec ?? 0).toFixed(1) }}s
                <template v-if="presetUsage(p.id).subjects.length">
                  · 用于 {{ presetUsage(p.id).subjects.join('、') }}
                </template>
                <template v-if="presetUsage(p.id).lines">
                  · {{ presetUsage(p.id).lines }} 行
                </template>
              </span>
              <NButton
                size="tiny"
                quaternary
                :disabled="!!disabled"
                :data-testid="`preset-rerecord-${p.id}`"
                title="重新录一段替换这个音色"
                @click="emit('cloneVoice', { mode: 'preset', replaceId: p.id, name: p.name })"
              >
                重录
              </NButton>
              <NButton size="tiny" quaternary :disabled="!!disabled" @click="startRename(p)">改名</NButton>
              <NButton
                size="tiny"
                quaternary
                type="error"
                :disabled="!!disabled"
                :data-testid="`preset-delete-${p.id}`"
                @click="emit('removePreset', p.id)"
              >
                删除
              </NButton>
            </template>
          </div>
        </div>
      </div>

      <div class="sub-block">
        <p class="sub-label">② 角色音色绑定（按说话人自动关联）</p>
        <VoiceBindingsTable
          :plan="props.plan"
          :disabled="disabled"
          :voices="voiceChoices"
          :known-subjects="knownSubjects"
        />
      </div>

      <div class="sub-block">
        <p class="sub-label">③ 配音（逐镜旁白 / 台词，可拖拽定位、一镜多段）</p>
      <!-- P12：单条/本镜试听的播放器就放在本条下面（只在该槽位时出现） -->
      <div v-if="props.audioPreview && props.audioPreview.slot === 'line'" class="voice-preview inline" data-testid="audio-preview-line">
        <span class="font-mono vp-label">🎙 试听 · {{ props.audioPreview.label }}</span>
        <audio :src="props.audioPreview.url" class="vp-audio" controls autoplay preload="auto" />
        <NButton size="tiny" quaternary @click="emit('closePreview')">关闭</NButton>
      </div>
      <p class="hint-line text-secondary">
        拖块改起点；旁白短于镜头就留白（不会为填满而慢放），长于镜头才建议加快语速
      </p>
      <div v-for="shot in visibleNtShots" :key="shot.shot_no" class="nt-row">
        <span class="key narration-key">第 {{ shot.shot_no }} 镜</span>
        <div class="nt-slot">
          <NarrationTimeline
            :shot="shot"
            :disabled="disabled"
            :busy="previewBusy"
            :subjects="knownSubjects"
            :voices="voiceChoices"
            :speed-hints="speedHints"
            :durations="props.durations ?? {}"
            @extend-shot="(no, patch) => emit('patchShot', no, patch)"
            @ai-lines="(no) => emit('aiLines', no)"
            @update:shot="onShotUpdate"
            @gen-line="(no, li) => emit('genLine', no, li)"
            @preview-line="(no, li) => emit('previewLine', no, li)"
            @import-line="(no, li, f, at, sub) => emit('importLine', no, li, f, at, sub)"
            @clone-line="(no, li, at, sub) => emit('cloneVoice', { mode: 'line', shotNo: no, lineIndex: li, atSec: at, subject: sub, name: sub })"
          />
        </div>
      </div>
      <button
        v-if="allShots.length > ntShown"
        type="button"
        class="more-btn"
        data-testid="btn-more-narrations"
        @click="ntShown += LIST_PAGE"
      >
        查看更多（余 {{ allShots.length - ntShown }} 镜）
      </button>
      </div>

      <div class="sub-block">
        <p class="sub-label">④ 配乐（多段：起止 / 强弱 / 淡入淡出）</p>
        <label class="row">
          <span class="key">BGM 情绪 music_mood（可直接输入自定义）</span>
          <NSelect
            v-model:value="props.plan.audio.music_mood"
            :options="moodOptions"
            size="small"
            filterable
            tag
            :disabled="disabled"
            placeholder="如：浪漫柔情 / 史诗磅礴"
          />
        </label>
        <div class="row">
          <span class="key">配乐试听</span>
          <NButton
            size="tiny"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-bgm"
            title="按当前情绪生成一条试听"
            @click="emit('previewBgm')"
          >
            试听配乐
          </NButton>
          <span class="text-secondary" style="font-size: 12px">
            换情绪后点一次即重生成；正式生成在下方任务区「生成配乐」
          </span>
        </div>
        <!-- P12：配乐试听的播放器就在「试听配乐」按钮下一行 -->
        <div v-if="props.audioPreview && props.audioPreview.slot === 'music'" class="voice-preview inline" data-testid="audio-preview-music">
          <span class="font-mono vp-label">🎵 试听 · {{ props.audioPreview.label }}</span>
          <audio :src="props.audioPreview.url" class="vp-audio" controls autoplay preload="auto" />
          <NButton size="tiny" quaternary @click="emit('closePreview')">关闭</NButton>
        </div>
      <div class="ai-bar">
        <NButton
          size="tiny"
          secondary
          :disabled="!!disabled"
          data-testid="btn-ai-music"
          title="让 AI 根据剧情把全片划分成 2~5 段配乐（含情绪与强弱）"
          @click="emit('aiMusic')"
        >
          ✨ AI 一键配乐（按剧情分段）
        </NButton>
        <span class="text-secondary" style="font-size: 12px">
          会覆盖现有的配乐段落；生成后点「生成配乐」按情绪渲染
        </span>
      </div>
      <p class="hint-line text-secondary">
        成片总长 {{ totalDur.toFixed(2) }}s；每段独立音量，追赶/高潮段可调高并换情绪
      </p>
      <MusicTimeline
        :plan="props.plan"
        :disabled="disabled"
        :moods="MUSIC_MOOD_PRESETS"
      />
      </div>
    </section>
  </div>
</template>

<style scoped>
.narration-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.ai-label {
  display: block;
  font-size: 12px;
  color: var(--wv-text-3);
  margin: 6px 0 4px;
}
.shot-prompt {
  border: 1px solid var(--wv-divider);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.sp-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}
.zh-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.zh-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.zh-row .n-input {
  flex: 1;
}
.hint-line {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
}
.narration-key {
  flex: none;
  width: 60px;
}
.editor-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.block-label {
  margin: 0;
  font-size: 10px;
  letter-spacing: 0.22em;
  color: var(--wv-text-4);
}
.hint {
  letter-spacing: 0;
  font-family: var(--wv-font-sans);
  color: var(--wv-text-4);
  font-size: 11px;
}
.row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.key {
  font-size: 11.5px;
  color: var(--wv-text-3);
  font-family: var(--wv-font-mono);
}
.grid3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.switch-row {
  justify-content: center;
}
.shot-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.select {
  background: var(--wv-surface-sunken);
  border: 1px solid var(--wv-line);
  border-radius: 6px;
  color: var(--wv-text-2);
  font-family: var(--wv-font-mono);
  font-size: 12px;
  padding: 6px 8px;
  outline: none;
}
.select:focus {
  border-color: color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
}
/* P8 分镜语音时间轴行：与上方「镜头时长」行保持同一种左侧标签对齐 */
.nt-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}
.nt-row .narration-key {
  flex: 0 0 auto;
  padding-top: 4px;
  min-width: 56px;
}
.nt-slot {
  flex: 1 1 auto;
  min-width: 0;
}
.vc-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0;
}
/* P12：长列表的「查看更多（余 N 条）」按钮 */
.more-btn {
  appearance: none;
  width: 100%;
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--wv-text-3);
  background: var(--wv-surface-sunken);
  border: 1px dashed var(--wv-line);
  border-radius: 8px;
  cursor: pointer;
}
.more-btn:hover {
  color: var(--wv-text-2);
  border-color: var(--wv-line-strong, var(--wv-line));
}

/* ---------- P12：试听播放器（就在触发它的按钮下一行） ---------- */
.voice-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 7px 10px;
  margin: 6px 0 4px;
  border: 1px solid var(--wv-line);
  border-radius: 8px;
  background: var(--wv-surface-sunken);
}
.voice-preview.inline { margin: 6px 0 2px; }
.vp-label {
  font-size: 11px;
  color: var(--wv-text-3);
  flex: none;
}
.vp-audio {
  flex: 1 1 240px;
  min-width: 180px;
  height: 34px;
}
@media (max-width: 640px) {
  .vp-audio {
    flex: 1 1 100%;
    min-width: 0;
  }
}
/* P12：镜头时长自适应网格（桌面多列，手机 1~2 列） */
.dur-grid {
  display: grid;
  /* P12：每镜一个小卡片，卡片之间留足间隙（太栅会看成一整块，分不清哪镜是哪镜） */
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px 14px;
  margin-top: 6px;
}
.dur-segs {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--wv-accent) 18%, transparent);
  color: var(--wv-accent);
  font-size: 11px;
  white-space: nowrap;
}
.dur-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--wv-line);
  border-radius: 10px;
  background: var(--wv-surface-sunken);
}
.dur-no {
  font-size: 11px;
  color: var(--wv-text-3);
  letter-spacing: 0.04em;
}
.dur-input {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dur-unit {
  font-size: 12px;
  color: var(--wv-text-3);
  flex: none;
}
/* P12：声音卡片的分块 */
.sub-block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed rgba(140, 160, 190, 0.18);
}
.sub-block:first-of-type {
  border-top: none;
  padding-top: 0;
}
.sub-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  opacity: 0.9;
}
.voice-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.voice-row .n-select {
  flex: 1 1 200px;
  min-width: 160px;
}
/* P12：手机端适配 */
@media (max-width: 640px) {
  .dur-grid {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 10px;
  }
  .dur-cell {
    padding: 7px 9px;
  }
  .voice-row > * {
    flex: 1 1 auto;
  }
}
.vc-lib {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0 0 10px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(120, 140, 170, 0.08);
  border: 1px solid rgba(140, 160, 190, 0.18);
}
.vc-lib-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.vc-lib-name {
  font-size: 12px;
  font-weight: 600;
}
.vc-lib-meta {
  font-size: 11px;
}
.vc-lib-dot {
  font-size: 12px;
}
.ai-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: -2px 0 8px;
}
</style>
