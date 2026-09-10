package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.billing.QuotaService;
import studio.weaveora.infra.obs.Metrics;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.identity.domain.User;
import studio.weaveora.identity.domain.UserRepository;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.infra.ws.JobWsHandler;
import studio.weaveora.job.api.CreateJobRequest;
import studio.weaveora.job.api.JobView;
import studio.weaveora.job.domain.GenerationJob;
import studio.weaveora.job.domain.GenerationJobRepository;
import studio.weaveora.job.domain.WorkerNode;
import studio.weaveora.job.domain.WorkerNodeRepository;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.BriefSnapshot;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.project.domain.StyleTemplate;
import studio.weaveora.project.domain.StyleTemplateRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;
import studio.weaveora.shared.api.ErrorResponse;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Job 编排（§17.5/§20.2）：确认闸门 → 建 queued 任务 → worker 出站 claim 认领执行 → 回执终态。
 * 额度：MVP billing.simplified（§22.2 配额常量校验，无 wallet/ledger）。
 * worker 回执带 cancel_requested 时以 cancelled 落终态。
 */
@Service
@EnableScheduling
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final UUID PRESET_STILL = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PRESET_CLIP = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final List<String> TERMINAL = List.of("succeeded", "failed", "cancelled");

    /** 画面比例 → SDXL/视频 标准尺寸（64 对齐；16:9/9:16 兼顾竖屏） */
    private static final java.util.Map<String, int[]> ASPECT_DIMS = java.util.Map.of(
            "1:1", new int[]{1024, 1024},
            "3:2", new int[]{1152, 768},
            "2:3", new int[]{768, 1152},
            "16:9", new int[]{1280, 704},
            "9:16", new int[]{704, 1280});

    private static int[] dimsFor(String aspect) {
        int[] d = ASPECT_DIMS.get(aspect == null ? "1:1" : aspect);
        return d == null ? new int[]{1024, 1024} : d;
    }

    /** 风格注入：模板前缀+原文+后缀；负面词与原文合并（优先模板）。 */
    private static String styledPositive(StyleTemplate st, String raw) {
        if (st == null) return raw;
        String prefix = st.promptPrefix() == null ? "" : st.promptPrefix();
        String suffix = st.promptSuffix() == null ? "" : st.promptSuffix();
        if (prefix.isEmpty() && suffix.isEmpty()) return raw;
        String base = raw == null ? "" : raw;
        return prefix + base + suffix;
    }

    private static String styledNegative(StyleTemplate st, String raw) {
        if (st == null) return raw == null ? "" : raw;
        String neg = st.negative();
        if (neg == null || neg.isEmpty()) return raw == null ? "" : raw;
        String base = raw == null ? "" : raw.trim();
        if (base.isEmpty()) return neg;
        String sep = (neg.endsWith(",") || neg.endsWith("，")) ? " " : ", ";
        return neg + sep + base;
    }

    /** 任务可审计性：payload 内记录实际送入引擎的版本号与正词 MD5（P3：用 vN 的哪句话生成可查）。 */
    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void stampRevisionMeta(ObjectNode payload, int revisionNo, String finalPositive) {
        payload.put("revision_no", revisionNo);
        payload.put("prompt_md5", md5Hex(finalPositive));
    }

    private StyleTemplate loadStyle(ProjectSnapshot project) {
        if (project.styleTemplateId() == null) return null;
        return styleRepo.findById(project.styleTemplateId()).orElse(null);
    }

    private final GenerationJobRepository jobs;
    private final WorkerNodeRepository nodes;
    private final AssetService assets;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final JobWsHandler ws;
    private final studio.weaveora.director.PlanReader planReader;
    private final studio.weaveora.asset.domain.AssetRepository assetRepo;
    private final QuotaService quota;
    private final Metrics metrics;
    private final StyleTemplateRepository styleRepo;
    private final studio.weaveora.engine.EngineSettingsService engineSettings;
    private final UserRepository users;
    private final String adminEmail;
    private final int motionFramesMin;
    private final int motionFramesMax;

    public JobService(GenerationJobRepository jobs, WorkerNodeRepository nodes, AssetService assets,
                      StoragePort storage, ProjectContextPort projects, WorkspaceGuard guard, JobWsHandler ws,
                      studio.weaveora.director.PlanReader planReader,
                      studio.weaveora.asset.domain.AssetRepository assetRepo,
                      QuotaService quota, Metrics metrics, StyleTemplateRepository styleRepo,
                      studio.weaveora.engine.EngineSettingsService engineSettings,
                      UserRepository users,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.access.admin-email:sysou.com@outlook.com}") String adminEmail,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.video.motion-frames-min:32}") int motionFramesMin,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.video.motion-frames-max:96}") int motionFramesMax) {
        this.jobs = jobs;
        this.nodes = nodes;
        this.assets = assets;
        this.storage = storage;
        this.projects = projects;
        this.guard = guard;
        this.ws = ws;
        this.planReader = planReader;
        this.assetRepo = assetRepo;
        this.quota = quota;
        this.metrics = metrics;
        this.styleRepo = styleRepo;
        this.engineSettings = engineSettings;
        this.users = users;
        this.adminEmail = adminEmail == null ? "" : adminEmail;
        this.motionFramesMin = motionFramesMin;
        this.motionFramesMax = motionFramesMax;
    }

    /** 回收卡死 running 任务（默认 30min 无完成即失败，可重试） */
    @Scheduled(fixedDelayString = "${weaveora.job.reaper-ms:300000}")
    @Transactional
    public void reapStaleRunning() {
        java.time.OffsetDateTime cut = java.time.OffsetDateTime.now()
                .minusMinutes(15);
        int n = jobs.markStaleRunning(cut, java.time.OffsetDateTime.now());
        if (n > 0) {
            log.warn("reaped {} stale running jobs", n);
        }
    }

    // ---------- 对外：创建 / 查询 / 取消 ----------

    @Transactional
    public List<JobView> create(UUID userId, UUID workspaceId, UUID projectId, CreateJobRequest req) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (req.kind() == null || !List.of("still", "clip").contains(req.kind())) {
            throw new BizException(ErrorCode.VALIDATION, "kind 必须为 still|clip");
        }
        if (project.approvedRevisionId() == null || !project.approvedRevisionId().equals(req.revisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先确认该方案（未确认不可生成）");
        }
        BriefSnapshot ignored = null;
        // 读取 revision plan（导演层产物）构造 payload
        JsonNode plan = planReader.revisionPlan(req.revisionId());
        String planMode = plan.path("mode").asText("image");
        int revisionNo = planReader.revisionNo(req.revisionId());
        // 风格模板（W 项目风格：前缀/后缀/负面词注入出图与出视频）
        StyleTemplate style = null;
        if (project.styleTemplateId() != null) {
            style = styleRepo.findById(project.styleTemplateId()).orElse(null);
        }
        // W4 一致性锚定：逐镜解析（方案内标注主体的参考图优先，见 resolveRefs）
        // 引擎路由（用户设置）：本批次按 kind 决定 gpu|cloud
        String engineRoute = engineSettings.resolveEngine(userId, req.kind());

        List<GenerationJob> created = new ArrayList<>();
        if ("video".equals(planMode)) {
            List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(), req.kind());
            if (shotIds.isEmpty()) {
                throw new BizException(ErrorCode.SHOT_NOT_APPROVED, "没有已确认的镜头可生成");
            }
            if ("clip".equals(req.kind())) {
                int secs = 0;
                for (UUID sid : shotIds) {
                    JsonNode sh = shotOf(plan, sid);
                    secs += (int) Math.ceil(sh == null ? 3 : sh.path("duration_sec").asDouble(3));
                }
                quota.checkClipSeconds(userId, secs);
            } else {
                // P2：运镜镜头可能有多关键帧 → 按帧数计额度
                int stills = 0;
                for (UUID sid : shotIds) {
                    stills += keyframeCount(shotOf(plan, sid));
                }
                quota.checkStills(userId, stills);
            }
            for (UUID shotId : shotIds) {
                JsonNode shot = shotOf(plan, shotId);
                if (shot == null) continue;
                RefCtx refs = resolveRefs(plan, shot, userId, workspaceId, projectId, req.revisionId());
                long seed = shot.path("seed").asLong(0) == 0 ? randomSeed() : shot.path("seed").asLong(0);
                List<JsonNode> frames = keyframesOf(shot);
                if ("still".equals(req.kind()) && frames.size() > 1) {
                    // P2 运镜关键帧：每帧一个 still 任务（同 seed 保镜头内连贯）
                    for (int fi = 0; fi < frames.size(); fi++) {
                        JsonNode kf = frames.get(fi);
                        String raw = kf.path("positive_prompt").asText(shot.path("positive_prompt").asText(""));
                        ObjectNode payload = videoShotPayload("still", plan, shot, req.revisionId(), shotId,
                                revisionNo, raw, seed, project, style, refs);
                        payload.put("keyframe_index", fi);
                        payload.put("keyframe_count", frames.size());
                        payload.put("frame_label", frameLabel(kf, fi, frames.size()));
                        if (kf.hasNonNull("shot_size")) payload.put("shot_size", kf.path("shot_size").asText());
                        if (kf.hasNonNull("camera_move")) payload.put("camera_move", kf.path("camera_move").asText());
                        if (kf.hasNonNull("composition")) payload.put("composition", kf.path("composition").asText());
                        created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                                PRESET_STILL, "still", payload, userId, engineRoute));
                    }
                    continue;
                }
                ObjectNode payload = videoShotPayload(req.kind(), plan, shot, req.revisionId(), shotId,
                        revisionNo, shot.path("positive_prompt").asText(""), seed, project, style, refs);
                if ("clip".equals(req.kind())) {
                    // motion 帧数：可显式指定（范围校验）
                    if (req.frames() != null) {
                        int f = req.frames();
                        if (f < motionFramesMin || f > motionFramesMax) {
                            throw new BizException(ErrorCode.VALIDATION,
                                    "运动帧数须在 " + motionFramesMin + "–" + motionFramesMax + " 之间");
                        }
                        payload.put("frames", f);
                    }
                    // W5 两段式闸门：motion 需要该镜已确认的关键帧（still 产物）作首帧
                    List<studio.weaveora.asset.domain.Asset> kfAssets =
                            assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "still");
                    if (kfAssets.isEmpty()) {
                        throw new BizException(ErrorCode.VALIDATION, "第 " + shot.path("shot_no").asInt()
                                + " 镜尚无关键帧，请先生成 still（两段式 §11.3）");
                    }
                    studio.weaveora.asset.domain.Asset first = pickKeyframeAsset(kfAssets, 0);
                    payload.put("keyframeKey", first.storageKey());
                    // P2：多关键帧镜头把末帧作为尾帧引导（引擎支持时生效）
                    if (frames.size() > 1) {
                        studio.weaveora.asset.domain.Asset last = pickKeyframeAsset(kfAssets, frames.size() - 1);
                        if (last != null && !last.id().equals(first.id())) {
                            payload.put("tailKey", last.storageKey());
                        }
                    }
                }
                created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                        "clip".equals(req.kind()) ? PRESET_CLIP : PRESET_STILL, req.kind(), payload, userId,
                        engineRoute));
            }
        } else {
            if ("clip".equals(req.kind())) {
                throw new BizException(ErrorCode.VALIDATION, "图片项目只能生成 still");
            }
            int count = req.count() == null ? 1 : req.count();
            if (count != 1 && count != 2 && count != 4) {
                throw new BizException(ErrorCode.VALIDATION, "图片张数须为 1/2/4（§7.5）");
            }
            quota.checkStills(userId, count);
            RefCtx refs = resolveRefs(plan, null, userId, workspaceId, projectId, req.revisionId());
            for (int i = 0; i < count; i++) {
                ObjectNode payload = mapper().createObjectNode();
                payload.put("kind", "still");
                payload.put("mode", "image");
                payload.put("revisionId", req.revisionId().toString());
                String pos = styledPositive(style, plan.path("positive_prompt").asText(""));
                if (!refs.anchor().isBlank()) pos = pos + refs.anchor();
                payload.put("positive_prompt", pos);
                payload.put("negative_prompt", styledNegative(style, plan.path("negative_prompt").asText("")));
                stampRevisionMeta(payload, revisionNo, pos);
                JsonNode params = plan.path("params");
                com.fasterxml.jackson.databind.node.ObjectNode pnode =
                        params != null && params.isObject()
                                ? (com.fasterxml.jackson.databind.node.ObjectNode) params.deepCopy()
                                : mapper().createObjectNode();
                // 画面比例决定生成尺寸（LLM 常给 1024×1024，覆盖为项目比例）
                int[] dd = dimsFor(project.aspectRatio());
                pnode.put("width", dd[0]).put("height", dd[1]);
                payload.put("aspect_ratio", project.aspectRatio());
                payload.set("params", pnode);
                payload.put("seed", randomSeed());
                payload.put("title", plan.path("title").asText(""));
                attachRefs(payload, refs);
                GenerationJob job = createOne(workspaceId, projectId, req.revisionId(), null,
                        PRESET_STILL, "still", payload, userId, engineRoute);
                created.add(job);
            }
        }
        log.info("jobs created project={} count={} kind={}", projectId, created.size(), req.kind());
        return created.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<JobView> listByProject(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        return jobs.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobView get(UUID userId, UUID workspaceId, UUID jobId) {
        guard.requireMember(userId, workspaceId);
        return toView(jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在或不在本工作区")));
    }

    @Transactional
    public JobView cancel(UUID userId, UUID workspaceId, UUID jobId) {
        guard.requireMember(userId, workspaceId);
        GenerationJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在或不在本工作区"));
        if (TERMINAL.contains(job.state())) {
            throw new BizException(ErrorCode.JOB_NOT_CANCELLABLE, "任务已进入终态，不可取消");
        }
        // 运行中/排队：立即取消为终态（worker 迟到回执会被忽略）；卡住不再阻塞队列
        job.cancel();
        emit(job, Map.of("type", "job.cancelled"));
        metrics.jobCancelled();
        return toView(jobs.save(job));
    }

    // ---------- 对外：失败/取消任务的重试与删除 ----------

    /**
     * 重试（§20.2：failed → retry 生成<b>新</b> job，不改历史）：
     * 仅 failed | cancelled 可重试。默认复用原任务 revision/shot/kind 重新入队；
     * 但若项目“当前确认稿”已前进到更新版本（用户改镜并重新确认），新任务必须<b>重新锚定到当前
     * 确认稿</b>的对应镜头并重建 payload（取最新 positive_prompt），而不是复制旧版本快照——
     * 否则用户改的提示词永远到不了出图引擎（复现：女儿国项目 v2–v7 反复改镜后重跑仍出 v1 画面）。
     */
    @Transactional
    public List<JobView> retry(UUID userId, UUID workspaceId, UUID projectId, List<UUID> jobIds) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        List<JobView> created = new ArrayList<>();
        for (UUID jobId : jobIds) {
            GenerationJob old = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在或不在本工作区"));
            if (!old.projectId().equals(projectId)) {
                throw new BizException(ErrorCode.NOT_FOUND, "任务不属于该项目");
            }
            if (!List.of("failed", "cancelled").contains(old.state())) {
                throw new BizException(ErrorCode.VALIDATION, "仅失败/已取消的任务可重试");
            }
            Retarget t = repointToCurrentApproved(old, project, userId);
            GenerationJob neu = createOne(old.workspaceId(), old.projectId(), t.revisionId(), t.shotId(),
                    old.modelPresetId(), old.kind(), reshuffleSeed(t.payload()), userId, old.engineRoute());
            if (!t.revisionId().equals(old.revisionId())) {
                log.info("job retry re-anchored job={} oldRevision={} -> approvedRevision={} shot={} kind={}",
                        jobId, old.revisionId(), t.revisionId(), t.shotId(), old.kind());
            }
            created.add(toView(neu));
        }
        log.info("jobs retried project={} count={}", projectId, created.size());
        return created;
    }

    /** 任务重生成（含已成功的）：单条入队为新 job，随机新 seed；引擎按当前用户设置路由。
     * 与 retry 同策略：若项目“当前确认稿”已前进到更新版本，则重新锚定到确认稿对应镜头取最新
     * prompt（避免“改词后重跑仍出旧画面”——女儿国 v2–v7 复盘）。 */
    @Transactional
    public JobView rerun(UUID userId, UUID workspaceId, UUID jobId) {
        guard.requireMember(userId, workspaceId);
        GenerationJob old = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在或不在本工作区"));
        if (!TERMINAL.contains(old.state())) {
            throw new BizException(ErrorCode.VALIDATION, "任务运行中/排队，先取消再重生成");
        }
        ProjectSnapshot project = projects.require(userId, workspaceId, old.projectId());
        String route = engineSettings.resolveEngine(userId, old.kind());
        Retarget t = repointToCurrentApproved(old, project, userId);
        GenerationJob neu = createOne(old.workspaceId(), old.projectId(), t.revisionId(), t.shotId(),
                old.modelPresetId(), old.kind(), reshuffleSeed(t.payload()), userId, route);
        if (!t.revisionId().equals(old.revisionId())) {
            log.info("job rerun re-anchored job={} oldRevision={} -> approvedRevision={} shot={} kind={}",
                    jobId, old.revisionId(), t.revisionId(), t.shotId(), old.kind());
        }
        log.info("job rerun id={} -> new {} route={}", old.id(), neu.id(), route);
        return toView(neu);
    }

    /** 复制 payload 并替换 seed，使重跑/重生成得到不同结果。 */
    private static JsonNode reshuffleSeed(JsonNode payload) {
        ObjectNode cp = payload == null || !payload.isObject()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : (ObjectNode) payload.deepCopy();
        cp.put("seed", randomSeed());
        return cp;
    }

    /** 重试锚定结果：可能重指向“当前确认稿”的新 revision/shot 与重建后的 payload。 */
    private record Retarget(UUID revisionId, UUID shotId, JsonNode payload) {
    }

    /**
     * 版本未前进 → 原样重试；确认稿已前进 → 按当前确认稿对应镜头重建 payload（video/still 与 image/still），
     * 重建失败则安全回退旧 payload 并告警（不阻塞重试）。clip 不自动改锚：motion 依赖该镜关键帧，改镜后应重新生成。
     */
    private Retarget repointToCurrentApproved(GenerationJob old, ProjectSnapshot project, UUID userId) {
        UUID approvedId = project.approvedRevisionId();
        if (approvedId == null || approvedId.equals(old.revisionId())) {
            return new Retarget(old.revisionId(), old.shotId(), old.payload());
        }
        JsonNode plan;
        try {
            plan = planReader.revisionPlan(approvedId);
        } catch (RuntimeException e) {
            log.warn("job retry re-anchor: 读取确认稿 {} 失败，沿用旧任务 payload: {}", approvedId, e.getMessage());
            return new Retarget(old.revisionId(), old.shotId(), old.payload());
        }
        String planMode = plan.path("mode").asText("image");
        ObjectNode payload = old.payload() instanceof ObjectNode o ? o.deepCopy() : null;
        if (payload == null) {
            return new Retarget(old.revisionId(), old.shotId(), old.payload());
        }
        if ("video".equals(planMode) && "still".equals(old.kind())) {
            int no = planReader.shotNoOf(old.shotId());
            JsonNode shot = null;
            for (JsonNode s : plan.path("shots")) {
                if (s.path("shot_no").asInt() == no) {
                    shot = s;
                    break;
                }
            }
            if (shot == null) {
                log.warn("job retry re-anchor: 确认稿无 shot_no={}，沿用旧 payload", no);
                return new Retarget(old.revisionId(), old.shotId(), old.payload());
            }
            // 定位确认稿上同 shot_no 且已确认的镜头行 id（沿用 keyframe/资产归属到新镜头）
            UUID shotId = old.shotId();
            for (UUID sid : planReader.approvedShotIds(approvedId)) {
                if (planReader.shotNoOf(sid) == no) {
                    shotId = sid;
                    break;
                }
            }
            payload.put("revisionId", approvedId.toString());
            payload.put("shotId", shotId.toString());
            StyleTemplate st = loadStyle(project);
            String pos = styledPositive(st, shot.path("positive_prompt").asText(""));
            RefCtx refs = resolveRefs(plan, shot, userId, old.workspaceId(), old.projectId(), approvedId);
            if (!refs.anchor().isBlank()) pos = pos + refs.anchor();
            payload.put("positive_prompt", pos);
            payload.put("negative_prompt", styledNegative(st, shot.path("negative_prompt").asText("")));
            payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
            payload.remove("referenceAssetIds");
            payload.remove("referenceKeys");
            attachRefs(payload, refs);
            stampRevisionMeta(payload, planReader.revisionNo(approvedId), pos);
            return new Retarget(approvedId, shotId, payload);
        }
        if ("image".equals(planMode) && "still".equals(old.kind())) {
            payload.put("revisionId", approvedId.toString());
            StyleTemplate st = loadStyle(project);
            String pos = styledPositive(st, plan.path("positive_prompt").asText(""));
            RefCtx refs = resolveRefs(plan, null, userId, old.workspaceId(), old.projectId(), approvedId);
            if (!refs.anchor().isBlank()) pos = pos + refs.anchor();
            payload.put("positive_prompt", pos);
            payload.put("negative_prompt", styledNegative(st, plan.path("negative_prompt").asText("")));
            payload.remove("referenceAssetIds");
            payload.remove("referenceKeys");
            attachRefs(payload, refs);
            stampRevisionMeta(payload, planReader.revisionNo(approvedId), pos);
            return new Retarget(approvedId, old.shotId(), payload);
        }
        log.warn("job retry re-anchor: kind={} planMode={} 不支持自动改锚，沿用旧 payload（改镜后请重新发起生成）",
                old.kind(), planMode);
        return new Retarget(old.revisionId(), old.shotId(), old.payload());
    }

    /** 删除所选 failed/cancelled 任务记录（§20.2；级联清掉其孤儿资产再删行，避免 FK 冲突）。 */
    @Transactional
    public int delete(UUID userId, UUID workspaceId, UUID projectId, List<UUID> jobIds) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        int removed = 0;
        for (UUID jobId : jobIds) {
            GenerationJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId).orElse(null);
            if (job == null || !job.projectId().equals(projectId)) {
                continue;
            }
            if (!List.of("failed", "cancelled").contains(job.state())) {
                continue;
            }
            // 该任务若有已落库产物（如取消竞态/半程失败遗留），先删资产行与文件再删 Job
            for (studio.weaveora.asset.domain.Asset kid : assetRepo.findByJobIdAndWorkspaceId(jobId, workspaceId)) {
                try {
                    storage.delete(kid.storageKey());
                    if (kid.thumbKey() != null && !kid.thumbKey().isBlank()) {
                        storage.delete(kid.thumbKey());
                    }
                } catch (RuntimeException ignore) {
                    // 文件缺失不阻塞
                }
                assetRepo.delete(kid);
            }
            jobs.delete(job);
            removed++;
        }
        if (removed > 0) {
            log.info("jobs deleted project={} count={}", projectId, removed);
        }
        return removed;
    }

    // ---------- 管理员：任务队列 ----------

    private boolean isAdminJob(UUID userId) {
        if (userId == null || adminEmail.isBlank()) return false;
        return users.findById(userId).map(User::email)
                .map(em -> em.equalsIgnoreCase(adminEmail)).orElse(false);
    }

    /** 全部 queued/running 任务（管理员） */
    @Transactional(readOnly = true)
    public List<JobView> adminQueue(UUID userId) {
        if (!isAdminJob(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可查看任务队列");
        }
        return jobs.findByStateInOrderByCreatedAtAsc(List.of("queued", "running")).stream()
                .map(this::toView).toList();
    }

    /** 管理员手工让超长任务失败（解除队列阻塞） */
    @Transactional
    public int adminFail(UUID userId, UUID jobId) {
        if (!isAdminJob(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作任务队列");
        }
        GenerationJob job = jobs.findById(jobId)
                .filter(j -> List.of("queued", "running").contains(j.state())).orElse(null);
        if (job == null) return 0;
        job.fail("ADMIN_FAIL", "管理员手工终止（避免阻塞队列）");
        emit(jobs.save(job), Map.of("type", "job.failed", "code", "ADMIN_FAIL"));
        return 1;
    }

    // ---------- 内部：节点与认领 ----------

    @Transactional
    public UUID registerNode(String name, UUID workspaceId, JsonNode capabilities) {
        String safe = name == null ? "worker-" + ThreadLocalRandom.current().nextInt(100000, 999999) : name;
        WorkerNode n = nodes.findByName(safe).orElseGet(() -> {
            WorkerNode created = WorkerNode.register(safe, workspaceId, capabilities);
            return nodes.save(created);
        });
        n.heartbeat();
        if (workspaceId != null) {
            // BYO 节点只服务自己工作区
        }
        nodes.save(n);
        return n.id();
    }

    @Transactional
    public void heartbeat(UUID nodeId) {
        WorkerNode n = node(nodeId);
        n.heartbeat();
        nodes.save(n);
    }

    @Transactional
    public Map<String, Object> claim(UUID nodeId) {
        WorkerNode n = node(nodeId);
        UUID scope = n.workspaceId(); // NULL = 节点池 → 任意工作区
        // 引擎路由匹配：节点能力 engine（缺省 gpu）只认领同引擎任务
        String nodeEngine = n.capabilities() == null ? "gpu"
                : n.capabilities().path("engine").asText("gpu");
        for (GenerationJob candidate : jobs.findQueuedForClaim()) {
            if (scope != null && !scope.equals(candidate.workspaceId())) {
                continue;
            }
            if (!nodeEngine.equals(candidate.engineRoute())) {
                continue;
            }
            int updated = jobs.claim(candidate.id(), nodeId.toString(), OffsetDateTime.now());
            if (updated == 1) {
                GenerationJob running = jobs.findById(candidate.id()).orElseThrow();
                emit(running, Map.of("type", "job.queued", "state", "running"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("job", workerJobView(running));
                return out;
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("job", null);
        return empty;
    }

    @Transactional
    public void progress(UUID jobId, int progress, String stage) {
        GenerationJob job = requireRunning(jobId);
        job.progress(progress, stage);
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "job.progress");
        evt.put("progress", progress);
        evt.put("stage", stage == null ? "" : stage);
        emit(jobs.save(job), evt);
    }

    @Transactional
    public List<AssetResponse> complete(UUID jobId, List<CompleteAsset> items) {
        GenerationJob job = requireRunning(jobId);
        List<AssetResponse> created = new ArrayList<>();
        if (job.cancelRequested()) {
            job.cancel();
            emit(jobs.save(job), Map.of("type", "job.cancelled"));
            metrics.jobCancelled();
            throw new BizException(ErrorCode.JOB_NOT_CANCELLABLE, "任务已请求取消");
        }
        job.succeed();
        jobs.save(job);
        metrics.jobSucceeded();
        String kind = "clip".equals(job.kind()) ? "clip" : "still";
        for (CompleteAsset a : items) {
            AssetResponse resp = toAssetResponse(assets.createOutput(
                    job.workspaceId(), job.projectId(), job.id(), job.shotId(), kind,
                    a.key(), a.mime(), a.width(), a.height(), a.seed(), a.durationMs()));
            created.add(resp);
        }
        emit(job, Map.of("type", "job.succeeded", "assets", items.size()));
        return created;
    }

    @Transactional
    public void fail(UUID jobId, String code, String message) {
        GenerationJob job = requireRunning(jobId);
        if (job.cancelRequested()) {
            job.cancel();
            emit(jobs.save(job), Map.of("type", "job.cancelled"));
            metrics.jobCancelled();
            return;
        }
        job.fail(code == null ? "WORKER_ERROR" : code, message);
        emit(jobs.save(job), Map.of("type", "job.failed", "code", job.errorCode()));
        metrics.jobFailed();
    }

    @Transactional
    public String storeWorkerFile(UUID jobId, byte[] data, String mime, String suffix) {
        GenerationJob job = requireRunning(jobId);
        String key = job.workspaceId() + "/" + job.projectId() + "/" + job.id() + "/"
                + UUID.randomUUID() + "." + suffix;
        storage.put(key, new java.io.ByteArrayInputStream(data), data.length, mime);
        return key;
    }

    // ---------- W4 一致性锚定 ----------

    record RefCtx(List<String> ids, List<String> keys, String anchor) {
        static RefCtx empty() { return new RefCtx(List.of(), List.of(), ""); }
    }

    /**
     * 参考图解析（主体绑定）：
     * ① 方案内 `referenceAssets=[{assetId, subject}]`（参考图面板标注主体后随方案保存）优先：
     *    - 带 subject 的仅当该镜文本（action/zh/positive_prompt）提到该主体时绑定；无 subject 的始终绑定；
     *    - 该镜什么都没提到则带回全部（避免空锚定）；
     *    - 生成 anchor 文案追加到正词（人物形象以参考图为准）。
     * ② 否则退回 brief 显式挂图 / 项目最新参考图（旧行为）。
     */
    private RefCtx resolveRefs(JsonNode plan, JsonNode shot, UUID userId, UUID workspaceId,
                               UUID projectId, UUID revisionId) {
        JsonNode arr = plan == null ? null : plan.get("referenceAssets");
        if (arr != null && arr.isArray() && arr.size() > 0) {
            String text = (shot == null)
                    ? plan.path("positive_prompt").asText("") + " " + plan.path("prompt_zh").asText("")
                    : shot.path("action").asText("") + " " + shot.path("zh").asText("")
                      + " " + shot.path("positive_prompt").asText("");
            List<JsonNode> picked = new ArrayList<>();
            for (JsonNode b : arr) {
                if (b == null || !b.isObject() || b.path("assetId").asText("").isBlank()) continue;
                String subject = b.path("subject").asText("");
                if (subject.isBlank() || text.contains(subject)) picked.add(b);
            }
            if (picked.isEmpty()) {
                for (JsonNode b : arr) {
                    if (b != null && b.isObject() && !b.path("assetId").asText("").isBlank()) picked.add(b);
                }
            }
            List<UUID> ids = new ArrayList<>();
            for (JsonNode b : picked) {
                try { ids.add(UUID.fromString(b.path("assetId").asText())); } catch (IllegalArgumentException ignored) { }
            }
            List<studio.weaveora.asset.domain.Asset> found = assetRepo.findByIdInAndWorkspaceId(ids, workspaceId);
            List<String> keys = found.stream().map(studio.weaveora.asset.domain.Asset::storageKey).toList();
            List<String> okIds = found.stream().map(a -> a.id().toString()).toList();
            StringBuilder subj = new StringBuilder();
            for (JsonNode b : picked) {
                String s = b.path("subject").asText("");
                if (!s.isBlank() && subj.indexOf(s) < 0) {
                    if (subj.length() > 0) subj.append(", ");
                    subj.append(s);
                }
            }
            String anchor = okIds.isEmpty() ? "" : (subj.length() > 0
                    ? " The appearance of " + subj + " must strictly follow the provided reference image (identity, face and costume)."
                    : " The subject appearance must strictly follow the provided reference image.");
            return new RefCtx(okIds, keys, anchor);
        }
        RefCtx legacy = loadRefs(userId, workspaceId, projectId, revisionId);
        return new RefCtx(legacy.ids(), legacy.keys(), "");
    }

    private RefCtx loadRefs(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        try {
            UUID briefId = planReader.revisionBriefId(revisionId);
            BriefSnapshot brief = projects.requireBrief(userId, workspaceId, projectId, briefId);
            // ① brief 显式挂的参考图
            List<UUID> ids = new ArrayList<>();
            if (brief.constraints() != null && brief.constraints().has("referenceAssetIds")) {
                for (JsonNode n : brief.constraints().get("referenceAssetIds")) {
                    try { ids.add(UUID.fromString(n.asText())); } catch (IllegalArgumentException ignored) { }
                }
            }
            List<String> keys = new ArrayList<>();
            if (ids.isEmpty()) {
                // ② 兜底：项目最新上传的参考图（用户上传即作为参考）
                List<studio.weaveora.asset.domain.Asset> refs =
                        assetRepo.findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(projectId, workspaceId, "reference");
                if (!refs.isEmpty()) {
                    studio.weaveora.asset.domain.Asset a = refs.get(0);
                    ids.add(a.id());
                    keys.add(a.storageKey());
                }
            } else {
                keys = assetRepo.findByIdInAndWorkspaceId(ids, workspaceId).stream()
                        .map(studio.weaveora.asset.domain.Asset::storageKey)
                        .toList();
            }
            return new RefCtx(ids.stream().map(UUID::toString).toList(), keys, "");
        } catch (BizException e) {
            return RefCtx.empty(); // 引用缺失不阻塞出图（仅丢锚定）
        }
    }

    private void attachRefs(ObjectNode payload, RefCtx refs) {
        com.fasterxml.jackson.databind.node.ArrayNode ids = payload.putArray("referenceAssetIds");
        refs.ids().forEach(ids::add);
        com.fasterxml.jackson.databind.node.ArrayNode keys = payload.putArray("referenceKeys");
        refs.keys().forEach(keys::add);
    }

    /** 内部：按存储 key 读取参考图字节（worker 经 token 拉取，供 ComfyUI 上传/IP-Adapter）。 */
    @Transactional(readOnly = true)
    public studio.weaveora.infra.storage.StoragePort.StoredObject readAssetByKey(String storageKey) {
        var obj = storage.get(storageKey);
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资产文件不存在");
        }
        return obj;
    }

    // ---------- 内部工具 ----------

    private GenerationJob createOne(UUID workspaceId, UUID projectId, UUID revisionId, UUID shotId,
                                    UUID presetId, String kind, JsonNode payload, UUID userId,
                                    String engineRoute) {
        String idem = "j:" + workspaceId + ":" + projectId + ":" + revisionId + ":" + shotId + ":"
                + kind + ":" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        GenerationJob job = GenerationJob.create(workspaceId, projectId, revisionId, shotId, presetId,
                kind, idem, payload, userId);
        job.setEngineRoute(engineRoute);
        GenerationJob saved = jobs.save(job);
        emit(saved, Map.of("type", "job.queued", "state", "queued"));
        metrics.jobQueued();
        return saved;
    }

    private List<UUID> resolveVideoShots(UUID userId, UUID workspaceId, UUID projectId,
                                         UUID revisionId, UUID shotId, String kind) {
        // 镜头状态表在 director 侧；此处经由公开查询：approved 镜头才可生成。
        List<UUID> ids = new ArrayList<>();
        if (shotId != null) {
            ids.add(shotId);
        } else {
            // 需要镜头清单 → 委托 director 模块提供的只读服务
            ids.addAll(planReader.shotIds(revisionId));
        }
        List<UUID> allowed = planReader.approvedShotIds(revisionId);
        ids.removeIf(id -> !allowed.contains(id));
        return ids;
    }

    private JsonNode shotOf(JsonNode plan, UUID shotId) {
        // shot_drafts 行有 shot_no；revision plan.shots 也有 shot_no —— 用行号匹配
        int no = planReader.shotNoOf(shotId);
        if (plan.has("shots") && plan.get("shots").isArray()) {
            for (JsonNode s : plan.get("shots")) {
                if (s.path("shot_no").asInt() == no) return s;
            }
        }
        return null;
    }

    /** P2：该镜关键帧数（无 keyframes 或非法则按 1 张单帧）。 */
    private static int keyframeCount(JsonNode shot) {
        List<JsonNode> fs = keyframesOf(shot);
        return fs.size() > 1 ? fs.size() : 1;
    }

    private static List<JsonNode> keyframesOf(JsonNode shot) {
        List<JsonNode> out = new ArrayList<>();
        if (shot == null) return out;
        JsonNode kfs = shot.get("keyframes");
        if (kfs != null && kfs.isArray()) {
            for (JsonNode kf : kfs) {
                if (kf != null && kf.isObject()) out.add(kf);
            }
        }
        return out;
    }

    private static String frameLabel(JsonNode kf, int index, int total) {
        String label = kf.path("label").asText("");
        if (!label.isBlank()) return label;
        if (index == 0) return "起始帧";
        if (index == total - 1) return "结束帧";
        return "第" + (index + 1) + "帧";
    }

    /** 视频镜头 payload 公共构造（含 P3 的 revision_no/prompt_md5；正词可传关键帧词）。 */
    private ObjectNode videoShotPayload(String kind, JsonNode plan, JsonNode shot, UUID revisionId, UUID shotId,
                                        int revisionNo, String positiveRaw, long seed, ProjectSnapshot project,
                                        StyleTemplate style, RefCtx refs) {
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kind", kind);
        payload.put("mode", "video");
        payload.put("revisionId", revisionId.toString());
        payload.put("shotId", shotId.toString());
        payload.put("shot_no", shot.path("shot_no").asInt());
        String pos = styledPositive(style, positiveRaw);
        if (refs != null && !refs.anchor().isBlank()) pos = pos + refs.anchor();
        payload.put("positive_prompt", pos);
        payload.put("negative_prompt", styledNegative(style, shot.path("negative_prompt").asText("")));
        stampRevisionMeta(payload, revisionNo, pos);
        payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
        payload.put("fps", plan.path("edit_plan").path("fps").asInt(30));
        payload.put("seed", seed);
        String aspect = plan.path("aspect_ratio").asText(project.aspectRatio());
        payload.put("aspect_ratio", aspect);
        int[] dd = dimsFor(aspect);
        payload.set("params", mapper().createObjectNode().put("width", dd[0]).put("height", dd[1]));
        attachRefs(payload, refs);
        return payload;
    }

    /** P2：按关键帧序号选 still 产物（job payload.keyframe_index 标记帧号；无标记时首帧取最新、末帧取最早）。 */
    private studio.weaveora.asset.domain.Asset pickKeyframeAsset(
            List<studio.weaveora.asset.domain.Asset> candidates, int index) {
        for (studio.weaveora.asset.domain.Asset a : candidates) {
            if (a.jobId() == null) continue;
            GenerationJob j = jobs.findById(a.jobId()).orElse(null);
            if (j != null && j.payload() != null && j.payload().path("keyframe_index").asInt(-1) == index) {
                return a;
            }
        }
        return index == 0 ? candidates.get(0) : candidates.get(candidates.size() - 1);
    }

    private WorkerNode node(UUID nodeId) {
        return nodes.findById(nodeId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "worker 节点不存在"));
    }

    private GenerationJob requireRunning(UUID jobId) {
        GenerationJob job = jobs.findById(jobId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在"));
        if (!"running".equals(job.state())) {
            throw new BizException(ErrorCode.JOB_NOT_CANCELLABLE, "任务状态 " + job.state() + " 不可回执");
        }
        return job;
    }

    private void emit(GenerationJob job, Map<String, Object> event) {
        Map<String, Object> payload = new LinkedHashMap<>(event);
        payload.put("jobId", job.id().toString());
        payload.put("projectId", job.projectId().toString());
        payload.put("workspaceId", job.workspaceId().toString());
        payload.put("state", job.state());
        payload.putIfAbsent("progress", job.progress());
        payload.putIfAbsent("stage", job.stage() == null ? "" : job.stage());
        ws.push(job.projectId(), payload);
    }

    private JobView toView(GenerationJob j) {
        return new JobView(j.id(), j.projectId(), j.revisionId(), j.shotId(), j.kind(), j.state(),
                j.progress(), j.stage(), j.cancelRequested(), j.errorCode(), j.errorMessage(),
                j.modelPresetId(), j.payload(), j.createdAt());
    }

    private Map<String, Object> workerJobView(GenerationJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", j.id().toString());
        m.put("kind", j.kind());
        m.put("engineRoute", j.engineRoute());
        m.put("userId", j.createdBy() == null ? null : j.createdBy().toString());
        m.put("projectId", j.projectId().toString());
        m.put("workspaceId", j.workspaceId().toString());
        m.put("revisionId", j.revisionId() == null ? null : j.revisionId().toString());
        m.put("shotId", j.shotId() == null ? null : j.shotId().toString());
        m.put("payload", j.payload());
        return m;
    }

    private AssetResponse toAssetResponse(studio.weaveora.asset.domain.Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.kind(), a.mime(),
                a.width(), a.height(), a.createdAt());
    }

    private static long randomSeed() {
        return ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /** complete 请求中的资产元数据。 */
    public record CompleteAsset(String key, String mime, Integer width, Integer height, Long seed, Integer durationMs) {
    }
}
