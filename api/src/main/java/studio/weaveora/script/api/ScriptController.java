package studio.weaveora.script.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.script.ScriptService;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「我的剧本」端点：/api/v1/scripts（对齐 /api/v1/projects 的命名与语义）。
 *
 * <p>工作区隔离：写操作必须带 {@code X-Workspace-Id}；剧本精选的 GET 允许游客（uidOrNull）。
 */
@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    // ------------------------------------------------------------ 剧本 CRUD

    @GetMapping
    public ResponseEntity<List<ScriptResponse>> list(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId) {
        return ResponseEntity.ok(scriptService.list(uid(request), ws(workspaceId)));
    }

    @PostMapping
    public ResponseEntity<ScriptResponse> create(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @Valid @RequestBody CreateScriptRequest req) {
        return ResponseEntity.ok(scriptService.create(uid(request), ws(workspaceId), req));
    }

    /** 我的剧本（分页：最近更新倒序，默认 8/页）。 */
    @GetMapping("/own")
    public ResponseEntity<ScriptPage> own(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(scriptService.ownPage(uid(request), ws(workspaceId), page, size));
    }

    /** 管理态批量删除（软删）。 */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Integer>> deleteBatch(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @Valid @RequestBody BatchDeleteScriptRequest req) {
        return ResponseEntity.ok(Map.of("deleted",
                scriptService.deleteBatch(uid(request), ws(workspaceId), req.scriptIds())));
    }

    @GetMapping("/{scriptId}")
    public ResponseEntity<ScriptResponse> get(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.get(uid(request), ws(workspaceId), scriptId));
    }

    @PatchMapping("/{scriptId}")
    public ResponseEntity<ScriptResponse> patch(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @Valid @RequestBody UpdateScriptRequest req) {
        return ResponseEntity.ok(scriptService.patch(uid(request), ws(workspaceId), scriptId, req));
    }

    // ------------------------------------------------------------ 剧本集市

    /** 提交分享（进入剧本精选待审；仅创建者/管理员）。 */
    @PostMapping("/{scriptId}/share")
    public ResponseEntity<ScriptCard> share(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.share(uid(request), ws(workspaceId), scriptId));
    }

    /** 剧本精选（已上架，非本人；游客可读）。 */
    @GetMapping("/marketplace")
    public ResponseEntity<ScriptPage> marketplace(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(scriptService.marketPage(uidOrNull(request), page, size));
    }

    /** 管理后台待审（管理员）。 */
    @GetMapping("/marketplace/pending")
    public ResponseEntity<ScriptPage> pending(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(scriptService.pendingPage(uid(request), page, size));
    }

    /** 审批（批量通过/驳回，管理员）。 */
    @PostMapping("/marketplace/review")
    public ResponseEntity<Map<String, Integer>> review(
            HttpServletRequest request,
            @Valid @RequestBody ReviewScriptRequest req) {
        return ResponseEntity.ok(Map.of("reviewed",
                scriptService.review(uid(request), req.scriptIds(), Boolean.TRUE.equals(req.approved()))));
    }

    /** 剧本精选只读卡片。 */
    @GetMapping("/marketplace/{scriptId}")
    public ResponseEntity<ScriptCard> marketGet(
            HttpServletRequest request,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.marketGet(uidOrNull(request), scriptId));
    }

    /** 剧本精选只读：集列表（游客可读）。 */
    @GetMapping("/marketplace/{scriptId}/episodes")
    public ResponseEntity<List<ScriptEpisodeResponse>> marketEpisodes(@PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.marketEpisodes(scriptId));
    }

    /** 点赞/收藏切换（like|fav）。 */
    @PostMapping("/marketplace/{scriptId}/toggle/{kind}")
    public ResponseEntity<MarkToggleResult> toggle(
            HttpServletRequest request,
            @PathVariable UUID scriptId,
            @PathVariable String kind) {
        return ResponseEntity.ok(scriptService.toggle(uid(request), scriptId, kind));
    }

    // ------------------------------------------------------------ 集

    @GetMapping("/{scriptId}/episodes")
    public ResponseEntity<List<ScriptEpisodeResponse>> listEpisodes(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.listEpisodes(uid(request), ws(workspaceId), scriptId));
    }

    @PostMapping("/{scriptId}/episodes")
    public ResponseEntity<EpisodeSaveResult> createEpisode(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @Valid @RequestBody SaveEpisodeRequest req) {
        return ResponseEntity.ok(scriptService.saveEpisode(
                uid(request), ws(workspaceId), scriptId, null, req));
    }

    @PatchMapping("/{scriptId}/episodes/{episodeId}")
    public ResponseEntity<EpisodeSaveResult> updateEpisode(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @PathVariable UUID episodeId,
            @Valid @RequestBody SaveEpisodeRequest req) {
        return ResponseEntity.ok(scriptService.saveEpisode(
                uid(request), ws(workspaceId), scriptId, episodeId, req));
    }

    @DeleteMapping("/{scriptId}/episodes/{episodeId}")
    public ResponseEntity<Map<String, Boolean>> deleteEpisode(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @PathVariable UUID episodeId) {
        scriptService.deleteEpisode(uid(request), ws(workspaceId), scriptId, episodeId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /** 变更记录（含被同步改动的历史章节）。 */
    @GetMapping("/{scriptId}/changes")
    public ResponseEntity<List<ScriptChangeResponse>> listChanges(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(scriptService.listChanges(uid(request), ws(workspaceId), scriptId, limit));
    }

    /** Q4：用户确认后应用一致性改动（AI 重写历史章节）。 */
    @PostMapping("/{scriptId}/episodes/{episodeId}/apply-sync")
    public ResponseEntity<ApplySyncResult> applySync(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @PathVariable UUID episodeId,
            @Valid @RequestBody ApplySyncRequest req) {
        return ResponseEntity.ok(scriptService.applySync(
                uid(request), ws(workspaceId), scriptId, episodeId, req));
    }

    // ------------------------------------------------------------ AI

    /** 新建剧本页：无剧本 ID 的字段生成（不落库，避免幽灵草稿）。 */
    @PostMapping("/ai/preview-field")
    public ResponseEntity<AiFieldResult> aiFieldPreview(
            HttpServletRequest request,
            @Valid @RequestBody AiFieldPreviewRequest req) {
        uid(request);   // 仅要求已登录（无工作区依赖）
        return ResponseEntity.ok(scriptService.aiFieldPreview(req));
    }

    @PostMapping("/{scriptId}/ai/field")
    public ResponseEntity<AiFieldResult> aiField(            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @Valid @RequestBody AiFieldRequest req) {
        return ResponseEntity.ok(scriptService.aiField(uid(request), ws(workspaceId), scriptId, req));
    }

    @PostMapping("/{scriptId}/ai/next-episode")
    public ResponseEntity<AiNextEpisodeResult> aiNextEpisode(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @Valid @RequestBody AiNextEpisodeRequest req) {
        return ResponseEntity.ok(scriptService.aiNextEpisode(uid(request), ws(workspaceId), scriptId, req));
    }

    @PostMapping("/{scriptId}/ai/condensed")
    public ResponseEntity<AiCondensedResult> aiCondensed(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.aiCondensed(uid(request), ws(workspaceId), scriptId));
    }

    @PostMapping("/{scriptId}/ai/guide")
    public ResponseEntity<AiGuideResult> aiGuide(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(scriptService.aiGuide(uid(request), ws(workspaceId), scriptId));
    }

    // ------------------------------------------------------------ 转成项目

    /** 把一集转成项目（建项目 + brief + 导演生成分镜动作与提示词）。 */
    @PostMapping("/{scriptId}/episodes/{episodeId}/to-project")
    public ResponseEntity<ConvertToProjectResult> toProject(
            HttpServletRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @PathVariable UUID scriptId,
            @PathVariable UUID episodeId,
            @Valid @RequestBody ConvertToProjectRequest req) {
        return ResponseEntity.ok(scriptService.toProject(
                uid(request), ws(workspaceId), scriptId, episodeId, req));
    }

    // ------------------------------------------------------------ 内部

    private UUID uidOrNull(HttpServletRequest request) {
        String uid = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        return uid == null ? null : UUID.fromString(uid);
    }

    private UUID uid(HttpServletRequest request) {
        String uid = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return UUID.fromString(uid);
    }

    private UUID ws(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, ScriptService.workspaceHint());
        }
        return UUID.fromString(workspaceId);
    }
}
