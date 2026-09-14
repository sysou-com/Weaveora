import type { DirectorPlan, DirectorShot, ImagePlan, VideoPlan } from '@/api/types'

/**
 * 与存储格式无关的**稳定序列化**：对象键递归排序后再 stringify（数组顺序保留）。
 *
 * 为什么必须这么做（2026-09-14 定位到的真因）：`prompt_revisions.schema_json` 是
 * PostgreSQL **jsonb** —— jsonb 会重排对象键（先按 key 长度、再按字节序），
 * 而前端 `draft` 用的是客户端插入顺序（新加的键一定落在对象**末尾**）。
 * 只要用 `JSON.stringify` 直接对比，就会出现「纯键序差异」被当成「方案有改动」：
 *
 *   例：第 N 镜点选人脸 → `shot.lipsync_targets = {...}` 追加到末尾 →
 *       就地保存（PATCH plan/inplace）→ 回读的 jsonb 把它排到 negative_prompt 之前 →
 *       `changeSummary` 报「分镜 N 镜有改动」→ 已确认版本上点「生成关键帧 / 对口型」
 *       仍弹「保存并确认后生成」（用户实测反馈：已确认还一直要求保存）。
 *
 * 同理适用于 参考图区域（referenceAssets.subject/region）、新加的 narrations、
 * 带 t/camera_move 的 keyframes 等一切「客户端新建的对象」。
 */
export function canonicalJson(v: unknown): string {
  return JSON.stringify(sortKeysDeep(v))
}

function sortKeysDeep(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sortKeysDeep)
  if (v && typeof v === 'object') {
    const src = v as Record<string, unknown>
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(src).sort()) out[k] = sortKeysDeep(src[k])
    return out
  }
  return v
}

/**
 * P10：把某分镜的语音按**配音实际时长**铺到镜内时间轴上（只动没被手动改过的段）。
 *
 * 规则：
 *  - `manual !== true` 的段才会被调整（用户手拖过的不碰）
 *  - 有实际时长 → `end_sec = at_sec + 实际时长`
 *  - 下一段若与上一段重叠 → 把下一段后移到上一段结束（仅当它非手动）
 *
 * @param durations key = `"镜号:段号"` → 毫秒
 * @returns 是否有改动
 */
export function autoLayoutShot(shot: DirectorShot, durations: Record<string, number>): boolean {
  const lines = [...(shot.narrations ?? [])].sort((a, b) => (a.at_sec ?? 0) - (b.at_sec ?? 0))
  if (!lines.length) return false
  let changed = false
  let prevEnd: number | null = null
  lines.forEach((l, i) => {
    const ms = durations[`${shot.shot_no}:${i}`]
    const actual = ms && ms > 0 ? ms / 1000 : null
    if (l.manual !== true) {
      let at = Math.max(0, l.at_sec ?? 0)
      if (prevEnd != null && at < prevEnd - 0.05) {
        at = Math.round(prevEnd * 10) / 10
        if (at !== l.at_sec) {
          l.at_sec = at
          changed = true
        }
      }
      if (actual != null) {
        const end = Math.round((at + actual) * 10) / 10
        if (Number(l.end_sec ?? 0) !== end) {
          l.end_sec = end
          changed = true
        }
      }
    }
    // 推进游标：以「实际音频」为真值。
    //
    // 以前只用 end_sec（可能是自动铺排时用**字数估算**写进去的，例：段0 估 2.3s 而实际 2.92s），
    // 结果下一段起点落在 2.3s → 段0 还没播完段1 就开始（重升）→ 成片听起来像被截断。
    // 手动段也要按“实际音频更长”推游标，否则手动留下的估算 end_sec 会永久制造重升。
    const at = Math.max(0, l.at_sec ?? 0)
    const end = Number(l.end_sec ?? 0)
    const natural = ms && ms > 0 ? at + ms / 1000 : 0
    const next = Math.max(at, end, natural)
    if (next > at) prevEnd = next
  })
  return changed
}

