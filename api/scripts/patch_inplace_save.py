#!/usr/bin/env python3
"""P13：单条语音（重新生成/试听）改为「就地保存」—— 不另存版本、不弹确认。

用户真实痛点：逐条重生成配音时被“确认方案”卡住 —— 不确认就没法做下一条。
原因：这类操作原来 `if (dirty) handleSave()`，而在已确认稿上保存会**另存 vN+1** →
之后每次都处于「未确认」→ 每次都被拦。

就地保存的语义：直接把当前草稿写进**当前版本**（含 approved），不动版本号、不改确认态。
用于「逐条调音/试听」这类**正在迭代当前 take** 的操作；关键帧/整片配音/配乐/渲染/导出
仍走 ensureApprovedForGenerate（花钱多、影响成片）。
"""
import io

# ---------------- 后端：新增就地保存端点 ----------------
p = "api/src/main/java/studio/weaveora/director/DirectorService.java"
s = io.open(p, encoding="utf-8").read()
if "patchPlanInPlace" not in s:
    anchor = "    /**\n     * P13：只更新「主体元数据」"
    assert s.count(anchor) == 1
    block = '''    /**
     * P13：**就地保存方案**（不另存版本、不改确认态）——用于「逐条重生成/试听配音」这类
     * 正在迭代当前 take 的操作，避免每改一条就另存 vN+1 从而反复要求确认。
     *
     * <p>与 {@link #patchRevision} 的区别：后者在已确认稿上会 fork 新版本 + 需重新确认。
     */
    @Transactional
    public RevisionDetailResponse patchPlanInPlace(UUID userId, UUID workspaceId, UUID projectId,
                                                   UUID revisionId, com.fasterxml.jackson.databind.JsonNode plan) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        if (plan == null || !plan.isObject()) {
            throw new BizException(ErrorCode.VALIDATION, "方案内容为空");
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj = plan.deepCopy();
        String curMode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        enrich(obj, curMode, project.aspectRatio());
        validateOrThrow(obj, curMode, project.durationSec());
        r.replacePlan(obj);
        revisions.save(r);
        if ("video".equals(curMode)) {
            syncShots(r.id(), obj);
        }
        log.info("plan patched in place: project={} rev={}", projectId, revisionId);
        return toDetail(r, project.approvedRevisionId());
    }

'''
    s = s.replace(anchor, block + anchor, 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("DirectorService + patchPlanInPlace")
else:
    print("service already patched")

p = "api/src/main/java/studio/weaveora/director/api/DirectorController.java"
s = io.open(p, encoding="utf-8").read()
if "plan/inplace" not in s:
    s = s.replace('''    /** P13：只更新主体元数据（别名/勾选），就地生效、不另存版本。 */''',
                  '''    /** P13：就地保存方案（不另存版本、不改确认态）——供逐条重生成/试听配音使用。 */
    @PatchMapping("/revisions/{revisionId}/plan/inplace")
    public ResponseEntity<RevisionDetailResponse> patchPlanInPlace(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return ResponseEntity.ok(directorService.patchPlanInPlace(
                uid(request), ws(workspaceId), projectId, revisionId, body.path("plan")));
    }

    /** P13：只更新主体元数据（别名/勾选），就地生效、不另存版本。 */''', 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("Controller + plan/inplace")
else:
    print("controller already patched")

# ---------------- 前端 ----------------
p = "web/src/api/director.ts"
s = io.open(p, encoding="utf-8").read()
if "patchPlanInPlace" not in s:
    s += '''
/** P13：就地保存方案（不另存版本、不改确认态）—— 逐条重生成/试听配音用，避免反复要求确认 */
export async function patchPlanInPlace(
  workspaceId: string,
  projectId: string,
  revisionId: string,
  plan: DirectorPlan,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/api/v1/projects/${projectId}/revisions/${revisionId}/plan/inplace`, {
    method: 'PATCH',
    headers: { [WORKSPACE_HEADER]: workspaceId },
    body: { plan },
  })
}
'''
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("api patchPlanInPlace")

p = "web/src/views/ProjectDetailView.vue"
s = io.open(p, encoding="utf-8").read()
if "savePlanInPlace" not in s:
    s = s.replace("import { aiGenerateLines, aiGenerateMusic, extractSubjects, patchSubjectMeta } from '@/api/director'",
                  "import { aiGenerateLines, aiGenerateMusic, extractSubjects, patchPlanInPlace, patchSubjectMeta } from '@/api/director'", 1)
    s = s.replace("/* GEN_PREFLIGHT_DONE */", '''/**
 * P13：**就地保存**当前草稿（不动版本、不需确认）—— 供「逐条重生成/试听配音」使用。
 * 返回是否成功。
 */
async function savePlanInPlace(): Promise<boolean> {
  const revId = genRevisionId()
  const plan = draft.value
  if (!revId || !plan) return false
  try {
    await patchPlanInPlace(workspaceId.value, projectId.value, revId, plan)
    pristineJson.value = JSON.stringify(plan)   // 已落库 → 不再算脏
    dirty.value = false
    await queryClient.invalidateQueries({ queryKey: ['revision', workspaceId.value, projectId.value, revId] })
    return true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '就地保存失败')
    return false
  }
}

/* GEN_PREFLIGHT_DONE */''', 1)
    # 逐条重生成 / 逐条试听：改为就地保存，不弹确认
    s = s.replace("""async function genVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (!(await ensureApprovedForGenerate('重新生成配音'))) return     // 先落盘，否则服务端拿到的是旧段落""",
                  """async function genVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  // 逐条调音：就地保存即可（不另存版本、不弹确认），否则改一条就要确认一次，根本没法逐条做
  if (dirty.value && !(await savePlanInPlace())) return""", 1)
    s = s.replace("""async function previewVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (!(await ensureApprovedForGenerate('试听该条配音'))) return""",
                  """async function previewVoiceLine(shotNo: number, lineIndex: number): Promise<void> {
  const revId = genRevisionId()
  if (!revId) return
  if (dirty.value && !(await savePlanInPlace())) return""", 1)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    print("view patched")
else:
    print("view already patched")
