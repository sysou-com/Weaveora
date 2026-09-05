package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.director.PlanReader;
import studio.weaveora.export.api.ExportDetail;
import studio.weaveora.export.api.ExportInfo;
import studio.weaveora.export.domain.EditPackage;
import studio.weaveora.export.domain.EditPackageRepository;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 成片导出（§12：自有稳定契约 edit_list.json + zip 打包）。MVP：视频项目时间线（clip 优先，still 兜底）。
 */
@Service
public class ExportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EditPackageRepository packages;
    private final AssetRepository assets;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final PlanReader planReader;

    public ExportService(EditPackageRepository packages, AssetRepository assets, StoragePort storage,
                         ProjectContextPort projects, WorkspaceGuard guard, PlanReader planReader) {
        this.packages = packages;
        this.assets = assets;
        this.storage = storage;
        this.projects = projects;
        this.guard = guard;
        this.planReader = planReader;
    }

    @Transactional
    public ExportInfo create(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (!revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先确认方案再导出");
        }
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "当前仅支持视频项目导出成片（图片请从资产库下载）");
        }
        List<UUID> shotIds = planReader.shotIds(revisionId);
        PackageData data = build(workspaceId, project, plan, shotIds);

        String zipKey = workspaceId + "/" + projectId + "/exports/" + UUID.randomUUID() + ".zip";
        storage.put(zipKey, new ByteArrayInputStream(data.zip()), data.zip().length, "application/zip");
        EditPackage saved = packages.save(EditPackage.create(workspaceId, projectId, revisionId,
                zipKey, data.editList(), userId));
        return new ExportInfo(saved.id(), saved.projectId(), saved.revisionId(),
                "/api/v1/exports/" + saved.id() + "/download", saved.createdAt());
    }

    @Transactional(readOnly = true)
    public ExportDetail get(UUID userId, UUID workspaceId, UUID exportId) {
        guard.requireMember(userId, workspaceId);
        EditPackage p = packages.findByIdAndWorkspaceId(exportId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "导出不存在或不在本工作区"));
        return new ExportDetail(p.id(), p.projectId(), p.revisionId(), p.editList(), p.createdAt());
    }

    /** 下载 zip（文件不存在 → null，由控制器返回 404）。 */
    @Transactional(readOnly = true)
    public StoragePort.StoredObject download(UUID userId, UUID workspaceId, UUID exportId) {
        guard.requireMember(userId, workspaceId);
        EditPackage p = packages.findByIdAndWorkspaceId(exportId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "导出不存在或不在本工作区"));
        return storage.get(p.storageKey());
    }

    // ---------- 组装 ----------

    private PackageData build(UUID workspaceId, ProjectSnapshot project, JsonNode plan, List<UUID> shotIds) {
        JsonNode editPlan = plan.path("edit_plan");
        int fps = editPlan.path("fps").asInt(30);
        int width = 1280;
        int height = 720;
        double duration = 0;
        ArrayNode shotsNode = (ArrayNode) plan.path("shots");
        // 总时长按 shot.duration_sec
        for (JsonNode s : shotsNode) duration += s.path("duration_sec").asDouble(0);

        ObjectNode list = MAPPER.createObjectNode();
        list.put("version", "1.0");
        list.put("project_id", project.id().toString());
        list.put("title", plan.path("title").asText(project.id().toString()));
        list.put("fps", fps);
        list.put("width", width);
        list.put("height", height);
        list.put("duration_sec", round2(duration));

        ArrayNode tracks = list.putArray("tracks");
        ArrayNode video = tracks.addObject().put("type", "video").putArray("clips");
        tracks.addObject().put("type", "audio").putArray("clips");
        tracks.addObject().put("type", "caption").putArray("clips");

        StringBuilder prompts = new StringBuilder();
        prompts.append("# ").append(plan.path("title").asText("")).append("\n\n");
        prompts.append(plan.path("logline").asText("")).append("\n\n");
        StringBuilder readme = new StringBuilder();
        readme.append("# Weaveora 导出包（织影）\n\n")
                .append("这些片段由 Weaveora 生成。精修请在剪映/CapCut 完成（v2.0：兼容可选项，§12.2）。\n")
                .append("edit_list.json 是稳定契约；若剪映适配失败，请按 edit_list.json 手工导入。\n\n");

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        double cursor = 0;
        int order = 0;
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            for (JsonNode shot : shotsNode) {
                order++;
                int shotNo = shot.path("shot_no").asInt(order);
                double dur = shot.path("duration_sec").asDouble(3);
                UUID shotId = order <= shotIds.size() ? shotIds.get(order - 1) : null;
                Asset media = pick(workspaceId, shotId);
                prompts.append("## Shot ").append(shotNo).append(" (").append(round2(dur))
                        .append("s)\n")
                        .append("- camera: ").append(shot.path("camera_move").asText("")).append("\n")
                        .append("- positive: ").append(shot.path("positive_prompt").asText("")).append("\n")
                        .append("- negative: ").append(shot.path("negative_prompt").asText("")).append("\n\n");
                if (media == null) {
                    cursor += dur;
                    continue;
                }
                String ext = extOf(media.mime());
                String src = "assets/shot_" + String.format("%02d", shotNo) + "." + ext;
                ObjectNode clip = video.addObject();
                clip.put("shot_no", shotNo);
                clip.put("src", src);
                clip.put("in_sec", 0);
                double durSec = media.kind().equals("clip") && media.durationMs() != null
                        ? media.durationMs() / 1000.0 : dur;
                clip.put("out_sec", round2(durSec));
                clip.put("timeline_start_sec", round2(cursor));
                if (media.width() != null) width = media.width();
                if (media.height() != null) height = media.height();

                byte[] file = readAsset(media);
                zip.putNextEntry(new ZipEntry(src));
                zip.write(file);
                zip.closeEntry();
                cursor += dur;
            }
            list.put("width", width);
            list.put("height", height);

            zip.putNextEntry(new ZipEntry("edit_list.json"));
            zip.write(MAPPER.writeValueAsBytes(list));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("prompts.md"));
            zip.write(prompts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write(readme.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("导出打包失败", e);
        }
        return new PackageData(list, zipBytes.toByteArray());
    }

    private Asset pick(UUID workspaceId, UUID shotId) {
        if (shotId == null) return null;
        List<Asset> clips = assets.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "clip");
        if (!clips.isEmpty()) return clips.get(0);
        List<Asset> stills = assets.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "still");
        return stills.isEmpty() ? null : stills.get(0);
    }

    private byte[] readAsset(Asset a) {
        var obj = storage.get(a.storageKey());
        if (obj == null) return new byte[0];
        try (InputStream in = obj.stream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取资产失败", e);
        }
    }

    private static String extOf(String mime) {
        if (mime == null) return "bin";
        if (mime.contains("jpeg")) return "jpg";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("mp4")) return "mp4";
        if (mime.contains("webm")) return "webm";
        if (mime.contains("png")) return "png";
        return "bin";
    }

    private static double round2(double d) {
        return Math.round(d * 100) / 100.0;
    }

    private record PackageData(JsonNode editList, byte[] zip) {
    }
}
