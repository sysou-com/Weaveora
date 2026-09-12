package studio.weaveora.director.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.director.DirectorService;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.UUID;

/** 导演层端点（§17.2）：generate / revisions 查改 / approve。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DirectorController {

    private final DirectorService directorService;
    private final studio.weaveora.director.AiAudioService aiAudioService;
    private final studio.weaveora.director.SubjectService subjectService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public DirectorController(DirectorService directorService,
                              studio.weaveora.director.AiAudioService aiAudioService,
                              studio.weaveora.director.SubjectService subjectService) {
        this.subjectService = subjectService;
        this.directorService = directorService;
        this.aiAudioService = aiAudioService;
    }

    /**
     * P11：AI 一键生成台词（分析画面+人物 → 1~3 段台词，模拟对话）。
     *
     * @param shotNo  可选；不传 = 为所有“还没有台词”的镜头批量生成
     * @param replace true = 覆盖该镜已有台词
     */
    @PostMapping("/revisions/{revisionId}/ai-lines")
    public ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> aiLines(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId,
            @RequestBody(required = false) AiLinesRequest body) {
        Integer shotNo = body == null ? null : body.shotNo();
        boolean replace = body != null && Boolean.TRUE.equals(body.replace());
        var r = aiAudioService.generateLines(uid(request), ws(workspaceId), projectId, revisionId, shotNo, replace);
        return ResponseEntity.ok(studio.weaveora.director.AiAudioService.linesToJson(mapper, r));
    }

    /** P13：一键抽取剧情主体（人物/载具/物件/场景），已有主体保留、只补新的。 */
    @PostMapping("/revisions/{revisionId}/subjects/extract")
    public ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> extractSubjects(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId) {
        var r = subjectService.extract(uid(request), ws(workspaceId), projectId, revisionId);
        var body = mapper.createObjectNode();
        body.put("source", r.source());
        var arr = body.putArray("subjects");
        r.subjects().forEach(s -> {
            var o = arr.addObject();
            o.put("name", s.name());
            o.put("kind", s.kind());
            o.put("enabled", s.enabled());
            o.put("hasPortrait", s.hasPortrait());
            o.put("portraitVersion", s.portraitVersion());
            o.put("refCount", s.refs().size());
            o.put("checkedCount", s.checkedRefs().size());
            var al = o.putArray("aliases");
            s.aliases().forEach(al::add);
        });
        var added = body.putArray("added");
        r.added().forEach(added::add);
        return ResponseEntity.ok(body);
    }

    /** P11：AI 一键配乐（依据剧情给出 2~5 段「时间段 + 情绪」）。 */
    @PostMapping("/revisions/{revisionId}/ai-music")
    public ResponseEntity<java.util.Map<String, Object>> aiMusic(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId) {
        var r = aiAudioService.generateMusic(uid(request), ws(workspaceId), projectId, revisionId);
        java.util.List<java.util.Map<String, Object>> cues = new java.util.ArrayList<>();
        for (var c : r.cues()) {
            cues.add(java.util.Map.of(
                    "id", c.id(), "start_sec", c.startSec(), "end_sec", c.endSec(),
                    "mood", c.mood(), "gain_db", c.gainDb(),
                    "fade_in_sec", c.fadeInSec(), "fade_out_sec", c.fadeOutSec(),
                    "loop", c.loop(), "duck", c.duck()));
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("source", r.source());
        out.put("music", cues);
        out.put("notes", r.notes());
        return ResponseEntity.ok(out);
    }

    /** AI 台词请求体（shotNo 空 = 批量）。 */
    public record AiLinesRequest(Integer shotNo, Boolean replace) {
    }

    @PostMapping("/director/rewrite-prompt")
    public ResponseEntity<java.util.Map<String, String>> rewritePrompt(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody RewritePromptRequest body) {
        return ResponseEntity.ok(directorService.rewritePrompt(
                uid(request), ws(workspaceId), projectId, body.rawText(),
                body.originalPositive(), body.originalNegative()));
    }

    public record RewritePromptRequest(@NotBlank String rawText,
                                       String originalPositive,
                                       String originalNegative) {
    }

    @PostMapping("/director/generate")
    public ResponseEntity<GenerateResponse> generate(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody GenerateRequest req) {
        return ResponseEntity.ok(directorService.generate(
                uid(request), ws(workspaceId), projectId, req));
    }

    @GetMapping("/revisions")
    public ResponseEntity<List<RevisionSummaryResponse>> listRevisions(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(directorService.listRevisions(
                uid(request), ws(workspaceId), projectId));
    }

    @GetMapping("/revisions/{revisionId}")
    public ResponseEntity<RevisionDetailResponse> getRevision(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId) {
        return ResponseEntity.ok(directorService.getRevision(
                uid(request), ws(workspaceId), projectId, revisionId));
    }

    @PatchMapping("/revisions/{revisionId}")
    public ResponseEntity<RevisionDetailResponse> patchRevision(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody PatchRevisionRequest req) {
        return ResponseEntity.ok(directorService.patchRevision(
                uid(request), ws(workspaceId), projectId, revisionId, req));
    }

    /** P13：就地保存方案（不另存版本、不改确认态）——供逐条重生成/试听配音使用。 */
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

    /** P13：只更新主体元数据（别名/勾选），就地生效、不另存版本。 */
    @PostMapping("/revisions/{revisionId}/subjects/meta")
    public ResponseEntity<RevisionDetailResponse> patchSubjectMeta(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return ResponseEntity.ok(directorService.patchSubjectMeta(
                uid(request), ws(workspaceId), projectId, revisionId, body));
    }

    @PostMapping("/revisions/{revisionId}/approve")
    public ResponseEntity<RevisionApproveResult> approve(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID revisionId) {
        return ResponseEntity.ok(directorService.approveRevision(
                uid(request), ws(workspaceId), projectId, revisionId));
    }

    @PostMapping("/shots/{shotId}/approve")
    public ResponseEntity<ShotApproveResponse> approveShot(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID shotId) {
        return ResponseEntity.ok(directorService.approveShot(
                uid(request), ws(workspaceId), projectId, shotId));
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
            throw new BizException(ErrorCode.VALIDATION, "缺少 " + ProjectController.WORKSPACE_HEADER + " 请求头");
        }
        return UUID.fromString(workspaceId);
    }
}
