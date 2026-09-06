package studio.weaveora.project.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
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
import studio.weaveora.project.ProjectService;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.Map;
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

    // ---------- 内测管理 / 项目集市 ----------

    /** 我的项目（分页：最近更新时间倒序，默认 8/页） */
    @GetMapping("/own")
    public ResponseEntity<ProjectPage> own(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(projectService.ownPage(uid(request), ws(workspaceId), page, size));
    }

    /** 管理态批量删除（软删） */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Integer>> deleteBatch(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @Valid @RequestBody BatchDeleteProjectRequest req) {
        int n = projectService.deleteBatch(uid(request), ws(workspaceId), req.projectIds());
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    /** 提交分享（进入集市待审） */
    @PostMapping("/{projectId}/share")
    public ResponseEntity<ProjectCard> share(
            HttpServletRequest request,
            @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.share(uid(request), ws(workspaceId), projectId));
    }

    /** 集市（已上架，非本人） */
    @GetMapping("/marketplace")
    public ResponseEntity<ProjectPage> marketplace(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(projectService.marketPage(uid(request), page, size));
    }

    /** 管理后台待审（管理员） */
    @GetMapping("/marketplace/pending")
    public ResponseEntity<ProjectPage> pending(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(projectService.pendingPage(uid(request), page, size));
    }

    /** 审批（批量通过/驳回，管理员） */
    @PostMapping("/marketplace/review")
    public ResponseEntity<Map<String, Integer>> review(
            HttpServletRequest request,
            @Valid @RequestBody ReviewProjectRequest req) {
        int n = projectService.review(uid(request), req.projectIds(), Boolean.TRUE.equals(req.approved()));
        return ResponseEntity.ok(Map.of("reviewed", n));
    }

    /** 集市只读详情卡片 */
    @GetMapping("/marketplace/{projectId}")
    public ResponseEntity<ProjectCard> marketGet(
            HttpServletRequest request,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.marketGet(uid(request), projectId));
    }

    /** 集市预览图（该集市项目最新图片资产；跨工作区无需成员身份） */
    @GetMapping("/marketplace/{projectId}/preview")
    public ResponseEntity<org.springframework.core.io.Resource> marketPreview(
            HttpServletRequest request,
            @PathVariable UUID projectId) {
        var opt = projectService.preview(uid(request), projectId);
        if (opt.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "暂无预览图");
        }
        ProjectService.Preview pv = opt.get();
        String ext = switch (pv.mime() == null ? "image/png" : pv.mime()) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pv.mime() == null ? "image/png" : pv.mime()))
                .body(new InputStreamResource(pv.stream()));
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