/**
 * P13：「按实际重排本镜」——忽略 manual / 旧的估算 end_sec，
 * 用**配音实际时长**逐段无重升地重新铺排（修历史上被估算写坏的时间轴）。
 *
 * @returns 本镜是否真的改动了
 */
export function relayoutShotByActual(shot: DirectorShot, durations: Record<string, number>): boolean {
  const lines = shot.narrations ?? []
  if (!lines.length) return false
  let cursor = 0
  let changed = false
  lines.forEach((l, i) => {
    const ms = durations[`${shot.shot_no}:${i}`]
    const actual = ms && ms > 0 ? ms / 1000 : null
    const at = Math.round(cursor * 10) / 10
    if ((l.at_sec ?? 0) !== at) {
      l.at_sec = at
      changed = true
    }
    // 用实际时长作本段的结束点；没有实际音频的段保留估算长度（不写 end_sec，留给下次）
    if (actual != null) {
      const end = Math.round((at + actual) * 10) / 10
      if (Number(l.end_sec ?? 0) !== end) {
        l.end_sec = end
        changed = true
      }
      cursor = at + actual + 0.1
    } else {
      const est = Number(l.end_sec ?? 0) > at ? Number(l.end_sec) - at : 0
      cursor = at + (est > 0 ? est : 0) + 0.1
    }
    if (l.manual === true) {
      l.manual = false // 交给自动铺排，避免下次又被跳过
      changed = true
    }
  })
  return changed
}

/** §10.2 方案类型守卫与编辑辅助（key 与后端/LLM 的 snake_case 一致）。 */

/**
 * P8：该镜是否有可配音文本。
 *
 * 优先看多段 narrations（后端也以它为准），兼容旧的单段 narration —— 两边判断必须一致，
 * 否则会出现「有文本但试听按钮不出现」或反之。
 */
export function shotHasText(s: DirectorShot): boolean {
  if ((s.narrations ?? []).some((l) => (l.text ?? '').trim().length > 0)) return true
  return (s.narration ?? '').trim().length > 0
}

export function isImagePlan(p: DirectorPlan | null | undefined): p is ImagePlan {
  return !!p && p.mode === 'image'
}

export function isVideoPlan(p: DirectorPlan | null | undefined): p is VideoPlan {
  return !!p && p.mode === 'video'
}

export function clonePlan(p: DirectorPlan): DirectorPlan {
  // JSON 往返：既能剥离 vue reactive proxy（structuredClone 会对其抛 DataCloneError），也保持纯数据
  return JSON.parse(JSON.stringify(p)) as DirectorPlan
}

