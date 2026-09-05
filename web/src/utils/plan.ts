import type { DirectorPlan, ImagePlan, VideoPlan } from '@/api/types'

/** §10.2 方案类型守卫与编辑辅助（key 与后端/LLM 的 snake_case 一致）。 */

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
      return {
        shot_no: num(sh.shot_no, 0),
        duration_sec: num(sh.duration_sec, 1),
        shot_size: str(sh.shot_size, 'wide'),
        camera_move: str(sh.camera_move, ''),
        action: str(sh.action, ''),
        positive_prompt: str(sh.positive_prompt),
        negative_prompt: str(sh.negative_prompt),
        seed_lock: typeof sh.seed_lock === 'boolean' ? sh.seed_lock : true,
        ref_shot_no: sh.ref_shot_no == null ? null : num(sh.ref_shot_no, null),
      }
    })
    return v
  }
  const img = p as unknown as ImagePlan
  img.prompt_zh = str(img.prompt_zh)
  img.positive_prompt = str(img.positive_prompt)
  img.negative_prompt = str(img.negative_prompt)
  img.camera = {
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
