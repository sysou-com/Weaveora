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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssetService.class);

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
     * ★ 2026-09-16（用户提案）：把一张**参考图物化成真正的「定妆照」资产**，再让方案去绑它。
     *
     * <p>为什么要复制成新资产，而不是直接让 {@code subjects[].portraitAssetId} 指向参考图：
     * <ul>
     *   <li>以前是"指针方案"，于是方案的「定妆照」其实 {@code kind=reference} ——
     *       资产库的「定妆照」分类、{@code portraitsOf(subject)}、后端按 {@code kind=portrait} 扫描
     *       这三处都得额外写"宽容判断"才能认它（一不留神就误判成「没定妆照」→ 中止出图或静默剔除角色）；</li>
     *   <li>指针方案下**删掉那张素材图 = 定妆照悬空**（生成时立刻报"缺定妆照"或悄悄丢角色），
     *       复制成独立资产后两者生命周期解耦；</li>
     *   <li>一个主体可以有多版定妆照，{@code portraitVersion} 才有真实意义。</li>
     * </ul>
     *
     * <p>落库形态：{@code kind=portrait}、{@code prompt_snapshot.kind=portrait} + {@code subject} +
     * {@code portrait_version} + {@code source_asset_id}（可溯源到哪张素材转来的）。
     * 幂等：同一素材 + 同一主体已物化过就直接返回那一张（避免重复点出垃圾资产）。
     *
     * <p>⚠️ 语义提醒：这只是把「素材图」**换了个正确的身份**，并不会把它变成"标准角色设定图" ——
     * 多角色同框时参考集样式不统一仍会串脸，所以界面另外提示「推荐先生成定妆照」。
     */
    @Transactional
    public AssetResponse portraitFromAsset(UUID userId, UUID workspaceId, UUID projectId,
                                           UUID assetId, String subject, Integer version) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        String name = subject == null ? "" : subject.trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "缺少主体名");
        }
        studio.weaveora.asset.domain.Asset src = assets.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "参考图不存在"));
        if (!projectId.equals(src.projectId())) {
            throw new BizException(ErrorCode.VALIDATION, "该资产不属于本项目");
        }
        // 幂等：同一素材已为同一主体物化过 → 直接复用
        for (studio.weaveora.asset.domain.Asset a : assets
                .findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(projectId, workspaceId, "portrait")) {
            com.fasterxml.jackson.databind.JsonNode snap = a.promptSnapshot();
            if (snap != null && assetId.toString().equals(snap.path("source_asset_id").asText(""))
                    && name.equals(snap.path("subject").asText(""))) {
                return toResponse(a);
            }
        }
        String mime = src.mime() == null || src.mime().isBlank() ? "image/png" : src.mime();
        String ext = ext(mime);
        String key = workspaceId + "/" + projectId + "/portrait/" + UUID.randomUUID() + "." + ext;
        long size = 0;
        try (InputStream in = storage.get(src.storageKey()).stream()) {
            byte[] bytes = in.readAllBytes();
            size = bytes.length;
            storage.put(key, new java.io.ByteArrayInputStream(bytes), size, mime);
        } catch (Exception e) {
            throw new IllegalStateException("定妆照物化失败（复制素材图字节）: " + e.getMessage(), e);
        }
        int ver = version != null && version > 0 ? version : 1;
        com.fasterxml.jackson.databind.node.ObjectNode snap = mapper.createObjectNode();
        snap.put("kind", "portrait");
        snap.put("subject", name);
        snap.put("portrait_version", ver);
        snap.put("source", "from-reference");
        snap.put("source_asset_id", assetId.toString());
        snap.put("prompt", "（由所选参考图复制而来，未重新生成）");
        snap.put("note", "由参考图复制而来，不是标准角色设定图：多角色同框时样式可能不统一，建议改用「生成定妆照」");
        studio.weaveora.asset.domain.Asset made = studio.weaveora.asset.domain.Asset.output(
                workspaceId, projectId, null, null, null, "portrait", key, mime,
                src.width(), src.height(), null, null, snap);
        studio.weaveora.asset.domain.Asset saved = assets.save(made);
        log.info("asset: 参考图 {} → 定妆照 {}（主体 {}，{} bytes，v{}）", assetId, saved.id(), name, size, ver);
        return toResponse(saved);
    }

    /**
     * ★ 2026-09-16 夜（用户要求）：把资产库里的**任意一张图**复制一份进「参考图」。
     *
     * <p>为什么要复制而不是直接引用原资产 id：
     * <ul>
     *   <li>参考图是**独立的一堆候选素材**，只支持「勾选 / 删除」两态；如果直接指向某张 still/portrait，
     *       删参考图就会把那张原图一起删掉（数据库删连坐），而且它会同时出现在「关键帧」「定妆」两个 Tab 里；</li>
     *   <li>复制件带 {@code source_asset_id} 快照，能回溯“这张参考图从哪来”。</li>
     * </ul>
     * 若原资产**本身就是** kind=reference（用户上传的素材图），则不复制 —— 它已经是参考图了，直接返回。
     */
    @Transactional
    public AssetResponse referenceFromAsset(UUID userId, UUID workspaceId, UUID projectId, UUID assetId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        studio.weaveora.asset.domain.Asset src = assets.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "资产不存在"));
        if (!projectId.equals(src.projectId())) {
            throw new BizException(ErrorCode.VALIDATION, "该资产不属于本项目");
        }
        if ("reference".equals(src.kind())) {
            return toResponse(src);      // 已经是参考图：不重复复制
        }
        // 幂等：同一资产已复制过一次就复用
        for (studio.weaveora.asset.domain.Asset a : assets
                .findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(projectId, workspaceId, "reference")) {
            com.fasterxml.jackson.databind.JsonNode snap = a.promptSnapshot();
            if (snap != null && assetId.toString().equals(snap.path("source_asset_id").asText(""))) {
                return toResponse(a);
            }
        }
        String mime = src.mime() == null || src.mime().isBlank() ? "image/png" : src.mime();
        String key = workspaceId + "/" + projectId + "/ref/" + UUID.randomUUID() + "." + ext(mime);
        long size = 0;
        try (InputStream in = storage.get(src.storageKey()).stream()) {
            byte[] bytes = in.readAllBytes();
            size = bytes.length;
            storage.put(key, new java.io.ByteArrayInputStream(bytes), size, mime);
        } catch (Exception e) {
            throw new IllegalStateException("参考图复制失败（复制资产字节）: " + e.getMessage(), e);
        }
        com.fasterxml.jackson.databind.node.ObjectNode snap = mapper.createObjectNode();
        snap.put("kind", "reference");
        snap.put("source", "from-asset");
        snap.put("source_asset_id", assetId.toString());
        snap.put("source_kind", src.kind());
        // 原图如果带了主体标注（定妆照/产物快照里的 subject）就一起带过来，便于前端显示与排查
        com.fasterxml.jackson.databind.JsonNode srcSnap = src.promptSnapshot();
        if (srcSnap != null && !srcSnap.path("subject").asText("").isBlank()) {
            snap.put("source_subject", srcSnap.path("subject").asText(""));
        }
        snap.put("prompt", "（由资产库中的图复制而来，未重新生成）");
        studio.weaveora.asset.domain.Asset made = studio.weaveora.asset.domain.Asset.output(
                workspaceId, projectId, null, null, null, "reference", key, mime,
                src.width(), src.height(), null, null, snap);
        studio.weaveora.asset.domain.Asset saved = assets.save(made);
        log.info("asset: {} {} → 参考图 {}（{} bytes）", src.kind(), assetId, saved.id(), size);
        return toResponse(saved);
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
                a.width(), a.height(), a.durationMs(), lineIndexOf(a),
                snapText(a, "subject"),
                snapInt(a, "portrait_version"),
                snapText(a, "kind"),
                faceDetectedOf(a),
                faceFramesOf(a),
                a.createdAt());
    }

    /** P13：产物的主体名（快照 subject）；非定妆图为 null。 */
    public static String subjectOf(Asset a) {
        return snapText(a, "subject");
    }

    /** P13：快照里的产物类别（portrait = 定妆图）；兼容历史数据 kind 被写成 still 的情况。 */
    public static String snapshotKindOf(Asset a) {
        return snapText(a, "kind");
    }

    /** 快照里的字符串字段（取不到返回 null）。 */
    private static String snapText(Asset a, String field) {
        var snap = a.promptSnapshot();
        return snap != null && snap.hasNonNull(field) ? snap.path(field).asText("") : null;
    }

    /** 快照里的整数字段（取不到返回 null）。 */
    private static Integer snapInt(Asset a, String field) {
        var snap = a.promptSnapshot();
        return snap != null && snap.hasNonNull(field) ? snap.path(field).asInt() : null;
    }

    /** 快照里的布尔字段（取不到返回 null；与“false” 区分）。 */
    private static Boolean snapBool(Asset a, String field) {
        var snap = a.promptSnapshot();
        return snap != null && snap.hasNonNull(field) ? snap.path(field).asBoolean() : null;
    }

    /**
     * P13：该产物画面里是否检出了人脸（worker 在出 motion 时抽样 6 帧检测后写进快照）。
     *
     * <p>false = 一帧都没检出：对口型跑不了（LatentSync 会报 Face not detected），
     * 选镜弹窗靠它把该镜标成「不可：无人脸」；null = 未知（历史数据）。
     */
    public static Boolean faceDetectedOf(Asset a) {
        return snapBool(a, "faceDetected");
    }

    /** P13：抽样帧里能检出人脸的帧数（如 "4/6"）；未检测为 null。 */
    public static String faceFramesOf(Asset a) {
        return snapText(a, "faceFrames");
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