/** 从后端拉到的方案可能缺字段（如 prompt_zh）——补齐编辑器可安全绑定的默认结构。 */
export function normalizePlan(raw: DirectorPlan): DirectorPlan {
  const p = JSON.parse(JSON.stringify(raw)) as Record<string, unknown>
  if (!p.mode) p.mode = 'image'
  p.title = str(p.title)
  p.logline = str(p.logline)
  if (p.mode === 'video') {
    const v = p as unknown as VideoPlan
    v.duration_sec = num(v.duration_sec, 0)
    v.aspect_ratio = str(v.aspect_ratio, '16:9')
    v.script = v.script ?? { theme: '', acts: [] }
    v.audio = v.audio ?? { music_mood: '', sfx: [], vo: '' }
    v.edit_plan = v.edit_plan ?? { fps: 30, transition_default: 'cut', subtitle: true }
    v.shots = Array.isArray(v.shots) ? v.shots : []
    v.shots = v.shots.map((s) => {
      const sh = s as unknown as Record<string, unknown>
      // P2 关键帧归一化（保留合法帧；非法丢弃）
      const kfs = Array.isArray(sh.keyframes)
        ? (sh.keyframes as unknown[])
            .filter((k): k is Record<string, unknown> => !!k && typeof k === 'object')
            .map((k) => ({
              label: str(k.label),
              ...(typeof k.t === 'number' || typeof k.t === 'string' ? { t: k.t } : {}),
              ...(k.shot_size === undefined ? {} : { shot_size: str(k.shot_size) }),
              ...(k.camera_move === undefined ? {} : { camera_move: str(k.camera_move) }),
              composition: str(k.composition),
              positive_prompt: str(k.positive_prompt),
            }))
        : []
      return {
        ...sh, // 保留 narration/zh/en_synced 等扩展字段，避免保存时丢失（原实现会剥离）
        shot_no: num(sh.shot_no, 0),
        duration_sec: num(sh.duration_sec, 1),
        shot_size: str(sh.shot_size, 'wide'),
        camera_move: str(sh.camera_move, ''),
        action: str(sh.action, ''),
        positive_prompt: str(sh.positive_prompt),
        negative_prompt: str(sh.negative_prompt),
        seed_lock: typeof sh.seed_lock === 'boolean' ? sh.seed_lock : true,
        ref_shot_no: sh.ref_shot_no == null ? null : num(sh.ref_shot_no, null),
        ...(kfs.length ? { keyframes: kfs } : {}),
      } as unknown as DirectorShot
    })
    return v
  }
  const img = p as unknown as ImagePlan
  img.prompt_zh = str(img.prompt_zh)
  img.positive_prompt = str(img.positive_prompt)
  img.negative_prompt = str(img.negative_prompt)
  img.camera = {
    ...((img.camera as unknown as Record<string, unknown>) ?? {}),
    focal_mm: num((img.camera as Record<string, unknown> | undefined)?.focal_mm, 35),
    shot_size: str((img.camera as Record<string, unknown> | undefined)?.shot_size, 'wide'),
    angle: str((img.camera as Record<string, unknown> | undefined)?.angle, 'low'),
  }
  img.lighting = str(img.lighting)
  img.palette = Array.isArray(img.palette) ? (img.palette as string[]).filter((x): x is string => typeof x === 'string') : []
  const pr = (img.params as Record<string, unknown> | undefined) ?? {}
  img.params = {
    width: num(pr.width, 1344),
    height: num(pr.height, 768),
    steps: num(pr.steps, 30),
    cfg: num(pr.cfg, 5.5),
    sampler: str(pr.sampler, 'dpmpp_2m_karras'),
    seed: pr.seed == null ? null : num(pr.seed, null),
  }
  return img
}

/** 客户端预检（与后端 §10.3 一致的精简版）：返回问题清单，空=可提交确认。 */
export function planProblems(p: DirectorPlan): string[] {
  const out: string[] = []
  if (!p.title || !p.title.trim()) out.push('缺少标题')
  if (isImagePlan(p)) {
    const pos = p.positive_prompt?.trim() ?? ''
    if (pos.length < 20 || pos.length > 1200) out.push(`正向提示词长度需 20–1200（当前 ${pos.length}）`)
    if (!p.negative_prompt?.trim()) out.push('缺少负向提示词')
  } else if (isVideoPlan(p)) {
    const shots = p.shots ?? []
    if (!shots.length) {
      out.push('镜头列表为空')
      return out
    }
    let sum = 0
    shots.forEach((s, i) => {
      const pos = s.positive_prompt?.trim() ?? ''
      if (pos.length < 20 || pos.length > 1200) out.push(`第 ${i + 1} 镜正向词长度需 20–1200（当前 ${pos.length}）`)
      const kfs = Array.isArray(s.keyframes) ? s.keyframes : []
      if (kfs.length === 1) out.push(`第 ${i + 1} 镜关键帧至少需 2 帧（当前 1）`)
      kfs.forEach((kf, j) => {
        const kp = kf.positive_prompt?.trim() ?? ''
        if (kp.length < 20 || kp.length > 1200) {
          out.push(`第 ${i + 1} 镜关键帧 #${j + 1} 正向词长度需 20–1200（当前 ${kp.length}）`)
        }
      })
      if (!s.negative_prompt?.trim()) out.push(`第 ${i + 1} 镜缺少负向提示词`)
      sum += Number(s.duration_sec) || 0
    })
    const target = Number(p.duration_sec) || 0
    if (Math.abs(sum - target) > 0.5) out.push(`镜头时长总和 ${round2(sum)}s ≠ 目标 ${round2(target)}s`)
  }
  return out
}

