package studio.weaveora.asset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 资产（§7.6/§21）：参考图上传（W2C）+ Job 产物落库（W3 complete 调用）。 */
@Service
public class AssetService {

    private static final Set<String> ALLOWED_IMAGE = Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_UPLOAD = 20L * 1024 * 1024;

    private final AssetRepository assets;
    private final StoragePort storage;
    private final WorkspaceGuard guard;
    private final ProjectContextPort projects;

    public AssetService(AssetRepository assets, StoragePort storage, WorkspaceGuard guard,
                        ProjectContextPort projects) {
        this.assets = assets;
        this.storage = storage;
        this.guard = guard;
        this.projects = projects;
    }

    @Transactional
    public AssetResponse uploadReference(UUID userId, UUID workspaceId, UUID projectId, MultipartFile file) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId); // 项目须在本工作区
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "上传文件为空");
        }
        if (file.getSize() > MAX_UPLOAD) {
            throw new BizException(ErrorCode.UPLOAD_TOO_LARGE, "图片不能超过 20MB");
        }
        String mime = normalizeMime(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_IMAGE.contains(mime)) {
            throw new BizException(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED, "仅支持 png/jpg/webp 参考图");
        }
        String ext = ext(mime);
        String key = workspaceId + "/" + projectId + "/ref/" + UUID.randomUUID() + "." + ext;
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), mime);
        } catch (IOException e) {
            throw new IllegalStateException("参考图存储失败", e);
        }
        Asset a = Asset.reference(workspaceId, projectId, key, mime, null, null);
        return toResponse(assets.save(a));
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> listByProject(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        return assets.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 读取下载（返回流对象；不存在 → null，由控制器处理 NOT_FOUND）。 */
    @Transactional(readOnly = true)
    public Download getForDownload(UUID userId, UUID workspaceId, UUID assetId) {
        guard.requireMember(userId, workspaceId);
        Asset a = assets.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "资产不存在或不在本工作区"));
        studio.weaveora.infra.storage.StoragePort.StoredObject obj = storage.get(a.storageKey());
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件已不存在");
        }
        return new Download(a, obj.stream(), obj.contentType());
    }

    /** Job 产物（W3 complete）：job 模块调用，落同一 assets 表。 */
    @Transactional
    public Asset createOutput(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, String kind,
                              String storageKey, String mime, Integer width, Integer height, Long seed) {
        return assets.save(Asset.output(workspaceId, projectId, jobId, shotId, kind,
                storageKey, mime, width, height, seed));
    }

    public record Download(Asset asset, InputStream stream, String contentType) {
    }

    private AssetResponse toResponse(Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.kind(), a.mime(),
                a.width(), a.height(), a.createdAt());
    }

    private static String normalizeMime(String contentType, String filename) {
        if (contentType != null && ALLOWED_IMAGE.contains(contentType.toLowerCase())) {
            return contentType.toLowerCase();
        }
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static String ext(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }
}
