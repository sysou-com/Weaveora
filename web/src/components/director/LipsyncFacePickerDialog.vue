<script setup lang="ts">
/**
 * P13：对口型「谁在哪张脸」点选弹窗。
 *
 * 背景：LatentSync 每帧只能驱动一张脸，多人同框时按「面积最大的脸」会中途换人（画面坏掉）；
 * 而按定妆照做人脸识别，在 480p/AI 古风这类风格化素材上区分度会崩（实测同一个人的相似度
 * 只有 0.2 上下，且宝玉/警幻/袭人互相混淆）。**用户点一下**是最可靠的信号，也不受画风影响。
 *
 * 交互：选一个说话人 → 在画面上点他的脸 → 该说话人的坐标（归一化 0–1）写入方案的
 * `shots[].lipsync_targets`；再点可覆盖，点「清除」删掉。
 */
import { NButton, NModal, NTag, useMessage } from 'naive-ui'
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    show: boolean
    /** 镜号（仅用于标题） */
    shotNo: number
    /** 该镜台词说话人（有台词的才有意义） */
    speakers: string[]
    /** 已保存的点选结果 */
    targets: Record<string, { x: number; y: number }>
    /** 预览媒体地址（blob url）；无则提示先去生成画面 */
    mediaUrl?: string
    /** 是否视频（视频需要 seek 到首帧再点） */
    isVideo?: boolean
  }>(),
  { mediaUrl: '', isVideo: false },
)

const emit = defineEmits<{
  'update:show': [v: boolean]
  /** 保存某说话人的点选坐标（归一化 0–1） */
  pick: [payload: { subject: string; x: number; y: number }]
  clear: [subject: string]
}>()

const message = useMessage()
/** 当前正在给谁点 */
const active = ref('')
const mediaRef = ref<HTMLVideoElement | HTMLImageElement | null>(null)

const local = computed(() => props.targets ?? {})

watch(
  () => props.show,
  (v) => {
    if (!v) return
    active.value = props.speakers[0] ?? ''
    // 视频需要 seek 到首帧，否则画面是黑的、没法点
    if (props.isVideo) {
      requestAnimationFrame(() => {
        const el = mediaRef.value as HTMLVideoElement | null
        if (el && el.tagName === 'VIDEO') {
          const v = el as HTMLVideoElement
          const seek = () => {
            try {
              v.currentTime = 0.05
            } catch {
              /* 忽略 */
            }
          }
          if (v.readyState >= 1) seek()
          else v.addEventListener('loadedmetadata', seek, { once: true })
        }
      })
    }
  },
)

function onClickMedia(e: MouseEvent): void {
  if (!active.value) {
    message.warning('先选一个说话人，再点他/她的脸')
    return
  }
  const el = mediaRef.value as HTMLElement | null
  if (!el) return
  const r = el.getBoundingClientRect()
  if (!r.width || !r.height) return
  const x = (e.clientX - r.left) / r.width
  const y = (e.clientY - r.top) / r.height
  if (x < 0 || x > 1 || y < 0 || y > 1) return
  emit('pick', { subject: active.value, x: Number(x.toFixed(4)), y: Number(y.toFixed(4)) })
  // 顺手切到下一个还没点的说话人，连续点选更顺
  const next = props.speakers.find((s) => s !== active.value && !local.value[s])
  if (next) active.value = next
}

