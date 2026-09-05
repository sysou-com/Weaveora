package studio.weaveora.director.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    public DirectorController(DirectorService directorService) {
        this.directorService = directorService;
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
