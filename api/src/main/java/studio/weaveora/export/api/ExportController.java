package studio.weaveora.export.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.export.ExportService;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.UUID;

/** 成片导出端点（§12/§17.2）。 */
@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private final ExportService exportService;
    private final studio.weaveora.export.ConcatService concatService;

    public ExportController(ExportService exportService, studio.weaveora.export.ConcatService concatService) {
        this.exportService = exportService;
        this.concatService = concatService;
    }

    @PostMapping("/projects/{projectId}/exports")
    public ResponseEntity<ExportInfo> create(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ExportRequest req) {
        return ResponseEntity.ok(exportService.create(
                uid(request), ws(workspaceId), projectId, req.revisionId()));
    }

    @PostMapping("/projects/{projectId}/render")
    public ResponseEntity<studio.weaveora.asset.api.AssetResponse> render(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody RenderRequest req) {
        return ResponseEntity.ok(concatService.renderMaster(
                uid(request), ws(workspaceId), projectId, req.revisionId()));
    }

    @GetMapping("/projects/{projectId}/exports/{exportId}")
    public ResponseEntity<ExportDetail> detail(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID exportId) {
        return ResponseEntity.ok(exportService.get(uid(request), ws(workspaceId), exportId));
    }

    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID exportId) {
        StoragePort.StoredObject obj = exportService.download(uid(request), ws(workspaceId), exportId);
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出文件不存在");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"weaveora-export-" + exportId.toString().substring(0, 8) + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(obj.stream()));
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