export const SOURCE_LABEL: Record<string, string> = {
  llm: 'LLM',
  stub: '示例(未接 LLM)',
  user: '手改',
}

export function round2(n: number): number {
  return Math.round(n * 100) / 100
}

function str(v: unknown, fallback = ''): string {
  return typeof v === 'string' ? v : fallback
}

function num(v: unknown, fallback: number | null): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : (fallback ?? 0)
}

/* ------------------------------------------------------------------------- *
 * P13 配音优先的时长校准（audio-first timing）
 *
 * 背景：镜头时长原来由 AI 一次写死，配音只能硬塞 → 截断/脱节。
 * 现在反过来：**用配音的实际时长反推镜头该多长**；当所需时长超过
 * 「视频模型单次输出上限」时，把镜头切成多段（分段生成、成片 cut 拼接）。
 * 方案文档：docs/audio-first-timing.md
 * ------------------------------------------------------------------------- */

/** 字数 → 秒（与 NarrationTimeline 同一口径：4.5 字/秒） */
function estSecOf(text: string): number {
  const t = (text ?? '').trim()
  return t ? Math.max(1, t.length / 4.5) : 0
}

/** 视频模型单次输出上限（秒）：项目级设定优先；未设按 5s（当前 i2v 模型上限） */
export function modelClipCap(plan: { edit_plan?: { video_model_max_sec?: number } }): number {
  const v = Number(plan?.edit_plan?.video_model_max_sec ?? 0)
  return v > 0 ? v : 5
}

/**
 * 镜头时长的**决定权**（timing_mode）：
 *
 * - `shot_fixed`（**默认**）：镜长 = min(模型单次上限, 目标镜长)。**配音顺排、允许溢出到下一镜**。
 *   每镜只调用云端 **1 次**、画面原速完整（不用本地拉伸/补帧），最适合旁白/叙事类。
 * - `audio_first`：镜长跟着配音走（配音多长，镜头就多长）；超过模型上限时按 oversize_policy
 *   处理（本地拉伸 / 配音溢出 / 切段）。适合对话、口型、以及"画面必须配合台词"的镜头。
 */
export type TimingMode = 'shot_fixed' | 'audio_first'

export function timingMode(plan: { edit_plan?: Record<string, unknown> }): TimingMode {
  const v = plan?.edit_plan?.timing_mode as string | undefined
  return v === 'audio_first' ? 'audio_first' : 'shot_fixed'
}

/**
 * 「配音比模型单次上限长」时的处理策略 —— 直接决定**云端调用次数（= 钱）**：
 *
 * - `stretch`（默认）：按模型上限生成 **1 次**，本地重定时拉伸到镜头需要的时间。**不多花钱**。
 * - `overflow`：按模型上限生成 1 次，画面到点就切，配音**溢出到下一镜**（旁白类可接受）。**不多花钱**。
 * - `segment`：切成 N 段分别生成 → **每多一段就多一次云端调用**（按次计费时最贵）。
 */
export type OversizePolicy = 'stretch' | 'overflow' | 'segment'

export function oversizePolicy(plan: { edit_plan?: Record<string, unknown> }): OversizePolicy {
  const v = plan?.edit_plan?.oversize_policy as string | undefined
  return v === 'overflow' || v === 'segment' ? v : 'stretch'
}

/** 镜头尾部呼吸余量（秒）：项目级设定优先，默认 0.3 */
export function planTailSec(plan: { edit_plan?: { tail_sec?: number } }): number {
  const v = Number(plan?.edit_plan?.tail_sec ?? NaN)
  return Number.isFinite(v) && v >= 0 ? v : 0.3
}

export interface ShotSegment {
  index: number
  start_sec: number
  duration_sec: number
}

