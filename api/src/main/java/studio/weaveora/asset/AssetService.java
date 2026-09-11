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
    /** P8：导入配音允许的音频类型与大小上限 */
    private static final Set<String> ALLOWED_AUDIO = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/flac", "audio/x-flac");
    private static final long MAX_VOICE_UPLOAD = 50L * 1024 * 1024;

    private final AssetRepository assets;
    private final StoragePort storage;
    private final WorkspaceGuard guard;
    private final ProjectContextPort projects;
    /** P8：构造配音快照（prompt_snapshot）用 */
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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

    /**
     * P8：用户上传「一段配音」（导入自己配好的声音）。
     *
     * <p>落的资产 kind=voice、shot_no 有值，并在 prompt_snapshot 里写
     * {@code line_index / at_sec / subject / source=upload} —— 与生成产物同格式，
     * 所以混音与导出完全不需要区分来源。
     */
    @Transactional
    public AssetResponse uploadVoiceLine(UUID userId, UUID workspaceId, UUID projectId, int shotNo,
                                        int lineIndex, double atSec, String subject, MultipartFile file) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "上传文件为空");
        }
        if (file.getSize() > MAX_VOICE_UPLOAD) {
            throw new BizException(ErrorCode.UPLOAD_TOO_LARGE, "配音文件不能超过 50MB");
        }
        String mime = normalizeMime(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_AUDIO.contains(mime)) {
            throw new BizException(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED,
                    "仅支持 mp3/wav/m4a/aac/ogg/flac 音频（当前 " + mime + "；如为其它格式请先转换）");
        }
        String ext = ext(mime);
        String key = workspaceId + "/" + projectId + "/voice/" + UUID.randomUUID() + "." + ext;
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), mime);
        } catch (IOException e) {
            throw new IllegalStateException("配音文件存储失败", e);
        }
        com.fasterxml.jackson.databind.node.ObjectNode snap = mapper.createObjectNode();
        snap.put("kind", "voice");
        snap.put("line_index", Math.max(0, lineIndex));
        snap.put("at_sec", Math.max(0, atSec));
        snap.put("line_kind", lineIndex == 0 && (subject == null || subject.isBlank()) ? "narration" : "dialogue");
        if (subject != null && !subject.isBlank()) {
            snap.put("subject", subject.trim());
        }
        snap.put("source", "upload");
        Asset a = Asset.output(workspaceId, projectId, null, null, shotNo, "voice",
                key, mime, null, null, null, null, snap);
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

    /** 删除资产（资产库勾选：still/clip/参考图均可删）：删行 + 删存储文件（thumb 一并）。 */
    @Transactional
    public int delete(UUID userId, UUID workspaceId, UUID projectId, List<UUID> assetIds) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        int removed = 0;
        for (UUID assetId : assetIds) {
            Asset a = assets.findByIdAndWorkspaceId(assetId, workspaceId).orElse(null);
            if (a == null || !a.projectId().equals(projectId)) {
                continue;
            }
            try {
                storage.delete(a.storageKey());
                if (a.thumbKey() != null && !a.thumbKey().isBlank()) {
                    storage.delete(a.thumbKey());
                }
            } catch (RuntimeException ignore) {
                // 文件缺失不阻塞记录删除
            }
            assets.delete(a);
            removed++;
        }
        return removed;
    }

    /** Job 产物（W3 complete）：job 模块调用，落同一 assets 表。 */
    @Transactional
    public Asset createOutput(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, Integer shotNo,
                              String kind, String storageKey, String mime, Integer width, Integer height,
                              Long seed, Integer durationMs) {
        return createOutput(workspaceId, projectId, jobId, shotId, shotNo, kind, storageKey, mime,
                width, height, seed, durationMs, null);
    }

    /** 同上，额外落 prompt_snapshot（产生该资产的 job payload；P8 用它承载配音在镜内的 at_sec/line_index）。 */
    @Transactional
    public Asset createOutput(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, Integer shotNo,
                              String kind, String storageKey, String mime, Integer width, Integer height,
                              Long seed, Integer durationMs,
                              com.fasterxml.jackson.databind.JsonNode promptSnapshot) {
        return assets.save(Asset.output(workspaceId, projectId, jobId, shotId, shotNo, kind,
                storageKey, mime, width, height, seed, durationMs, promptSnapshot));
    }

    public record Download(Asset asset, InputStream stream, String contentType) {
    }

    private AssetResponse toResponse(Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.shotNo(), a.kind(), a.mime(),
                a.width(), a.height(), a.durationMs(), lineIndexOf(a), a.createdAt());
    }

    /** P10：配音产物在镜内的段号（写产生它的 job payload 快照里）；非配音为空。 */
    public static Integer lineIndexOf(Asset a) {
        var snap = a.promptSnapshot();
        if (snap == null || !snap.hasNonNull("line_index")) {
            return null;
        }
        return snap.path("line_index").asInt(0);
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
