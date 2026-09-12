#!/usr/bin/env python3
"""P13：①未确认横幅+一键确认 ②变更摘要 ③别名/勾选走元数据接口（不另存版本）。幂等。"""
import io

# ---------------- api ----------------
p = "web/src/api/director.ts"
s = io.open(p, encoding="utf-8").read()
if "patchSubjectMeta" not in s:
    s += '''
/** P13：只更新主体元数据（别名 / 参与勾选）—— 就地生效，不另存版本、不需重新确认 */
export async function patchSubjectMeta(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  subjects: Array<{ name: string; aliases?: string[]; enabled?: boolean }>,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}/subjects/meta`, {
    method: 'POST',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { subjects },
  })
}
'''
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("api ok")

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "confirmBanner" in s:
    print("already patched")
    raise SystemExit(0)

s = s.replace("import { aiGenerateLines, aiGenerateMusic, extractSubjects } from '@/api/director'",
              "import { aiGenerateLines, aiGenerateMusic, extractSubjects, patchSubjectMeta } from '@/api/director'", 1)

# ---------- ③ 元数据保存（就地，不另存版本） ----------
s = s.replace("""/** 主体参与锚定开关 */
function toggleSubject(name: string, on: boolean): void {
  setPlanSubjects(planSubjects().map((s) => (s.name === name ? { ...s, enabled: on } : s)))
}""",
              """/**
 * P13：主体元数据（别名 / 参与勾选）**就地保存**：直接改当前版本，不另存 vN+1、不需要重新确认。
 * 只提交这两类字段，后端也只改这两类（不影响 prompt/分镜/台词等生成相关字段）。
 */
async function saveSubjectMeta(): Promise<void> {
  const revId = selectableRevId()
  const subs = planSubjects()
  if (!revId || !subs.length) return
  try {
    await patchSubjectMeta(workspaceId.value, projectId.value, revId,
      subs.map((x) => ({ name: x.name, aliases: x.aliases ?? [], enabled: x.enabled !== false })))
    // 元数据已落库 → 不置脏（避免又要求“保存 + 确认”）
    metaSyncedAt.value = Date.now()
    message.info('主体别名/勾选已就地保存（无需重新确认）')
  } catch (e) {
    message.warning(e instanceof Error ? e.message : '元数据保存失败（不影响其它改动）')
  }
}

/** 主体参与锚定开关 */
function toggleSubject(name: string, on: boolean): void {
  setPlanSubjects(planSubjects().map((s) => (s.name === name ? { ...s, enabled: on } : s)))
  void saveSubjectMeta()
}""", 1)

# 别名增删后同步元数据
s = s.replace("""  setPlanSubjects(subs)
  message.success(`已删除「${name}」的别名「${alias}」`)""",
              """  setPlanSubjects(subs)
  void saveSubjectMeta()
  message.success(`已删除「${name}」的别名「${alias}」（已就地保存，无需确认）`)""", 1)
s = s.replace("""  setPlanSubjects(subs)
  aliasDraft.value = ''
  message.success(`已为「${name}」添加别名「${v}」`)""",
              """  setPlanSubjects(subs)
  void saveSubjectMeta()
  aliasDraft.value = ''
  message.success(`已为「${name}」添加别名「${v}」（已就地保存，无需确认）`)""", 1)