export interface ShotTiming {
  /** 本镜需要多长（配音占用 + 余量） */
  need: number
  /** 单段素材不足、需要**本地拉伸**补齐（不额外调用云端） */
  stretch: boolean
  /** 分段（单段时长度为 1） */
  segs: ShotSegment[]
  /** 配音占用（不含余量） */
  voiceEnd: number
  /** 提示（空 = 无异常） */
  warn: string
}

/**
 * 计算某镜需要的时长与分段（**纯函数，不改动 plan**）。
 *
 * 规则（已确认的默认值）：
 * - 配音位置**不动**，只调镜头总长；`need = 配音结束 + tail`
 * - `need <= cap` → 单段
 * - `need > cap` → 切成 ceil(need/cap) 段，末段
 * - 末段短于 `minClip`（模型下限，默认 1s）→ **并入上一段**（不生成极短段）
 */
export function computeShotTiming(
  shot: {
    shot_no: number
    narrations?: Array<{ at_sec?: number | null; end_sec?: number | null; text?: string }> | null
    duration_sec?: number
  },
  durations: Record<string, number>,
  cap: number,
  tail: number,
  minClip = 1,
  policy: OversizePolicy = 'stretch',
): ShotTiming {
  const lines = shot.narrations ?? []
  let voiceEnd = 0
  lines.forEach((l, i) => {
    const at = Math.max(0, Number(l.at_sec ?? 0))
    const ms = durations[`${shot.shot_no}:${i}`]
    const actual = ms && ms > 0 ? ms / 1000 : null
    const end = Number(l.end_sec ?? 0)
    const len = actual ?? (end > at ? end - at : estSecOf(l.text ?? ''))
    voiceEnd = Math.max(voiceEnd, at + len)
  })
  const r1 = (n: number) => Math.round(n * 10) / 10
  const need = r1(Math.max(minClip, voiceEnd > 0 ? voiceEnd + tail : Number(shot.duration_sec ?? 0) || minClip))

  let segs: ShotSegment[] = []
  let warn = ''
  let stretch = false
  if (need <= cap) {
    segs = [{ index: 0, start_sec: 0, duration_sec: need }]
  } else if (policy === 'stretch') {
    // 按模型上限出 1 次 → 渲染时本地拉伸到 need：**不增加云端调用**
    segs = [{ index: 0, start_sec: 0, duration_sec: cap }]
    stretch = true
    warn = `需要 ${need}s，超过模型单次上限 ${cap}s → 按上限生成 1 次后**本地拉伸**到 ${need}s（不额外消耗云端调用）`
  } else if (policy === 'overflow') {
    segs = [{ index: 0, start_sec: 0, duration_sec: cap }]
    warn = `需要 ${need}s，超过模型上限 ${cap}s → 画面按 ${cap}s，配音溢出到下一镜（不额外消耗云端调用）`
  } else {
    const n = Math.ceil(need / cap)
    // **平均切分**（而不是先填满第一段、剩下丢给末段）：
    // 例 5.2s / 上限 4.0 → 2.6 + 2.6（而非 4.0 + 1.2）；短尾段帧数可能低于模型下限、观感也跳
    const each = Math.round((need / n) * 10) / 10
    let cursor = 0
    for (let i = 0; i < n; i++) {
      const rest = r1(need - cursor)
      const d = i === n - 1 ? rest : Math.min(r1(each), rest)
      segs.push({ index: i, start_sec: r1(cursor), duration_sec: d })
      cursor = r1(cursor + d)
    }
    // 末段过短 → 并入上一段（避免低于模型最小帧数的碎片段）
    if (segs.length > 1 && segs[segs.length - 1].duration_sec < minClip) {
      const last = segs.pop() as ShotSegment
      const prev = segs[segs.length - 1]
      prev.duration_sec = r1(prev.duration_sec + last.duration_sec)
    }
    warn = `需要 ${need}s，超过模型单次上限 ${cap}s → 切成 ${segs.length} 段生成`
      + `（同镜内 cut 拼接，**将多消耗 ${segs.length - 1} 次云端调用**）`
  }
  return { need, segs, stretch, voiceEnd: r1(voiceEnd), warn }
}

