package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.director.PlanReader;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * W8 P0：ffmpeg 把已确认视频项目的有序素材合成单 mp4（“可交付自动成片”）。
 * - 真 clip(mp4/webm) 优先；只有关键帧(图片)时按镜时长静帧幻灯兜底。
 * - 依赖宿主机 ffmpeg（生产 VPS 已装 7.0.2-static；dev 需自行安装）。
 */
@Service
public class ConcatService {

    private static final Logger log = LoggerFactory.getLogger(ConcatService.class);
    private static final long FFMPEG_TIMEOUT_SEC = 900;

    private final AssetService assets;
    private final AssetRepository assetRepo;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final PlanReader planReader;
    private final String ffmpeg;

    public ConcatService(AssetService assets, AssetRepository assetRepo, StoragePort storage,
                         ProjectContextPort projects, WorkspaceGuard guard, PlanReader planReader,
                         @Value("${weaveora.ffmpeg:ffmpeg}") String ffmpeg) {
        this.assets = assets;
        this.assetRepo = assetRepo;
        this.storage = storage;
        this.projects = projects;
        this.guard = guard;
        this.planReader = planReader;
        this.ffmpeg = ffmpeg;
    }

    @Transactional
    public AssetResponse renderMaster(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (!revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先确认方案再渲染成片");
        }
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频项目可渲染成片");
        }
        List<UUID> shotIds = planReader.shotIds(revisionId);
        List<MediaClip> clips = orderedMedia(workspaceId, projectId, plan, shotIds);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.EXPORT_EMPTY, "没有可用素材（先生成关键帧/运动）");
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("weaveora-render-");
            List<Path> segs = new ArrayList<>();
            for (MediaClip c : clips) {
                Path raw = work.resolve("src_" + segs.size());
                writeAsset(c.assetKey(), raw);
                Path seg = work.resolve("seg_" + segs.size() + ".mp4");
                if (isVideo(c)) {
                    runFfmpeg("-y", "-i", raw.toString(), "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                            "-an", seg.toString());
                } else {
                    // 静帧 → 定长片段
                    runFfmpeg("-y", "-loop", "1", "-t", String.valueOf(c.durationSec()), "-i", raw.toString(),
                            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                            "-an", seg.toString());
                }
                segs.add(seg);
            }
            Path list = work.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path s : segs) {
                sb.append("file '").append(s.getFileName()).append("'\n");
            }
            Files.writeString(list, sb.toString());
            Path master = work.resolve("master.mp4");
            runFfmpeg("-y", "-f", "concat", "-safe", "0", "-i", list.toString(),
                    "-c", "copy", master.toString());
            byte[] bytes = Files.readAllBytes(master);
            String key = workspaceId + "/" + projectId + "/master/" + UUID.randomUUID() + ".mp4";
            try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                storage.put(key, in, bytes.length, "video/mp4");
            }
            Asset a = assets.createOutput(workspaceId, projectId, null, null, "master",
                    key, "video/mp4", 1280, 720, null, null);
            return toAssetResponse(a);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("成片合成失败", e);
        } finally {
            if (work != null) {
                try {
                    Files.walk(work).sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private List<MediaClip> orderedMedia(UUID workspaceId, UUID projectId, JsonNode plan, List<UUID> shotIds) {
        List<MediaClip> out = new ArrayList<>();
        int order = 0;
        for (JsonNode shot : plan.path("shots")) {
            order++;
            double dur = shot.path("duration_sec").asDouble(3);
            UUID shotId = order <= shotIds.size() ? shotIds.get(order - 1) : null;
            Asset m = pickClipOrStill(workspaceId, shotId);
            if (m == null) continue;
            out.add(new MediaClip(m.storageKey(), isVideo(m), dur));
        }
        return out;
    }

    private Asset pickClipOrStill(UUID workspaceId, UUID shotId) {
        if (shotId == null) return null;
        List<Asset> clips = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "clip");
        if (!clips.isEmpty()) return clips.get(0);
        List<Asset> stills = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "still");
        return stills.isEmpty() ? null : stills.get(0);
    }

    private void writeAsset(String storageKey, Path target) throws IOException {
        var obj = storage.get(storageKey);
        if (obj == null) throw new IllegalStateException("资产文件缺失 " + storageKey);
        try (InputStream in = obj.stream()) {
            Files.copy(in, target);
        }
    }

    private void runFfmpeg(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            log.warn("ffmpeg 失败: {}", out.length() > 400 ? out.substring(out.length() - 400) : out);
            throw new IllegalStateException("ffmpeg 执行失败（exit=" + p.exitValue() + "）");
        }
    }

    private static boolean isVideo(Asset a) {
        String m = a.mime() == null ? "" : a.mime();
        return m.startsWith("video/") || m.contains("mp4") || m.contains("webm");
    }

    private static boolean isVideo(MediaClip c) {
        return c.video();
    }

    private AssetResponse toAssetResponse(Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.kind(), a.mime(),
                a.width(), a.height(), a.createdAt());
    }

    private record MediaClip(String assetKey, boolean video, double durationSec) {
    }
}
