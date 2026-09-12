#!/usr/bin/env python3
"""P13 前端：参考图面板升级（勾选框 + 剧情主体列表 + 定妆图 + 一键生成主体）。幂等。"""
import io

# ---------------- api ----------------
p = "web/src/api/director.ts"
s = io.open(p, encoding="utf-8").read()
if "extractSubjects" not in s:
    s += '''
/** P13：一键抽取剧情主体（人物/载具/物件/场景）；已有主体保留、只补新的 */
export async function extractSubjects(
  workspaceId: string,
  projectId: string,
  revisionId: string,
): Promise<{ source: string; added: string[]; subjects: Array<{ name: string; kind: string; aliases: string[] }> }> {
  return request(`/api/v1/projects/${projectId}/revisions/${revisionId}/subjects/extract`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
  })
}
'''
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("api ok")

# ---------------- types ----------------
p = "web/src/api/types.ts"
s = io.open(p, encoding="utf-8").read()
if "PlanSubject" not in s:
    s = s.replace("export interface DirectorShot {", '''/** P13 剧情主体（参考图升级：勾选=参与锚定；定妆图=一致性锚定图） */
export interface PlanSubjectRef {
  assetId: string
  /** false = 不作为参考（替代原来的“删除才取消”） */
  checked?: boolean
  region?: { x: number; y: number; w: number; h: number } | null
}

export interface PlanSubject {
  name: string
  kind?: 'person' | 'vehicle' | 'object' | 'scene'
  aliases?: string[]
  enabled?: boolean
  locked?: boolean
  refs?: PlanSubjectRef[]
  /** 定妆图（由素材图生成，优先用于分镜锚定） */
  portraitAssetId?: string
  portraitVersion?: number
}

export interface DirectorShot {''', 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("types ok")

# ---------------- view ----------------
p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "syncSubjectsFromRefs" not in s:
    # 1) 引入 API + 类型
    s = s.replace("import { createBrief, listBriefs } from '@/api/briefs'",
                  "import { createBrief, listBriefs } from '@/api/briefs'", 1)
    s = s.replace("import { aiGenerateLines, aiGenerateMusic } from '@/api/director'",
                  "import { aiGenerateLines, aiGenerateMusic, extractSubjects } from '@/api/director'", 1)

    # 2) 状态：未参与集合 + 主体列表 + 定妆图忙碌
    s = s.replace("const refSelected = ref<string[]>([])",
                  '''const refSelected = ref<string[]>([])
/** P13：未勾选 = 不作为参考（默认全部参与） */
const refUnchecked = ref<string[]>([])
const subjectBusy = ref('')
const portraitBusy = ref(false)''', 1)

    # 3) 主体工具函数（放在 buildRefAssets 之前）
    anchor = "function buildRefAssets(): Array<{"
    assert s.count(anchor) == 1
    helper = '''/** P13：当前方案里的剧情主体（含定妆图状态） */
function planSubjects(): PlanSubject[] {
  const plan = draft.value
  if (!plan) return []
  const subs = (plan as unknown as { subjects?: PlanSubject[] }).subjects
  return Array.isArray(subs) ? subs : []
}
/** 写回 plan.subjects（就地改 draft，触发脏标记） */
function setPlanSubjects(subs: PlanSubject[]): void {
  const plan = draft.value
  if (!plan) return
  ;(plan as unknown as { subjects?: PlanSubject[] }).subjects = subs
  syncReferenceAssets()
}
function subjectOf(id: string): string {
  return (refSubjects.value[id] ?? '').trim()
}
/** 主体 → 该主体的定妆图资产（同主体多版时取最新） */
function portraitsOf(name: string): Array<{ id: string; url?: string; width?: number | null; height?: number | null }> {
  return (assets.data.value ?? [])
    .filter((a) => a.kind === 'portrait' && (a.promptSnapshot as Record<string, unknown> | undefined)?.subject === name)
    .map((a) => ({ id: a.id, url: galUrls.value[a.id], width: a.width, height: a.height }))
}
/** 一键生成主体（LLM 抽取；已有主体保留，只补新的） */
async function onExtractSubjects(): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  subjectBusy.value = 'extract'
  try {
    const r = await extractSubjects(workspaceId.value, projectId.value, revId)
    const subs = planSubjects()
    const byName = new Map(subs.map((x) => [x.name, x]))
    for (const p of r.subjects ?? []) {
      const old = byName.get(p.name)
      if (old) {
        // 已存在：只补别名，不动勾选/定妆图
        const aliases = Array.from(new Set([...(old.aliases ?? []), ...(p.aliases ?? [])]))
        byName.set(p.name, { ...old, aliases })
      } else {
        byName.set(p.name, { name: p.name, kind: (p.kind as PlanSubject['kind']) ?? 'person', aliases: p.aliases ?? [], enabled: true, locked: false, refs: [], portraitAssetId: '', portraitVersion: 0 })
      }
    }
    setPlanSubjects([...byName.values()])
    message.success(r.added?.length ? `新增主体：${r.added.join('、')}（记得确认方案）` : '没有新主体（已有列表已是最新）')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '抽取主体失败')
  } finally {
    subjectBusy.value = ''
  }
}
/** 主体参与锚定开关 */
function toggleSubject(name: string, on: boolean): void {
  setPlanSubjects(planSubjects().map((s) => (s.name === name ? { ...s, enabled: on } : s)))
}
/** 生成该主体的定妆图（kind=portrait） */
async function genPortrait(name: string): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await handleSave())) return
  portraitBusy.value = true
  try {
    const created = await createJobs(workspaceId.value, projectId.value, {
      revisionId: revId,
      kind: 'portrait',
      subject: name,
    } as never)
    const jobId = created[0]?.id
    if (!jobId) throw new Error('未创建定妆图任务')
    message.info(`「${name}」定妆图合成中…`)
    const job = await waitJobDone(jobId, 600000)
    if (job.state !== 'succeeded') throw new Error(job.errorMessage || `任务${job.state}`)
    await queryClient.invalidateQueries({ queryKey: ['assets'] })
    await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    await refreshGallery()
    message.success(`「${name}」定妆图已生成 —— 点「选图」把它设为该主体的锚定图`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成定妆图失败')
  } finally {
    portraitBusy.value = false
  }
}
/** 选图：把最新一版定妆图写回该主体（供分镜锚定） */
function pickPortrait(name: string): void {
  const list = portraitsOf(name)
  if (!list.length) {
    message.warning(`「${name}」还没有定妆图，先点「生成图像」`)
    return
  }
  const newest = list[0]
  setPlanSubjects(planSubjects().map((s) => (s.name === name
    ? { ...s, portraitAssetId: newest.id, portraitVersion: (s.portraitVersion ?? 0) + 1 }
    : s)))
  message.success(`「${name}」已锚定最新定妆图（v${(planSubjects().find((x) => x.name === name)?.portraitVersion ?? 1)}）—— 记得确认方案后再生成分镜，否则读的是旧稿`)
}

'''
    s = s.replace(anchor, helper + anchor, 1)

    # 4) buildRefAssets → 同时产出 subjects（按主体聚合，带 checked/portrait）
    s = s.replace('''function syncReferenceAssets(): void {
  if (!draft.value) return
  ;(draft.value as unknown as { referenceAssets?: unknown }).referenceAssets = buildRefAssets()
}''', '''function syncReferenceAssets(): void {
  if (!draft.value) return
  const refs = buildRefAssets()
  ;(draft.value as unknown as { referenceAssets?: unknown }).referenceAssets = refs
  // P13：按主体聚合写回 subjects[]（勾选状态与定妆图一起落库）
  const prev = planSubjects()
  const byName = new Map(prev.map((s) => [s.name, s]))
  const grouped = new Map<string, PlanSubjectRef[]>()
  for (const r of refs) {
    const key = (r.subject ?? '').trim() || '（未命名）'
    grouped.set(key, [...(grouped.get(key) ?? []), { assetId: r.assetId, checked: !refUnchecked.value.includes(r.assetId), region: r.region ?? null }])
  }
  const out: PlanSubject[] = []
  for (const [name, refsOf] of grouped) {
    const old = byName.get(name)
    out.push({ name, kind: old?.kind ?? 'person', aliases: old?.aliases ?? [], enabled: old?.enabled ?? true, locked: old?.locked ?? false, refs: refsOf, portraitAssetId: old?.portraitAssetId ?? '', portraitVersion: old?.portraitVersion ?? 0 })
    byName.delete(name)
  }
  // 没有素材图但有定妆图/别名的主体也要保留（否则一键抽取的结果会丢）
  for (const s of byName.values()) out.push({ ...s, refs: [] })
  ;(draft.value as unknown as { subjects?: PlanSubject[] }).subjects = out
}
/** 勾选/取消某张参考图参与锚定 */
function toggleRefChecked(id: string, on: boolean): void {
  refUnchecked.value = on ? refUnchecked.value.filter((x) => x !== id) : [...new Set([...refUnchecked.value, id])]
  syncReferenceAssets()
}''', 1)

    # 5) 清理：被删掉的图也要从未勾选集合里移除
    s = s.replace("function toggleRef(id: string, on: boolean): void {",
                  '''function pruneUnchecked(): void {
  refUnchecked.value = refUnchecked.value.filter((x) => refSelected.value.includes(x))
}
function toggleRef(id: string, on: boolean): void {''', 1)
    s = s.replace('''  } else {
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]''', '''  } else {
    refSelected.value = refSelected.value.filter((x) => x !== id)
    delete refSubjects.value[id]
    delete refRegions.value[id]
    pruneUnchecked()''', 1)

    # 6) imports：类型
    s = s.replace("import type { AssetRef, DirectorPlan, DirectorShot, JobRecord } from '@/api/types'",
                  "import type { AssetRef, DirectorPlan, DirectorShot, JobRecord, PlanSubject, PlanSubjectRef } from '@/api/types'", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("view script ok")
else:
    print("view already patched")
