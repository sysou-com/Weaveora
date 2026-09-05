package studio.weaveora.project.api;

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
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.project.ProjectService;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.UUID;

/** 项目端点（§17.2）：/api/v1/projects。 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    public static final String WORKSPACE_HEADER = "X-Workspace-Id";

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId) {
        return ResponseEntity.ok(projectService.list(uid(request), ws(workspaceId)));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @Valid @RequestBody CreateProjectRequest req) {
        return ResponseEntity.ok(projectService.create(uid(request), ws(workspaceId), req));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.get(uid(request), ws(workspaceId), projectId));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> rename(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody RenameRequest req) {
        return ResponseEntity.ok(projectService.rename(uid(request), ws(workspaceId), projectId, req.title()));
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
            throw new BizException(ErrorCode.VALIDATION, "缺少 " + WORKSPACE_HEADER + " 请求头");
        }
        return UUID.fromString(workspaceId);
    }

    public record RenameRequest(@NotBlank String title) {
    }
}
