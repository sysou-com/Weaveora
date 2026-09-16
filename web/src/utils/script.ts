import type { ScriptFieldKey } from '@/api/types'

/**
 * 剧本要素字段元数据（单一真源：key / 中文名 / 字段说明 / 输入上限）。
 *
 * 字段说明与后端 `ScriptField`（Prompt 用）逐字一致 —— 括注原文即用户要求写进界面的说明。
 * 上限 8000：用户原始要求「除标题与类型外其它字段限制不要小于 4000 字」，Q2 确认为**输入上限**，
 * 取 8000 留出 AI 生成（要求 ≥4000 字）后的手工修改余量。
 */
export interface ScriptFieldMeta {
  key: ScriptFieldKey
  label: string
  help: string
  placeholder: string
}

export const SCRIPT_FIELD_MAX = 8000

/** AI 生成长文的**目标字数**区间（用户 2026-09-17：弹窗里设定，上限 8000） */
export const SCRIPT_TARGET_MIN = 500
export const SCRIPT_TARGET_DEFAULT = 4000
const TARGET_STORAGE_KEY = 'weaveora:script:fieldTarget'

/** 记住上次设定的目标字数（跨字段/跨会话），避免每次都调 */
export function rememberedFieldTarget(): number {
  const raw = window.localStorage.getItem(TARGET_STORAGE_KEY)
  const n = raw ? Number(raw) : NaN
  return clampTarget(Number.isFinite(n) ? n : SCRIPT_TARGET_DEFAULT)
}

export function rememberFieldTarget(n: number): void {
  window.localStorage.setItem(TARGET_STORAGE_KEY, String(clampTarget(n)))
}

export function clampTarget(n: number): number {
  const v = Math.round(Number(n))
  if (!Number.isFinite(v)) return SCRIPT_TARGET_DEFAULT
  return Math.min(SCRIPT_FIELD_MAX, Math.max(SCRIPT_TARGET_MIN, v))
}

export const SCRIPT_FIELDS: ScriptFieldMeta[] = [
  {
    key: 'characters',
    label: '剧本人物及人物介绍',
    help:
      '人物是剧本的灵魂，是推动剧情发展的主体。人物设计需要考虑性格特征、背景经历、动机和目标，以及与其他角色的关系。立体的人物形象能够引发观众共鸣，并通过人物的行动和选择推动故事发展。',
    placeholder: '例：林知远——32 岁，旧书店店主，外冷内热；因父亲失踪而执拗于旧报纸里的线索……',
  },
  {
    key: 'story',
    label: '剧本故事',
    help:
      '故事是剧本的核心内容，是剧本创作的基础。它包括事件的起因、发展、高潮和结局，同时需要具备完整性和逻辑性。故事的长度、节奏和题材会影响剧本的结构和表现形式，例如电影通常为 90 分钟，电视剧为 30 至 40 分钟，短片或广告则更短。',
    placeholder: '例：起因……发展……高潮……结局……（含主线与副线）',
  },
  {
    key: 'conflict',
    label: '剧本冲突',
    help:
      '冲突是推动剧情发展的动力源泉，也是展现人物性格和主题的重要手段。冲突可以表现为人与人之间的矛盾、人与环境的对抗或人物内心的挣扎。有效的冲突设计能够制造悬念和戏剧张力，使故事更具吸引力。',
    placeholder: '例：人与人的对抗（…）、人与环境（…）、人物内心（…）；每集冲突的升级曲线',
  },
  {
    key: 'plotStructure',
    label: '剧本情节结构',
    help:
      '剧本的结构通常包括开端、发展、转折、高潮和结局。开端引入故事和人物，发展部分展开情节，转折和高潮制造戏剧张力，结局解决冲突并给观众满意的回应。结构可以纵向发展（条式结构）或横向发展（团块结构），根据故事类型和创作风格灵活安排。',
    placeholder: '例：开端（第 1–2 集）… 发展（第 3–6 集）… 转折（第 7 集）… 高潮（第 8 集）… 结局（第 9 集）',
  },
  {
    key: 'language',
    label: '剧本语言',
    help:
      '语言是剧本的表达工具，包括对话、旁白和内心独白。剧本语言应简洁明了、富有个性和节奏感，能够准确传达人物的思想、情感和性格特点。戏曲或歌剧中，语言还可能以唱词形式呈现。',
    placeholder: '例：整体语感、各角色说话方式与口头禅、旁白人称与时态、唱词韵脚要求',
  },
  {
    key: 'stageDirections',
    label: '舞台说明',
    help:
      '舞台说明是剧本中对场景、时间、地点、环境、道具、人物动作与表情、灯光音响等的说明性文字，由剧作者提供，是导演、演员与舞台各工种再创作的依据。',
    placeholder: '例：【深夜 · 旧书店二楼】仅一盏台灯。雨声。他翻动报纸的手停住……',
  },
]

/** 剧本类型（Q8：建议 10 项 + 其他可自定义） */
export const SCRIPT_GENRES = [
  '电影',
  '电视剧',
  '短剧',
  '微电影',
  '舞台剧',
  '戏曲',
  '动画',
  '纪录片',
  '广告 / 宣传片',
  '其他',
]

/** 字段 key → 元数据 */
export function fieldMeta(key: ScriptFieldKey): ScriptFieldMeta {
  return SCRIPT_FIELDS.find((f) => f.key === key) as ScriptFieldMeta
}

/** 精简故事 / 变更记录里的时间展示 */
export function formatChars(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)} 万字`
  return `${n} 字`
}

/** 极简行级 diff（LCS 太长时退化为「按段落对比」），用于 Q3 的 AI 字段 diff 展示 */
export interface DiffLine {
  type: 'same' | 'add' | 'del'
  text: string
}

export function diffLines(before: string, after: string): DiffLine[] {
  const a = split(before)
  const b = split(after)
  const n = a.length
  const m = b.length
  // 段落数过大时退化为整体替换（避免 O(n*m) 卡顿）
  if (n * m > 40_000) {
    const out: DiffLine[] = []
    a.forEach((t) => out.push({ type: 'del', text: t }))
    b.forEach((t) => out.push({ type: 'add', text: t }))
    return out
  }
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: a[i++] })
    } else {
      out.push({ type: 'add', text: b[j++] })
    }
  }
  while (i < n) out.push({ type: 'del', text: a[i++] })
  while (j < m) out.push({ type: 'add', text: b[j++] })
  return out
}

function split(s: string): string[] {
  const t = (s ?? '').replace(/\r\n/g, '\n').trim()
  if (!t) return []
  // 先按空行分段；段落再按句号切行，diff 粒度更可用
  return t
    .split(/\n{2,}/)
    .flatMap((p) => p.split(/(?<=[。！？；])/))
    .map((x) => x.trim())
    .filter((x) => x.length > 0)
}