/** 某镜当前是否已按配音校准过（时长与分段一致） */
export function shotTimingAligned(
  shot: { shot_no: number; duration_sec?: number; segments?: ShotSegment[] | null },
  t: ShotTiming,
): boolean {
  const cur = Number(shot.duration_sec ?? 0)
  const segs = shot.segments ?? []
  if (Math.abs(cur - t.need) > 0.05) return false
  if ((shot as { stretch?: boolean }).stretch !== t.stretch) return false
  if (segs.length !== t.segs.length) return false
  return segs.every((s, i) => Math.abs(Number(s.duration_sec) - t.segs[i].duration_sec) < 0.05)
}

export interface CalibrateDiff {
  shotNo: number
  from: number
  to: number
  segCount: number
  warn: string
}

/**
 * 全片按配音校准：把每个镜头的 `duration_sec` 改为「配音占用 + 余量」，并写入 `segments`。
 *
 * @param apply true = 直接写回 plan（就地保存由调用方负责）；false = 只算差异（预览）
 */
export function calibrateAllShots(
  plan: {
    edit_plan?: { video_model_max_sec?: number; tail_sec?: number; oversize_policy?: string; fps?: number }
    shots?: Array<{
      shot_no: number
      duration_sec?: number
      narrations?: Array<{ at_sec?: number | null; end_sec?: number | null; text?: string }> | null
      segments?: ShotSegment[] | null
    }>
  },
  durations: Record<string, number>,
  apply: boolean,
): CalibrateDiff[] {
  const cap = modelClipCap(plan)
  const tail = planTailSec(plan)
  // 段长下限 = 模型最小帧数 / fps（默认 32 帧），避免生成出低于下限的碎片段
  const fps = Math.max(1, Number((plan as { edit_plan?: { fps?: number } }).edit_plan?.fps ?? 30))
  const minSeg = Math.max(0.5, 32 / fps)
  const policy = oversizePolicy(plan)
  const mode = timingMode(plan)
  const out: CalibrateDiff[] = []
  for (const s of plan.shots ?? []) {
    const from = Number(s.duration_sec ?? 0)
    if (mode === 'shot_fixed') {
      // 配音顺排：镜长 = min(模型上限, 目标镜长)；配音位置一律不动（允许溢出到下一镜）
      const shot = s as { target_sec?: number | null }
      const target = Number(shot.target_sec ?? from ?? 0) || cap
      const dur = Math.max(minSeg, Math.min(cap, Math.round(target * 10) / 10))
      if (Math.abs(dur - from) > 0.05 || !shot.target_sec) {
        const note =
          target > cap + 0.05
            ? `目标 ${target}s 超过模型上限 ${cap}s → 镜长取 ${dur}s，配音顺排溢出到下一镜（仍是 1 次调用）`
            : ''
        out.push({ shotNo: s.shot_no, from, to: dur, segCount: 1, warn: note })
        if (apply) {
          shot.target_sec = Math.round(target * 10) / 10
          s.duration_sec = dur
          s.segments = [{ index: 0, start_sec: 0, duration_sec: dur }]
          ;(s as { stretch?: boolean | null }).stretch = false
        }
      }
      continue
    }
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)
    const changed = Math.abs(from - t.need) > 0.05 || !shotTimingAligned(s, t)
    if (changed) {
      out.push({ shotNo: s.shot_no, from, to: t.need, segCount: t.segs.length, warn: t.warn })
      if (apply) {
        // overflow 策略：画面按模型上限（配音溢出到下一镜），其余策略画面覆盖整段
        s.duration_sec = policy === 'overflow' ? t.segs[0].duration_sec : t.need
        s.segments = t.segs
        ;(s as { stretch?: boolean | null }).stretch = t.stretch
      }
    }
  }
  return out
}

