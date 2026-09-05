package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.director.PlanReader;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * W8 P0/P1：ffmpeg 把已确认视频项目的素材合成单 mp4（“可交付自动成片”）。
 * 轻精修：统一分辨率/fps/像素格式 → 每段编码（静帧幻灯或视频，附静音 AAC 轨）→ concat；
 * transition=fade 时对每段做入/出 0.25s 淡入淡出；转场统一、无黑帧（重编码保证）、时长守恒。
 * 依赖宿主 ffmpeg（生产 VPS 已装 7.0.2-static；dev 需自带）。
 */
@Service
public class ConcatService {

    private static final Logger log = LoggerFactory.getLogger(ConcatService.class);
    private static final long FFMPEG_TIMEOUT_SEC = 1200;

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
    public AssetResponse renderMaster(UUID userId, UUID workspaceId, UUID projectId,
                                      UUID revisionId, String transition) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (!revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先确认方案再渲染成片");
        }
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频项目可渲染成片");
        }
        boolean fade = "fade".equals(transition);
        int fps = plan.path("edit_plan").path("fps").asInt(30);
        List<UUID> shotIds = planReader.shotIds(revisionId);
        List<MediaClip> clips = orderedMedia(workspaceId, plan, shotIds);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.EXPORT_EMPTY, "没有可用素材（先生成关键帧/运动）");
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("weaveora-render-");
            List<Path> segs = new ArrayList<>();
            int idx = 0;
            for (MediaClip c : clips) {
                idx++;
                Path raw = work.resolve("src_" + idx);
                writeAsset(c.assetKey(), raw);
                Path seg = work.resolve("seg_" + idx + ".mp4");
                encodeSegment(raw, seg, c, fps, fade, c.durationSec());
                segs.add(seg);
            }
            Path list = work.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path s : segs) sb.append("file '").append(s.toAbsolutePath()).append("'\n");
            Files.writeString(list, sb.toString());
            Path master = work.resolve("master.mp4");
            // concat（各段同参数含 AAC 轨）→ 封装拷贝，无二次转码损失
            run("ffmpeg-concat", "-y", "-f", "concat", "-safe", "0", "-i",
                    list.toString(), "-c", "copy",
                    "-metadata", "title=" + plan.path("title").asText(""), master.toString());
            byte[] bytes = Files.readAllBytes(master);
            String key = workspaceId + "/" + projectId + "/master/" + UUID.randomUUID() + ".mp4";
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                storage.put(key, in, bytes.length, "video/mp4");
            }
            return toAssetResponse(assets.createOutput(workspaceId, projectId, null, null, "master",
                    key, "video/mp4", null, null, null, null));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("成片合成失败", e);
        } finally {
            if (work != null) {
                try {
                    Files.walk(work).sorted(Comparator.reverseOrder()).forEach(p -> {
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

    /** 单段编码：统一 1280x720/fps/yuv420p + 静音 AAC 轨；fade 时加淡入淡出。 */
    private void encodeSegment(Path raw, Path out, MediaClip c, int fps, boolean fade, double shotDur)
            throws IOException, InterruptedException {
        String vf = "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,"
                + "fps=" + fps + ",format=yuv420p";
        if (fade) {
            double fadeD = Math.min(0.25, shotDur / 4);
            vf += ",fade=t=in:st=0:d=" + fadeD + ",fade=t=out:st="
                    + Math.max(0, shotDur - fadeD) + ":d=" + fadeD;
        }
        List<String> args = new ArrayList<>(List.of("-y"));
        if (c.video()) {
            args.addAll(List.of("-i", raw.toString()));
        } else {
            args.addAll(List.of("-loop", "1", "-t", String.valueOf(shotDur), "-i", raw.toString()));
        }
        args.addAll(List.of(
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", vf,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "aac", "-b:a", "96k", "-shortest",
                out.toString()));
        run("ffmpeg-seg", args.toArray(new String[0]));
    }

    private List<MediaClip> orderedMedia(UUID workspaceId, JsonNode plan, List<UUID> shotIds) {
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

    private void run(String tag, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException(tag + " 超时");
        }
        if (p.exitValue() != 0) {
            log.warn("{} 失败: {}", tag,
                    out.length() > 500 ? out.substring(out.length() - 500) : out);
            throw new IllegalStateException(tag + " 执行失败（exit=" + p.exitValue() + "）");
        }
    }

    private static boolean isVideo(Asset a) {
        String m = a.mime() == null ? "" : a.mime();
        return m.startsWith("video/");
    }

    private AssetResponse toAssetResponse(Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.kind(), a.mime(),
                a.width(), a.height(), a.createdAt());
    }

    private record MediaClip(String assetKey, boolean video, double durationSec) {
    }
}
