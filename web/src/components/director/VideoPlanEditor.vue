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
  /** 试听播放条（父级持有 URL，这里只负责靠按钮渲染） */
  audioPreview?: { url: string; label: string; kind: 'voice' | 'bgm' } | null
  /** P10：各段配音实际时长（"镜号:段号" → 毫秒），用于字幕对齐提示与超长判定 */
  durations?: Record<string, number>
}>()

const emit = defineEmits<{
  approveShot: [shotNo: number]
  aiPrompt: [shot: DirectorShot]
  aiSyncAll: []
  previewVoice: [shotNo?: number]
  previewBgm: []
  /** P8：单条重生成 / 单条试听 / 单条导入配音 */
  genLine: [shotNo: number, lineIndex: number]
  previewLine: [shotNo: number, lineIndex: number]
  importLine: [shotNo: number, lineIndex: number, file: File, atSec: number, subject: string]
  /** P9：打开克隆配音弹窗（preset=设为角色音色；line=也可直接用这段当本行配音） */
  cloneVoice: [ctx: { mode: 'preset' | 'line'; name?: string; shotNo?: number; lineIndex?: number; atSec?: number; subject?: string; replaceId?: string }]
  /** P10：分镜请求调整本镜（延长时长 / 允许溢出）—— 由父级回写以触发脏标记 */
  patchShot: [shotNo: number, patch: { duration_sec?: number; allowNarrationOverflow?: boolean }]
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
      <template v-if="props.plan.audio">
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
        <label class="row">
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
        </label>
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
        <div class="zh-head">
          <NButton
            size="small"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-voice"
            @click="emit('previewVoice', undefined)"
          >
            试听配音
          </NButton>
          <NButton
            size="small"
            secondary
            :loading="previewBusy"
            :disabled="!!disabled"
            data-testid="btn-preview-bgm"
            @click="emit('previewBgm')"
          >
            试听配乐
          </NButton>
          <span class="text-secondary" style="font-size: 12px">
            换音色/情绪后点对应试听即可重生成一条试听；正式生成在下方任务区（生成配音/生成配乐）
          </span>
        </div>
        <!-- P8：试听播放条就放在试听按钮下方（原来在页面中部，离按钮太远） -->
        <div v-if="props.audioPreview" class="voice-preview inline" data-testid="audio-preview">
          <span class="font-mono vp-label">
            {{ props.audioPreview.kind === 'bgm' ? '🎵' : '🎙' }} 试听 · {{ props.audioPreview.label }}
          </span>
          <audio :src="props.audioPreview.url" class="vp-audio" controls autoplay preload="auto" />
          <NButton size="tiny" quaternary @click="emit('closePreview')">关闭</NButton>
        </div>
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
          v-for="shot in props.plan.shots"
          :key="shot.shot_no"
          :shot="shot"
          :status="statusOf(shot.shot_no)"
          :disabled="!!disabled || approvedAll"
          :busy="busyShot === shot.shot_no"
          :preview-busy="previewBusy"
          @approve="emit('approveShot', $event)"
          @preview-voice="emit('previewVoice', $event)"
        />
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
      </template>
    </section>

    <section class="block">
      <p class="block-label font-mono">镜头时长（成片总长 {{ totalDur.toFixed(2) }}s）</p>
      <p class="hint-line text-secondary">
        逐镜调节时长后「保存方案」即生效；云端按镜头计费，短镜更省。留空镜将跳过字幕，时长需 ≥1s。
      </p>
      <div
        v-for="shot in props.plan.shots"
        :key="shot.shot_no"
        class="narration-row"
      >
        <span class="key narration-key">第 {{ shot.shot_no }} 镜</span>
        <NInputNumber
          v-model:value="shot.duration_sec"
          :min="1"
          :max="10"
          :step="0.5"
          size="small"
          style="width: 120px"
          :disabled="!!disabled"
        />
        <span class="hint-line text-secondary">秒</span>
      </div>
    </section>

    <section class="block">
      <p class="block-label font-mono">角色音色绑定（按说话人自动关联）</p>
      <VoiceBindingsTable
        :plan="props.plan"
        :disabled="disabled"
        :voices="voiceChoices"
        :known-subjects="knownSubjects"
      />
    </section>

    <section class="block">
      <p class="block-label font-mono">
        配乐时间轴（可多段：起止 / 强弱 / 淡入淡出）
      </p>
      <p class="hint-line text-secondary">
        成片总长 {{ totalDur.toFixed(2) }}s；每段独立音量，追赶/高潮段可调高并换情绪
      </p>
      <MusicTimeline
        :plan="props.plan"
        :disabled="disabled"
        :moods="MUSIC_MOOD_PRESETS"
      />
    </section>

    <section class="block">
      <p class="block-label font-mono">旁白 / 台词（逐镜时间轴：可拖拽定位、一镜多段）</p>
      <p class="hint-line text-secondary">
        拖块改起点；旁白短于镜头就留白（不会为填满而慢放），长于镜头才建议加快语速
      </p>
      <div v-for="shot in props.plan.shots" :key="shot.shot_no" class="nt-row">
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
            @update:shot="onShotUpdate"
            @gen-line="(no, li) => emit('genLine', no, li)"
            @preview-line="(no, li) => emit('previewLine', no, li)"
            @import-line="(no, li, f, at, sub) => emit('importLine', no, li, f, at, sub)"
            @clone-line="(no, li, at, sub) => emit('cloneVoice', { mode: 'line', shotNo: no, lineIndex: li, atSec: at, subject: sub, name: sub })"
          />
        </div>
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
  margin: -2px 0 8px;
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
</style>
