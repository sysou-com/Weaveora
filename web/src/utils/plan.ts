import type { DirectorPlan, DirectorShot, ImagePlan, VideoPlan } from '@/api/types'

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
    const end = Number(l.end_sec ?? 0)
    if (end > (l.at_sec ?? 0)) prevEnd = end
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
    v.edit_plan = v.edit_plan ?? { fps: 30, transition_default: 'cut', subtitle: false }
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