const COLOR = ['#E8B86D', '#7BC47F', '#7FB3D5', '#D98880', '#B39DDB']
function colorOf(name: string): string {
  const i = Math.max(0, props.speakers.indexOf(name))
  return COLOR[i % COLOR.length]
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="`对口型：指定「第 ${shotNo} 镜」每句台词是谁的脸`"
    style="max-width: 860px"
    data-testid="lipsync-face-picker"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <p class="lf-hint">
      先点下面的<b>说话人</b>，再在画面上点<u>他/她的脸</u>即可（点一次覆盖一次）。
      多人同框时 LatentSync 每帧只能驱动一张脸，指认清楚才不会把台词配到别人脸上。
    </p>

    <div class="lf-chips">
      <button
        v-for="s in speakers"
        :key="s"
        type="button"
        class="lf-chip"
        :class="{ on: active === s, done: !!local[s] }"
        :style="{ '--c': colorOf(s) }"
        :data-testid="`lf-chip-${s}`"
        @click="active = s"
      >
        <span class="lf-dot" />
        {{ s }}
        <span v-if="local[s]" class="font-mono lf-xy">
          ({{ local[s].x.toFixed(2) }}, {{ local[s].y.toFixed(2) }})
        </span>
        <span v-else class="lf-xy">未指定</span>
      </button>
      <NButton v-if="active && local[active]" size="tiny" secondary @click="emit('clear', active)">
        清除「{{ active }}」
      </NButton>
    </div>

    <div class="lf-stage">
      <template v-if="mediaUrl">
        <!-- 用 div 叠标记层，点击位置直接换算成归一化坐标 -->
        <div class="lf-frame" @click="onClickMedia">
          <video
            v-if="isVideo"
            ref="mediaRef"
            :src="mediaUrl"
            class="lf-media"
            muted
            playsinline
            preload="auto"
          />
          <img v-else ref="mediaRef" :src="mediaUrl" class="lf-media" alt="" />
          <span
            v-for="s in speakers"
            v-show="local[s]"
            :key="s"
            class="lf-mark"
            :style="{
              left: `${(local[s]?.x ?? 0) * 100}%`,
              top: `${(local[s]?.y ?? 0) * 100}%`,
              borderColor: colorOf(s),
            }"
          >
            <span class="lf-mark-label" :style="{ background: colorOf(s) }">{{ s }}</span>
          </span>
        </div>
      </template>
      <div v-else class="lf-empty">该镜还没有可用画面（先生成关键帧或 motion），无法点选人脸。</div>
    </div>

    <template #footer>
      <div class="lf-actions">
        <NTag size="small" :bordered="false">
          已指定 {{ speakers.filter((s) => local[s]).length }} / {{ speakers.length }}
        </NTag>
        <NButton size="small" type="primary" data-testid="lf-done" @click="emit('update:show', false)">
          完成
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.lf-hint {
  margin: 0 0 10px;
  font-size: 12.5px;
  color: var(--wv-text-2);
  line-height: 1.75;
}
.lf-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--wv-line);
}
.lf-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--wv-line);
  background: var(--wv-surface-sunken);
  color: var(--wv-text-2);
  font-size: 12.5px;
  cursor: pointer;
}
.lf-chip.on {
  border-color: var(--c);
  color: var(--wv-text-1);
  box-shadow: 0 0 0 1px var(--c) inset;
}
.lf-chip.done {
  font-weight: 500;
}
.lf-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c);
}
.lf-xy {
  font-size: 11px;
  color: var(--wv-text-4);
}
.lf-stage {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}
.lf-frame {
  position: relative;
  display: inline-block;
  cursor: crosshair;
  line-height: 0;
  border: 1px solid var(--wv-line);
  border-radius: 10px;
  overflow: hidden;
  max-width: 100%;
}
.lf-media {
  display: block;
  max-width: 100%;
  max-height: 52vh;
  width: auto;
}
.lf-mark {
  position: absolute;
  width: 18px;
  height: 18px;
  margin: -9px 0 0 -9px;
  border-radius: 50%;
  border: 2px solid #fff;
  background: rgba(255, 255, 255, 0.15);
  pointer-events: none;
}
.lf-mark-label {
  position: absolute;
  left: 14px;
  top: -4px;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  color: #1b1b1b;
  white-space: nowrap;
}
.lf-empty {
  padding: 28px;
  font-size: 12.5px;
  color: var(--wv-text-3);
}
.lf-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
</style>