/** 音画体检：渲染前检查配音与镜头、分段是否协调（返回人类可读的问题列表） */
export function audioVideoHealthCheck(
  plan: {
    edit_plan?: { video_model_max_sec?: number; tail_sec?: number; oversize_policy?: string; fps?: number }
    shots?: Array<{
      shot_no: number
      duration_sec?: number
      narrations?: Array<{ at_sec?: number | null; end_sec?: number | null; text?: string }> | null
      segments?: ShotSegment[] | null
    }>
  },
  durations: Record<string, number>,
): string[] {
  const cap = modelClipCap(plan)
  const tail = planTailSec(plan)
  const fps = Math.max(1, Number((plan as { edit_plan?: { fps?: number } }).edit_plan?.fps ?? 30))
  const minSeg = Math.max(0.5, 32 / fps)
  const policy = oversizePolicy(plan)
  const mode = timingMode(plan)
  const issues: string[] = []
  if (mode === 'shot_fixed') {
    // 配音顺排模式：配音超出镜长是**预期行为**（会延续到下一镜），只检查真正的风险：
    //   ① 单镜配音远短于镜长（画面空等）② 全片音频总长超出画面总长（片尾会被切）
    let videoTotal = 0
    let audioEnd = 0
    for (const s of plan.shots ?? []) {
      const d = Number(s.duration_sec ?? 0)
      const lines = s.narrations ?? []
      let last = 0
      lines.forEach((l, i) => {
        const at = Math.max(0, Number(l.at_sec ?? 0))
        const ms = durations[`${s.shot_no}:${i}`]
        const actual = ms && ms > 0 ? ms / 1000 : null
        const end = Number(l.end_sec ?? 0)
        const len = actual ?? (end > at ? end - at : Math.max(0, (l.text ?? '').trim().length / 4.5))
        last = Math.max(last, at + len)
      })
      if (lines.length && last > 0 && last < d - 1.5) {
        issues.push(`第 ${s.shot_no} 镜：镜头 ${d}s，配音只到 ${last.toFixed(1)}s（画面会空等）`)
      }
      videoTotal += d
      audioEnd = Math.max(audioEnd, videoTotal - d + last)
    }
    if (audioEnd > videoTotal + 0.05) {
      issues.push(
        `全片配音到 ${audioEnd.toFixed(1)}s，画面只到 ${videoTotal.toFixed(1)}s`
        + `（片尾会多出 ${(audioEnd - videoTotal).toFixed(1)}s；渲染会自动补尾画面）`,
      )
    }
    return issues
  }
  for (const s of plan.shots ?? []) {
    const t = computeShotTiming(s, durations, cap, tail, minSeg, policy)
    const dur = Number(s.duration_sec ?? 0)
    if (t.voiceEnd > 0 && dur + 0.05 < t.voiceEnd) {
      issues.push(`第 ${s.shot_no} 镜：配音 ${t.voiceEnd}s 超出镜头 ${dur}s（会被截断）`)
    } else if (t.voiceEnd > 0 && dur > t.voiceEnd + 1.0) {
      issues.push(`第 ${s.shot_no} 镜：镜头 ${dur}s 比配音长 ${(dur - t.voiceEnd).toFixed(1)}s（画面空等）`)
    }
    if (dur > cap && !(s.segments ?? []).length) {
      issues.push(`第 ${s.shot_no} 镜：${dur}s 超过模型上限 ${cap}s，但未分段（生成会失败/被截）`)
    } else if (dur > cap + 0.05 && (s.segments ?? []).length === 1
        && policy === 'stretch' && (s as { stretch?: boolean }).stretch !== true) {
      issues.push(`第 ${s.shot_no} 镜：${dur}s 超过模型上限但未标记「本地拉伸」（画面会比配音短）`)
    }
    const segs = s.segments ?? []
    if (segs.length > 1) {
      const sum = segs.reduce((a, b) => a + Number(b.duration_sec), 0)
      if (Math.abs(sum - dur) > 0.15) {
        issues.push(`第 ${s.shot_no} 镜：分段合计 ${sum.toFixed(1)}s 与镜头 ${dur}s 不一致`)
      }
    }
  }
  return issues
}