# ---------- ① 未确认横幅 + 一键确认；② 变更摘要 ----------
s = s.replace("""const shotTotal = computed(() => {""",
              """/** P13：是否需要「确认」（当前是未确认版本，或草稿有未保存的改动） */
const confirmBanner = computed(() => !detApproved.value || dirty.value)
const metaSyncedAt = ref(0)

/** P13：变更摘要 —— 与已确认稿对比，列出改了什么（让确认变成“看一眼差异”） */
const changeSummary = computed<string[]>(() => {
  const cur = draft.value
  const app = approvedPlan.value
  if (!cur) return []
  if (!app) return ['首次确认']
  const out: string[] = []
  if (!isVideoPlan(cur)) return ['图片方案有改动']
  const ac = app as unknown as Record<string, unknown>
  const cc = cur as unknown as Record<string, unknown>
  const themeA = (app.script?.theme ?? '') as string
  const themeC = (cur.script?.theme ?? '') as string
  if (themeA !== themeC) out.push('主题')
  const shotsA = (app.shots ?? []) as Array<Record<string, unknown>>
  const shotsC = (cur.shots ?? []) as Array<Record<string, unknown>>
  let changedShots = 0
  let changedLines = 0
  const byNo = new Map(shotsA.map((x) => [Number(x.shot_no), x]))
  for (const cs of shotsC) {
    const as = byNo.get(Number(cs.shot_no))
    if (!as || JSON.stringify(as) !== JSON.stringify(cs)) changedShots++
    const la = JSON.stringify(as?.narrations ?? [])
    const lc = JSON.stringify(cs.narrations ?? [])
    if (la !== lc) changedLines++
  }
  if (shotsC.length !== shotsA.length) out.push(`分镜数量 ${shotsA.length}→${shotsC.length}`)
  if (changedShots) out.push(`分镜 ${changedShots} 镜有改动`)
  if (changedLines) out.push(`台词/旁白 ${changedLines} 镜有改动`)
  const vbA = JSON.stringify((ac.audio as { voiceBindings?: unknown } | undefined)?.voiceBindings ?? [])
  const vbC = JSON.stringify((cc.audio as { voiceBindings?: unknown } | undefined)?.voiceBindings ?? [])
  if (vbA !== vbC) out.push('角色音色绑定')
  const vA = JSON.stringify((ac.audio as { voice?: unknown } | undefined)?.voice ?? '')
  const vC = JSON.stringify((cc.audio as { voice?: unknown } | undefined)?.voice ?? '')
  if (vA !== vC) out.push('默认音色')
  const mA = JSON.stringify((ac.audio as { music?: unknown } | undefined)?.music ?? (ac.audio as { music_mood?: unknown } | undefined)?.music_mood ?? '')
  const mC = JSON.stringify((cc.audio as { music?: unknown } | undefined)?.music ?? (cc.audio as { music_mood?: unknown } | undefined)?.music_mood ?? '')
  if (mA !== mC) out.push('配乐')
  const rA = JSON.stringify(ac.referenceAssets ?? [])
  const rC = JSON.stringify(cc.referenceAssets ?? [])
  if (rA !== rC) out.push('参考图/区域')
  return out.length ? out : ['（仅有不影响生成的元数据变化）']
})

const shotTotal = computed(() => {""", 1)

# 横幅：放在「中：方案编辑区」上方
s = s.replace("""        <!-- 中：方案编辑区 -->""",
              """        <!-- P13：未确认横幅（一键确认 + 变更摘要） -->
        <div v-if="confirmBanner" class="confirm-banner" data-testid="confirm-banner">
          <span class="cb-title font-mono">
            {{ detApproved ? '草稿有未保存改动' : `当前是未确认版本 v${selectedRev?.revisionNo ?? '?'}` }}
          </span>
          <span class="cb-diff text-secondary">
            变更：{{ changeSummary.join('、') }}
          </span>
          <span class="cb-ops">
            <NButton v-if="dirty" size="tiny" secondary data-testid="cb-save" @click="handleSave">保存</NButton>
            <NButton size="tiny" type="primary" data-testid="cb-approve" @click="handleApprove">确认并使用此版本</NButton>
          </span>
        </div>

        <!-- 中：方案编辑区 -->""", 1)

# 样式
s = s.replace("<style scoped>", """<style scoped>
.confirm-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 12px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--wv-accent) 55%, var(--wv-line));
  background: color-mix(in srgb, var(--wv-accent) 10%, transparent);
  border-radius: 10px;
  font-size: 12.5px;
}
.cb-title { color: var(--wv-accent-text); }
.cb-diff { flex: 1 1 auto; }
.cb-ops { display: inline-flex; gap: 6px; }
""", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("view patched")
