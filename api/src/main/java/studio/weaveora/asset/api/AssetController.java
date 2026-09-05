package studio.weaveora.asset.api;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import studio.weaveora.asset.AssetService;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 资产端点：参考图上传/列表（项目维度）+ 下载。 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /** 上传参考图（W2C）：multipart 字段 file */
    @PostMapping(value = "/projects/{projectId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetResponse> upload(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(assetService.uploadReference(
                uid(request), ws(workspaceId), projectId, file));
    }

    @GetMapping("/projects/{projectId}/assets")
    public ResponseEntity<List<AssetResponse>> list(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(assetService.listByProject(
                uid(request), ws(workspaceId), projectId));
    }

    @GetMapping("/assets/{assetId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID assetId) {
        AssetService.Download d = assetService.getForDownload(uid(request), ws(workspaceId), assetId);
        InputStreamResource res = new InputStreamResource(d.stream());
        String name = d.asset().id() + "." + ext(d.contentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(res);
    }

    /** 批量删除所选资产（资产库勾选，删行+删文件） */
    @PostMapping("/projects/{projectId}/assets/delete")
    public ResponseEntity<Map<String, Integer>> delete(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @jakarta.validation.Valid @RequestBody BatchAssetRequest req) {
        int removed = assetService.delete(uid(request), ws(workspaceId), projectId, req.assetIds());
        return ResponseEntity.ok(Map.of("deleted", removed));
    }

    private static String ext(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "application/zip" -> "zip";
            default -> "png";
        };
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
