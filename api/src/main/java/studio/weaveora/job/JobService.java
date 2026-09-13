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
import studio.weaveora.director.plan.AudioPlan;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final studio.weaveora.project.ShotLockService shotLocks;
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
    /**
     * 云引擎（云 API）的运动帧数上限：
     * 上限由**模型能出多少帧**决定（不是本机显存），典型 5s@30fps = 150 帧、最多可到 300。
     * 本机（GPU/ComfyUI）仍用 {@link #motionFramesMax}（3070Ti 显存口径）。
     */
    private final int motionFramesMaxCloud;
    private final int queuedTimeoutMin;   // queued 超时回收阈值（分钟）
    private final int runningTimeoutMin;  // running 超时回收阈值（分钟）

    public JobService(GenerationJobRepository jobs, WorkerNodeRepository nodes, AssetService assets,
                      StoragePort storage, ProjectContextPort projects, WorkspaceGuard guard, JobWsHandler ws,
                      studio.weaveora.director.PlanReader planReader,
                      studio.weaveora.asset.domain.AssetRepository assetRepo,
                      QuotaService quota, Metrics metrics, StyleTemplateRepository styleRepo,
                      studio.weaveora.engine.EngineSettingsService engineSettings,
                      studio.weaveora.project.ShotLockService shotLocks,
                      UserRepository users,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.access.admin-email:sysou.com@outlook.com}") String adminEmail,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.video.motion-frames-min:32}") int motionFramesMin,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.video.motion-frames-max:96}") int motionFramesMax,
                              @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.video.motion-frames-max-cloud:300}") int motionFramesMaxCloud,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.job.queued-timeout-minutes:1440}") int queuedTimeoutMin,
                      @org.springframework.beans.factory.annotation.Value(
                              "${weaveora.job.running-timeout-minutes:60}") int runningTimeoutMin) {
        this.jobs = jobs;
        this.shotLocks = shotLocks;
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
        this.motionFramesMaxCloud = motionFramesMaxCloud;
        this.queuedTimeoutMin = queuedTimeoutMin;
        this.runningTimeoutMin = runningTimeoutMin;
    }

    /**
     * 回收卡死的 running 任务。
     *
     * <p><b>只看 startedAt 是错的</b>（2026-09-13 线上：对口型跑到 5/8、worker 心跳正常，
     * 却在第 15 分钟被误杀）。正确口径：
     * <ul>
     *   <li>候选 = running 且 startedAt < now - runningTimeoutMin（默认 60min）；</li>
     *   <li>候选里**worker 仍活着**（lastSeenAt 在宽限期内）→ 视为长任务在跑，不回收；</li>
     *   <li>再叠一个**硬上限**（4×超时，最少 2h）：worker 一直不死但任务永远不结束的
     *       病态情况仍然要收掉，否则任务会永久占着 GPU。</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${weaveora.job.reaper-ms:300000}")
    @Transactional
    public void reapStaleRunning() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        java.time.OffsetDateTime cut = now.minusMinutes(runningTimeoutMin);
        java.time.OffsetDateTime hardCut =
                now.minusMinutes(Math.max((long) runningTimeoutMin * 4L, runningTimeoutMin + 60L));
        int n = 0;
        int keptAlive = 0;
        for (GenerationJob j : jobs.findRunningStartedBefore(cut)) {
            boolean pastHardCap = j.startedAt() == null || j.startedAt().isBefore(hardCut);
            boolean alive = workerAliveRecently(j.workerId(), now);
            if (!shouldReap(alive, pastHardCap)) {
                keptAlive++;
                continue;
            }
            String msg = pastHardCap
                    ? "执行超时（超过硬上限 " + Math.max((long) runningTimeoutMin * 4L, runningTimeoutMin + 60L)
                      + "min，worker 心跳=" + (alive ? "正常" : "失联") + "）"
                    : "执行超时（worker 无心跳完成）";
            if (jobs.failRunning(j.id(), msg, now) == 1) {
                n++;
            }
        }
        if (n > 0) {
            log.warn("reaped {} stale running jobs (>{} min)", n, runningTimeoutMin);
        }
        if (keptAlive > 0) {
            log.info("kept {} long-running jobs alive (worker 心跳正常)", keptAlive);
        }
        // queued 但已请求取消（历史遗留/异步取消）→ 直接终态，避免僵尸行永挂列表
        int c = jobs.markCancelledQueued(now);
        if (c > 0) {
            log.warn("finalized {} cancel-requested queued jobs as cancelled", c);
        }
        // queued 超时（长时间没有可用 worker，如 GPU 离线）→ failed(STALE_QUEUED)，用户可重试
        int q = jobs.markStaleQueued(now.minusMinutes(queuedTimeoutMin), now,
                "排队超时（无可用 worker）：请确认 GPU/云端节点在线后重试");
        if (q > 0) {
            log.warn("reaped {} stale queued jobs (>{} min)", q, queuedTimeoutMin);
        }
    }

    // ---------- 对外：创建 / 查询 / 取消 ----------

    @Transactional
    public List<JobView> create(UUID userId, UUID workspaceId, UUID projectId, CreateJobRequest req) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (req.kind() == null || !List.of("still", "clip", "voice", "bgm", "portrait", "lipsync").contains(req.kind())) {
            throw new BizException(ErrorCode.VALIDATION, "kind 必须为 still|clip|voice|bgm");
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
        // 引擎路由（用户设置）：本批次按 kind 决定 gpu|cloud；配音/配乐为自托管服务，固定走 gpu 节点
        if ("portrait".equals(req.kind())) {
            return createPortraitJob(workspaceId, projectId, req, plan, revisionNo, userId)
                    .stream().map(this::toView).toList();
        }
        if ("lipsync".equals(req.kind())) {
            return createLipsyncJobs(workspaceId, projectId, req, plan, revisionNo, userId)
                    .stream().map(this::toView).toList();
        }
        // 配音/配乐是自托管音频服务；**对口型也固定走本机 GPU**（音频驱动的后处理跑在 ComfyUI 工作流里，
        // 云端视频模型没有口型能力 —— 之前漏了这条，lipsync 被路由到云 → 掉进云图片分支报错）
        boolean audioKind = SELF_HOSTED_KINDS.contains(req.kind());
        String engineRoute = routeForKind(req.kind(), engineSettings.resolveEngine(userId, req.kind()));

        if (audioKind) {
            List<GenerationJob> audio = createAudioJobs(workspaceId, projectId, req, plan, revisionNo, userId);
            log.info("jobs created project={} count={} kind={}", projectId, audio.size(), req.kind());
            return audio.stream().map(this::toView).toList();
        }

        List<GenerationJob> created = new ArrayList<>();
        List<String> skippedShots = new ArrayList<>();   // P13：motion 跳过「尚无关键帧」的镜
        if ("video".equals(planMode)) {
            List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(), req.kind(),
                    req.shotNos(), Boolean.TRUE.equals(req.includeLocked()));
            if (shotIds.isEmpty()) {
                throw new BizException(ErrorCode.SHOT_NOT_APPROVED, emptyShotReason(workspaceId, projectId, req));
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
                        int lo = motionFramesMin;
                        int hi = motionFramesMaxFor(engineRoute, plan, userId);
                        if (f < lo || f > hi) {
                            throw new BizException(ErrorCode.VALIDATION,
                                    "运动帧数须在 " + lo + "–" + hi + " 之间"
                                            + ("cloud".equals(engineRoute) ? "（云模型口径，本机 GPU 另有一套）" : "（本机 GPU 显存口径）"));
                        }
                        payload.put("frames", f);
                    }
                    // W5 两段式闸门：motion 需要该镜的关键帧（still 产物）作首帧。
                    // P5.1：shot_drafts 每次 patch/确认都会重建（新 id），旧关键帧仍挂在旧 shot 行上
                    // → 当前镜无 still 时，按“同项目同镜号”回溯历史版本的关键帧（取最新）。
                    int shotNoVal = shot.path("shot_no").asInt();
                    // P6：优先按 (project, shot_no) 取（跨版本稳定）；再退当前 shot_id；最后跨版本 shot 行扫描
                    List<studio.weaveora.asset.domain.Asset> kfAssets = new ArrayList<>(
                            assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                                    projectId, workspaceId, shotNoVal, "still"));
                    boolean historical = false;
                    if (kfAssets.isEmpty()) {
                        kfAssets.addAll(assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(
                                shotId, workspaceId, "still"));
                    }
                    if (kfAssets.isEmpty()) {
                        int no = shotNoVal;
                        for (UUID sid : planReader.shotIdsByProjectAndShotNo(projectId, workspaceId, no)) {
                            if (sid.equals(shotId)) continue;
                            kfAssets.addAll(assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(
                                    sid, workspaceId, "still"));
                        }
                        if (!kfAssets.isEmpty()) {
                            kfAssets.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
                            historical = true;
                            log.info("motion keyframe fallback: project={} shot_no={} -> {} historical still(s), latest={}",
                                    projectId, no, kfAssets.size(), kfAssets.get(0).id());
                        }
                    }
                    if (kfAssets.isEmpty()) {
                        // P13：**跳过**还缺关键帧的镜，而不是整批报错 ——
                        // 否则用户必须等所有关键帧都出完才能做 motion，前面已生成的关键帧干等着，
                        // 一旦关键帧生成失败/中断就白烧生图费用（实测痛点）。
                        String miss = shot.path("shot_no").asText("?");
                        if (!created.isEmpty()) {
                            skippedShots.add(miss);
                            log.info("motion skip: project={} shot_no={} 无关键帧（继续其它镜）", projectId, miss);
                            continue;
                        }
                        skippedShots.add(miss);
                        continue;
                    }
                    studio.weaveora.asset.domain.Asset first = pickKeyframeAsset(kfAssets, 0);
                    historical = historical || (first.shotId() != null && !first.shotId().equals(shotId));
                    payload.put("keyframeKey", first.storageKey());
                    if (historical) {
                        payload.put("keyframeHistorical", true);
                        int kfRev = planReader.revisionNoOfShot(first.shotId());
                        if (kfRev > 0) payload.put("keyframeHistoricalRevisionNo", kfRev);
                    }
                    // P2：多关键帧镜头把末帧作为尾帧引导（引擎支持时生效）
                    if (frames.size() > 1) {
                        studio.weaveora.asset.domain.Asset last = pickKeyframeAsset(kfAssets, frames.size() - 1);
                        if (last != null && !last.id().equals(first.id())) {
                            payload.put("tailKey", last.storageKey());
                        }
                    }
                    // P13：分段生成 —— 校准后镜头时长可能超过「视频模型单次输出上限」
                    // （i2v 常见 5s），这时 shot.segments[] 有多段：**每段一个任务**，
                    // 帧数按段长折算（frames = 段长 × fps，夹在 motion-frames 上下限内），
                    // 段间续接优先用关键帧序列（段 i 首帧=关键帧 i、尾帧=关键帧 i+1）。
                    JsonNode segNode = shot.path("segments");
                    if (segNode.isArray() && segNode.size() > 1) {
                        int fpsVal = plan.path("edit_plan").path("fps").asInt(30);
                        int segCount = segNode.size();
                        for (int si = 0; si < segCount; si++) {
                            JsonNode seg = segNode.get(si);
                            double segDur = seg.path("duration_sec").asDouble(0);
                            if (segDur <= 0) continue;
                            ObjectNode sp = payload.deepCopy();
                            int f = (int) Math.round(segDur * Math.max(1, fpsVal));
                            // 上限按**引擎**取：云 = 模型口径，本机 = 显存口径
                            int segHi = motionFramesMaxFor(engineRoute, plan, userId);
                            f = Math.max(motionFramesMin, Math.min(segHi, f));
                            sp.put("frames", f);
                            sp.put("segment_index", si);
                            sp.put("segment_count", segCount);
                            sp.put("segment_start_sec", seg.path("start_sec").asDouble(0));
                            sp.put("segment_duration_sec", segDur);
                            if (frames.size() > 1) {
                                studio.weaveora.asset.domain.Asset sf =
                                        pickKeyframeAsset(kfAssets, Math.min(si, frames.size() - 1));
                                studio.weaveora.asset.domain.Asset sl =
                                        pickKeyframeAsset(kfAssets, Math.min(si + 1, frames.size() - 1));
                                if (sf != null) sp.put("keyframeKey", sf.storageKey());
                                if (sl != null && sf != null && !sl.id().equals(sf.id())) {
                                    sp.put("tailKey", sl.storageKey());
                                }
                            }
                            log.info("motion segmented: project={} shot_no={} seg={}/{} dur={}s frames={}",
                                    projectId, shot.path("shot_no").asInt(), si + 1, segCount, segDur, f);
                            created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                                    PRESET_CLIP, "clip", sp, userId, engineRoute));
                        }
                        continue;   // 已按段建完，不再建整镜任务
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
                payload.put("negative_prompt", negWithRefGuard(styledNegative(style, plan.path("negative_prompt").asText("")), refs));
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
        if ("clip".equals(req.kind()) && !skippedShots.isEmpty()) {
            if (created.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION, "第 " + String.join("、", skippedShots)
                        + " 镜尚无关键帧：请先为这些镜生成 still（其它镜可单独再跑 motion）");
            }
            log.info("motion partial: project={} created={} skipped={}", projectId, created.size(), skippedShots);
        }
        log.info("jobs created project={} count={} kind={}", projectId, created.size(), req.kind());
        return created.stream().map(this::toView).toList();
    }

    /**
     * P7 自托管音频任务：
     *  - voice（配音）：逐镜 narration → 一个 shot 一个任务（text/targetSec/voice/speed）
     *  - bgm（配乐）：整片一个任务（prompt 来自 plan.audio.music_mood + duration_sec）
     * 固定 engineRoute=gpu（自托管音频服务跑在 GPU 机器，云端节点不会认领）。
     */
    private List<GenerationJob> createAudioJobs(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                                JsonNode plan, int revisionNo, UUID userId) {
        List<GenerationJob> out = new ArrayList<>();
        if ("bgm".equals(req.kind())) {
            // P8：按配乐段落表取「需要生成的情绪」（同 mood 只生成一次，多段引用同一产物）
            List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
            if (cues.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION, "方案里没有配乐段落（audio.music / music_mood 均为空）");
            }
            double planDur = plan.path("duration_sec").asDouble(0);
            for (String mood : AudioPlan.distinctMoods(cues)) {
                double need = AudioPlan.generateDurationFor(cues, mood);
                if (need <= 0) {
                    need = planDur > 0 ? planDur : 30;
                }
                String prompt = (mood == null || mood.isBlank())
                        ? "cinematic instrumental score, emotional, no vocals"
                        : ("cinematic instrumental score, mood: " + mood + ", no vocals");
                ObjectNode payload = mapper().createObjectNode();
                payload.put("kind", "bgm");
                payload.put("preview", Boolean.TRUE.equals(req.preview()));
                payload.put("mode", plan.path("mode").asText("video"));
                payload.put("revisionId", req.revisionId().toString());
                payload.put("revision_no", revisionNo);
                payload.put("mood", mood == null ? "" : mood);   // 混音靠它把段落和产物对应起来
                payload.put("prompt", prompt);
                payload.put("duration_sec", Math.min(Math.max(need, 5), 180));
                payload.put("seed", randomSeed());
                stampRevisionMeta(payload, revisionNo, prompt);
                out.add(createOne(workspaceId, projectId, req.revisionId(), null, PRESET_STILL, "bgm",
                        payload, userId, "gpu"));
            }
            return out;
        }
        // voice：逐镜旁白
        List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(), "still",
                req.shotNos(), Boolean.TRUE.equals(req.includeLocked()));
        if (shotIds.isEmpty()) {
            throw new BizException(ErrorCode.SHOT_NOT_APPROVED, emptyShotReason(workspaceId, projectId, req));
        }
        for (UUID shotId : shotIds) {
            JsonNode shot = shotOf(plan, shotId);
            if (shot == null) continue;
            // P8：一镜可多段语音（旁白 + 角色台词），每段一个 job；
            //     音色用 AudioPlan.voiceFor 解析（narrations[].voice > voiceBindings[subject] > audio.voice > 默认）
            //     lineIndex 非空时只重建该段（供「单条重新生成」用），但 line_index 仍写原始下标
            List<AudioPlan.Line> lines = AudioPlan.lines(shot);
            if (lines.isEmpty()) continue;   // 没有语音的镜头跳过
            if (req.lineIndex() != null && (req.lineIndex() < 0 || req.lineIndex() >= lines.size())) {
                throw new BizException(ErrorCode.VALIDATION,
                        "第 " + shot.path("shot_no").asInt() + " 镜没有第 " + req.lineIndex() + " 段语音");
            }
            double shotDur = shot.path("duration_sec").asDouble(3);
            for (int k = 0; k < lines.size(); k++) {
                if (req.lineIndex() != null && req.lineIndex() != k) continue;
                AudioPlan.Line line = lines.get(k);
                // 本段可用窗口：优先 end_sec，否则到下一段起点（或镜头末尾）
                Double nextAt = (k + 1 < lines.size()) ? lines.get(k + 1).atSec() : null;
                double window = Math.max(0.5, line.windowSec(shotDur, nextAt));
                ObjectNode payload = mapper().createObjectNode();
                payload.put("kind", "voice");
                payload.put("preview", Boolean.TRUE.equals(req.preview()));
                payload.put("mode", "video");
                payload.put("revisionId", req.revisionId().toString());
                payload.put("revision_no", revisionNo);
                payload.put("shotId", shotId.toString());
                payload.put("shot_no", shot.path("shot_no").asInt());
                payload.put("line_index", k);
                payload.put("line_kind", line.kind());       // narration | dialogue
                payload.put("at_sec", line.atSec());         // 镜内起点（混音靠它摆放）
                if (line.hasEnd()) {
                    payload.put("end_sec", line.endSec());   // 镜内结束点（混音靠它裁切）
                }
                if (line.subject() != null) {
                    payload.put("subject", line.subject());
                }
                payload.put("text", line.text());
                String voice = AudioPlan.voiceFor(plan, line.subject(), line.voice());
                payload.put("voice", voice);
                // P9：clone:<id> → 把参考音**存储 key**（不是资产 id！）与转写文本一并下发。
                //     worker 拉参考音走的是 /internal/assets?key=storageKey（readAssetByKey），
                //     早期误传资产 UUID → worker 拿它当 storage key 查 → 404 "fetch ref asset … -> 404"。
                if (AudioPlan.isClone(voice)) {
                    AudioPlan.VoicePreset vp = AudioPlan.presetById(plan, AudioPlan.cloneId(voice));
                    if (vp == null) {
                        throw new BizException(ErrorCode.VALIDATION,
                                "克隆音色「" + AudioPlan.cloneId(voice) + "」不存在（可能已被删除），请重新绑定音色");
                    }
                    UUID presetAssetId;
                    try {
                        presetAssetId = UUID.fromString(vp.assetId());
                    } catch (IllegalArgumentException bad) {
                        throw new BizException(ErrorCode.VALIDATION,
                                "克隆音色「" + vp.name() + "」的样本引用已损坏，请重新录音色");
                    }
                    String storageKey = assetRepo.findByIdAndWorkspaceId(presetAssetId, workspaceId)
                            .map(studio.weaveora.asset.domain.Asset::storageKey)
                            .orElseThrow(() -> new BizException(ErrorCode.VALIDATION,
                                    "克隆音色「" + vp.name() + "」的样本文件已不存在"
                                            + "（可能被删除或已重录）。请到音频区重新录音色，并重新绑定到该角色"));
                    payload.put("refAssetKey", storageKey);
                    if (!vp.promptText().isBlank()) {
                        payload.put("refPromptText", vp.promptText());
                    }
                }
                payload.put("speed", AudioPlan.speedFor(plan, line.subject(), line.speed()));
                payload.put("target_sec", window);
                payload.put("seed", randomSeed());
                stampRevisionMeta(payload, revisionNo, line.text());
                out.add(createOne(workspaceId, projectId, req.revisionId(), shotId, PRESET_STILL, "voice",
                        payload, userId, "gpu"));
            }
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION,
                    "没有可配音的旁白/台词：请先在分镜里填写旁白（narration 或 narrations）");
        }
        return out;
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
            // 注意：不能直接沿袭 old.engineRoute() —— 若旧任务当初路由错了（例如 lipsync 被
            // 派到 cloud，云上没有口型工作流），重试会原样复现错误。自托管 kind 一律重算为 gpu，
            // 其它 kind 仍保留原执行面。
            GenerationJob neu = createOne(old.workspaceId(), old.projectId(), t.revisionId(), t.shotId(),
                    old.modelPresetId(), old.kind(), reshuffleSeed(t.payload()), userId,
                    routeForKind(old.kind(), old.engineRoute()));
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
        String route = routeForKind(old.kind(), engineSettings.resolveEngine(userId, old.kind()));
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

    /**
     * 重跑 voice/bgm：重新解析克隆音色参考音（refAssetKey），并清掉旧的错误字段。
     *
     * <p>为什么必须做：旧版本曾把**资产 UUID** 写成 {@code refAssetId}（worker 需要的是存储 key），
     * 且重跑时沿用旧 payload → worker 拿空参考音，TTS 静默兑底到自带参考音（用户听到“变标准女声”）。
     */
    private Retarget refreshAudioJobPayload(GenerationJob old, ProjectSnapshot project, UUID approvedId) {
        JsonNode raw = old.payload();
        if (!(raw instanceof ObjectNode src)) {
            return new Retarget(old.revisionId(), old.shotId(), raw);
        }
        ObjectNode payload = src.deepCopy();
        String voice = payload.path("voice").asText("");
        if (!AudioPlan.isClone(voice)) {
            return new Retarget(old.revisionId(), old.shotId(), payload);
        }
        // 先清掉历史错误字段，避免“看着有、其实用不上”
        payload.remove("refAssetId");
        UUID planRev = approvedId != null ? approvedId : old.revisionId();
        JsonNode plan = null;
        try {
            plan = planReader.revisionPlan(planRev);
        } catch (RuntimeException e) {
            log.warn("voice rerun: 读取方案 {} 失败: {}", planRev, e.getMessage());
        }
        AudioPlan.VoicePreset vp = plan == null ? null : AudioPlan.presetById(plan, AudioPlan.cloneId(voice));
        if (vp == null) {
            throw new BizException(ErrorCode.VALIDATION,
                    "克隆音色「" + AudioPlan.cloneId(voice) + "」在当前方案里已不存在，"
                            + "请重新绑定音色后再生成（不要重跑旧任务）");
        }
        String key = assetRepo.findByIdAndWorkspaceId(UUID.fromString(vp.assetId()), old.workspaceId())
                .map(studio.weaveora.asset.domain.Asset::storageKey)
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION,
                        "克隆音色「" + vp.name() + "」的样本文件已不存在（可能被删除或已重录），请重新录音色"));
        payload.put("refAssetKey", key);
        if (!vp.promptText().isBlank()) {
            payload.put("refPromptText", vp.promptText());
        }
        log.info("voice rerun refreshed clone ref job={} preset={} rev={}", old.id(), vp.id(), planRev);
        return new Retarget(planRev, old.shotId(), payload);
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
        // P9：声音类任务（voice/bgm）的参数全是“用户配置 + 参考音存储 key”，与画面改锚无关，
        //     但**必须把克隆音色的参考音重新解析一遍** —— 否则重跑修复前创建的旧任务时，
        //     payload 里只有旧的 refAssetId、没有 refAssetKey，worker 拿不到参考音，
        //     TTS 会静默兑底到自带参考音（听感上就是“变成标准女声”）。
        if ("voice".equals(old.kind()) || "bgm".equals(old.kind())) {
            return refreshAudioJobPayload(old, project, approvedId);
        }
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
            payload.put("negative_prompt", negWithRefGuard(styledNegative(st, shot.path("negative_prompt").asText("")), refs));
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
            payload.put("negative_prompt", negWithRefGuard(styledNegative(st, plan.path("negative_prompt").asText("")), refs));
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
        n.refreshCapabilities(capabilities);
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
        String kind = List.of("clip", "still", "voice", "bgm", "portrait", "lipsync").contains(job.kind()) ? job.kind() : "still";
        // 试听产物单独 kind（voice_preview/bgm_preview），避免被正式渲染/导出选中
        boolean previewJob = job.payload() != null && job.payload().path("preview").asBoolean(false);
        if (previewJob && ("voice".equals(kind) || "bgm".equals(kind))) {
            kind = kind + "_preview";
        }
        Integer jobShotNo = job.shotId() == null ? null : planReader.shotNoOf(job.shotId());
        for (CompleteAsset a : items) {
            // P13：把 worker 的人脸检测结果写进资产快照（prompt_snapshot），
            // 供选镜弹窗提前标出「无人脸」的镜——否则要等对口型跑到一半才报 Face not detected。
            // 注意：job.payload() 是共享节点，必须 deepCopy 后再改，否则会污染任务行。
            com.fasterxml.jackson.databind.JsonNode snap = job.payload();
            if (a.faceDetected() != null || a.faceFrames() != null) {
                com.fasterxml.jackson.databind.node.ObjectNode o = (snap != null && snap.isObject())
                        ? ((com.fasterxml.jackson.databind.node.ObjectNode) snap).deepCopy()
                        : mapper().createObjectNode();
                if (a.faceDetected() != null) {
                    o.put("faceDetected", a.faceDetected());
                }
                if (a.faceFrames() != null) {
                    o.put("faceFrames", a.faceFrames());
                }
                snap = o;
            }
            AssetResponse resp = toAssetResponse(assets.createOutput(
                    job.workspaceId(), job.projectId(), job.id(), job.shotId(), jobShotNo, kind,
                    a.key(), a.mime(), a.width(), a.height(), a.seed(), a.durationMs(),
                    snap));   // P8：资产带 job payload 快照（混音要靠它取 at_sec/line_index）
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

    record RefCtx(List<String> ids, List<String> keys, List<String> subjects, List<String> regions,
                  String anchor, String primarySubject) {
        static RefCtx empty() { return new RefCtx(List.of(), List.of(), List.of(), List.of(), "", ""); }
    }

    /**
     * 参考图解析（主体绑定）：
     * ① 方案内 `referenceAssets=[{assetId, subject}]`（参考图面板标注后随方案保存）优先：
     *    - 带 subject 的仅当该镜文本（action/zh/positive_prompt）提到该主体时绑定；无 subject 的始终绑定；
     *    - 该镜什么都没提到则带回全部（避免空锚定）；
     *    - 生成 anchor 文案追加到正词（人物形象以参考图为准）。
     * ② 其次 brief 级 `referenceAssets`（未出方案前在参考图面板标注，随 Brief 提交）；规则同 ①。
     * ③ 否则退回 brief 显式挂图 / 项目最新参考图（旧行为，无 anchor）。
     */
    private RefCtx resolveRefs(JsonNode plan, JsonNode shot, UUID userId, UUID workspaceId,
                               UUID projectId, UUID revisionId) {
        String text = (shot == null)
                ? plan.path("positive_prompt").asText("") + " " + plan.path("prompt_zh").asText("")
                : shot.path("action").asText("") + " " + shot.path("zh").asText("")
                  + " " + shot.path("positive_prompt").asText("");
        // P13：按「剧情主体」取锚定资产 —— **定妆图优先**（一致性靠它），没有定妆图才退回勾选的素材图
        RefCtx fromSubjects = bindFromSubjects(plan, text, workspaceId);
        if (fromSubjects != null) return fromSubjects;
        RefCtx fromPlan = bindFrom(plan == null ? null : plan.get("referenceAssets"), text, workspaceId);
        if (fromPlan != null) return fromPlan;
        RefCtx fromBrief = bindFrom(briefReferenceAssets(userId, workspaceId, projectId, revisionId), text, workspaceId);
        if (fromBrief != null) return fromBrief;
        RefCtx legacy = loadRefs(userId, workspaceId, projectId, revisionId);
        return new RefCtx(legacy.ids(), legacy.keys(), legacy.subjects(), legacy.regions(), "", legacy.primarySubject());
    }

    /**
     * P13：按剧情主体绑定锚定图。
     *
     * <p>规则：
     * <ol>
     *   <li>只取 {@code enabled}（勾选参与）的主体；镜文案命中名/别名者优先，一个都没命中则带回全部（避免空锚定）。</li>
     *   <li>每个主体取「**定妆图** → 勾选的素材图」中的第一张；两者都没有则跳过。</li>
     *   <li>顺序：先「镜文案里出现且为主主体」的，再按方案里的声明顺序 —— 保住「第 i 张图 = 哪个主体」。</li>
     * </ol>
     */
    private RefCtx bindFromSubjects(JsonNode plan, String text, UUID workspaceId) {
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> subjects =
                studio.weaveora.director.plan.PlanSubjects.parse(plan);
        if (subjects.isEmpty()) {
            return null;
        }
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> enabled = subjects.stream()
                .filter(studio.weaveora.director.plan.PlanSubjects.Subject::enabled)
                .filter(s -> s.anchorAssetId() != null)
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        String t = text == null ? "" : text;
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> picked = enabled.stream()
                .filter(s -> studio.weaveora.director.plan.PlanSubjects.matches(t, s))
                .toList();
        if (picked.isEmpty()) {
            picked = enabled;
            log.info("refs: 镜文本未命中任何主体，回退为全部 {} 个主体（{}）",
                    picked.size(), picked.stream().map(studio.weaveora.director.plan.PlanSubjects.Subject::name).toList());
        }
        java.util.List<UUID> ids = new ArrayList<>();
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : picked) {
            try {
                ids.add(UUID.fromString(sub.anchorAssetId()));
            } catch (IllegalArgumentException ignored) {
                // 资产 id 非法（手工改过方案）→ 跳过该主体
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        java.util.Map<String, studio.weaveora.asset.domain.Asset> byId = new java.util.HashMap<>();
        for (studio.weaveora.asset.domain.Asset a : assetRepo.findByIdInAndWorkspaceId(ids, workspaceId)) {
            byId.put(a.id().toString(), a);
        }
        java.util.List<String> okIds = new ArrayList<>();
        java.util.List<String> keys = new ArrayList<>();
        java.util.List<String> subjNames = new ArrayList<>();
        java.util.List<String> regions = new ArrayList<>();
        StringBuilder mapping = new StringBuilder();
        String primary = "";
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : picked) {
            studio.weaveora.asset.domain.Asset a = byId.get(sub.anchorAssetId());
            if (a == null) {
                continue;
            }
            boolean portrait = sub.hasPortrait() && sub.portraitAssetId().equals(a.id().toString());
            subjNames.add(sub.name());
            keys.add(a.storageKey());
            okIds.add(a.id().toString());
            regions.add(null);
            if (mapping.length() > 0) {
                mapping.append("; ");
            }
            mapping.append(keys.size()).append(") ").append(sub.name())
                    .append(portrait ? "(定妆图)" : "(素材图)");
            if (primary.isEmpty() && studio.weaveora.director.plan.PlanSubjects.nameMatches(t, sub.name())) {
                primary = sub.name();
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        if (primary.isEmpty()) {
            primary = subjNames.get(0);
        }
        String anchor = "\nReference images in order: " + mapping
                + ". Each subject MUST strictly match its own reference image (face / hair / costume / shape);"
                + " keep subjects distinct and never blend or swap their identities.";
        log.info("refs: 本镜锚定 {} 张（{}）primary={}", keys.size(), mapping, primary);
        return new RefCtx(okIds, keys, subjNames, regions, anchor, primary);
    }

    /** brief.constraints.referenceAssets（新流程：未出方案前就标注的主体绑定）。取不到/无则 null。 */
    private JsonNode briefReferenceAssets(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        try {
            UUID briefId = planReader.revisionBriefId(revisionId);
            BriefSnapshot brief = projects.requireBrief(userId, workspaceId, projectId, briefId);
            JsonNode ra = brief.constraints() == null ? null : brief.constraints().get("referenceAssets");
            return (ra != null && ra.isArray() && ra.size() > 0) ? ra : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 把 [{assetId, subject}] 按镜文本做主体过滤 → RefCtx；无有效绑定返回 null（交下一层）。
     *  顺序严格按用户标注顺序（不能按 DB 返回顺序，否则主体↔图 映射会错）。 */
    private RefCtx bindFrom(JsonNode arr, String text, UUID workspaceId) {
        if (arr == null || !arr.isArray() || arr.size() == 0) return null;
        String t = text == null ? "" : text;
        List<JsonNode> picked = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (JsonNode b : arr) {
            if (b == null || !b.isObject() || b.path("assetId").asText("").isBlank()) continue;
            String subject = b.path("subject").asText("");
            if (subjectMatches(t, subject)) picked.add(b);
            else if (!subject.isBlank()) dropped.add(subject);
        }
        if (picked.isEmpty()) {
            // 该镜文本一个主体都没提到 → 带回全部（避免空锚定）
            for (JsonNode b : arr) {
                if (b != null && b.isObject() && !b.path("assetId").asText("").isBlank()) picked.add(b);
            }
            log.info("refs: 镜文本未匹配到任何主体（{}），回退为绑定全部 {} 张参考图",
                    dropped, picked.size());
        } else if (!dropped.isEmpty()) {
            // 一定要看得见：漏绑参考图 = 人物一致性直接崩（踩过：方案绑「秦可卿」、镜文写「可卿」）
            log.info("refs: 本镜绑定 {} 张（已剔未提及的主体 {}）", picked.size(), dropped);
        }
        if (picked.isEmpty()) return null;
        List<UUID> ids = new ArrayList<>();
        for (JsonNode b : picked) {
            try { ids.add(UUID.fromString(b.path("assetId").asText())); } catch (IllegalArgumentException ignored) { }
        }
        // 按标注顺序重建，保住“第 i 张图 = 哪个主体”
        java.util.Map<String, studio.weaveora.asset.domain.Asset> byId = new java.util.HashMap<>();
        for (studio.weaveora.asset.domain.Asset a : assetRepo.findByIdInAndWorkspaceId(ids, workspaceId)) {
            byId.put(a.id().toString(), a);
        }
        List<String> okIds = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        StringBuilder mapping = new StringBuilder();
        StringBuilder layout = new StringBuilder();
        String primary = "";
        for (JsonNode b : picked) {
            String aid = b.path("assetId").asText();
            studio.weaveora.asset.domain.Asset a = byId.get(aid);
            if (a == null) continue;
            String subject = b.path("subject").asText("");
            okIds.add(aid);
            keys.add(a.storageKey());
            subjects.add(subject);
            regions.add(regionCsv(b.path("region")));
            if (!subject.isBlank()) {
                if (mapping.length() > 0) mapping.append("; ");
                mapping.append(keys.size()).append(") ").append(subject);
                if (primary.isBlank() && t.contains(subject)) primary = subject;
                String reg = regionCsv(b.path("region"));
                if (!reg.isBlank()) {
                    if (layout.length() > 0) layout.append("; ");
                    layout.append(subject).append(" -> ").append(regionHint(reg));
                }
            }
        }
        if (okIds.isEmpty()) return null;
        if (primary.isBlank()) {
            for (String s : subjects) { if (!s.isBlank()) { primary = s; break; } }
        }
        String anchor;
        if (subjects.stream().anyMatch(s -> !s.isBlank())) {
            anchor = " Reference images in order: " + mapping
                    + ". Each character's identity, face and costume must strictly follow its own reference image;"
                    + " keep the characters distinct and do not share, blend or swap their faces.";
            if (layout.length() > 0) {
                anchor = anchor + " Spatial layout: " + layout + ".";
            }
        } else {
            anchor = " The subject appearance must strictly follow the provided reference image.";
        }
        return new RefCtx(okIds, keys, subjects, regions, anchor, primary);
    }

    /** 归一化区域 {x,y,w,h}（0–1）→ "x,y,w,h"；非法返回 ""。 */
    private static String regionCsv(JsonNode r) {
        if (r == null || !r.isObject()) return "";
        double x = r.path("x").asDouble(-1), y = r.path("y").asDouble(-1);
        double w = r.path("w").asDouble(-1), h = r.path("h").asDouble(-1);
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x > 1 || y > 1 || w > 1 || h > 1) return "";
        if (x + w > 1.001 || y + h > 1.001) return "";
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", x, y, w, h);
    }

    /** 区域 → 方位描述（供云模型提示词）。 */
    private static String regionHint(String csv) {
        try {
            String[] p = csv.split(",");
            double cx = Double.parseDouble(p[0]) + Double.parseDouble(p[2]) / 2;
            double cy = Double.parseDouble(p[1]) + Double.parseDouble(p[3]) / 2;
            String hz = cx < 0.34 ? "left" : (cx > 0.66 ? "right" : "center");
            String vt = cy < 0.34 ? "upper" : (cy > 0.66 ? "lower" : "middle");
            return vt + "-" + hz + " of frame";
        } catch (Exception e) {
            return "";
        }
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
            return new RefCtx(ids.stream().map(UUID::toString).toList(), keys, List.of(), List.of(), "", "");
        } catch (BizException e) {
            return RefCtx.empty(); // 引用缺失不阻塞出图（仅丢锚定）
        }
    }

    /**
     * 参考图主体是否出现在镜文本里。
     *
     * <p>先精确包含；中文名再退一步做「2 字片段」匹配 —— 方案里绑定常用全名（秦可卿 / 贾宝玉），
     * 而镜头文本里往往只写名（可卿 / 宝玉），精确包含会把参考图**默默丢掉**，
     * 于是关键帧完全不参考人物形象（实测踩过）。
     *
     * <p>空 subject = 无主体绑定的通用参考图，总是绑定。
     */
    static boolean subjectMatches(String text, String subject) {
        if (subject == null || subject.isBlank()) return true;
        String t = text == null ? "" : text;
        String s = subject.trim();
        if (t.contains(s)) return true;
        if (s.length() < 3) return false;          // 2 字名没有可退的片段
        for (int i = 0; i + 2 <= s.length(); i++) {
            if (t.contains(s.substring(i, i + 2))) return true;
        }
        return false;
    }

    /** 多主体时追加“防串脸”负词。 */
    private static String negWithRefGuard(String neg, RefCtx refs) {
        if (refs == null) return neg;
        long distinct = refs.subjects().stream().filter(s -> s != null && !s.isBlank()).distinct().count();
        if (distinct <= 1) return neg;
        String extra = "identical faces, face swap, same person repeated, mixed identities, cloned face";
        return (neg == null || neg.isBlank()) ? extra : neg + ", " + extra;
    }

    private void attachRefs(ObjectNode payload, RefCtx refs) {
        com.fasterxml.jackson.databind.node.ArrayNode ids = payload.putArray("referenceAssetIds");
        refs.ids().forEach(ids::add);
        com.fasterxml.jackson.databind.node.ArrayNode keys = payload.putArray("referenceKeys");
        refs.keys().forEach(keys::add);
        com.fasterxml.jackson.databind.node.ArrayNode subjects = payload.putArray("referenceSubjects");
        refs.subjects().forEach(subjects::add);
        com.fasterxml.jackson.databind.node.ArrayNode regions = payload.putArray("referenceRegions");
        for (String csv : refs.regions()) {
            if (csv == null || csv.isBlank()) {
                regions.addNull();
            } else {
                String[] p = csv.split(",");
                regions.addObject()
                        .put("x", Double.parseDouble(p[0])).put("y", Double.parseDouble(p[1]))
                        .put("w", Double.parseDouble(p[2])).put("h", Double.parseDouble(p[3]));
            }
        }
        if (refs.primarySubject() != null && !refs.primarySubject().isBlank()) {
            payload.put("primarySubject", refs.primarySubject());
        }
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

    /**
     * 解析本批要处理的镜头。
     *
     * <p>P12 封版口径：
     * <ul>
     *   <li>单个镜（{@code shotId} 指定）→ 是他明确要重做的，**不受封版限制**；</li>
     *   <li>显式列了 {@code shotNos} → 按他勾的跑（含已封版镜也算明确要求）；</li>
     *   <li>其余（全部/批量）→ 跳过已封版镜，避免重跑把满意的镜头又生成一遍。</li>
     * </ul>
     */
    private List<UUID> resolveVideoShots(UUID userId, UUID workspaceId, UUID projectId,
                                         UUID revisionId, UUID shotId, String kind,
                                         List<Integer> onlyShotNos, boolean includeLocked) {
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

        boolean explicit = shotId != null || (onlyShotNos != null && !onlyShotNos.isEmpty()) || includeLocked;
        Set<Integer> locked = explicit ? Set.of() : shotLocks.lockedShotNosRaw(workspaceId, projectId);
        return applyShotFilter(ids, onlyShotNos, locked, planReader::shotNoOf);
    }

    /**
     * 自托管执行面：配音 / 配乐 / 对口型 都跑在**本机**（ComfyUI + CosyVoice \+ ACE-Step），
     * 且云节点根本不会认领（调度看 {@code engine_route}）。
     *
     * <p>所以这三种 kind 一律落 {@code gpu}，**不受用户 image/video_engine=cloud 的影响**。
     */
    static final Set<String> SELF_HOSTED_KINDS = Set.of("voice", "bgm", "lipsync");

    /**
     * P13 纯函数（便于单测）：决定任务落到哪个执行面。
     *
     * <p>背景：对口型曾因在 {@code createLipsyncJobs} 里自己调
     * {@code resolveEngine(userId,"clip")} 而被路由到云（用户 video_engine=cloud），
     * 云 worker 没有口型工作流 → 秒失败。配音/配乐的「重生成」也有同样问题。
     *
     * @param kind           任务类型
     * @param resolvedEngine 按用户设置解析出来的引擎（gpu|cloud）
     * @return 自托管 kind 恒为 gpu；其它 kind 尊重用户设置
     */
    static String routeForKind(String kind, String resolvedEngine) {
        // 注意：Set.of(...).contains(null) 会抛 NPE，故先判 null（kind 非法时按“非自托管”处理）
        return kind != null && SELF_HOSTED_KINDS.contains(kind) ? "gpu" : resolvedEngine;
    }

    /** worker 心跳间隔 25s（见 stub_worker.py），宽限期给足 12 倍余量。 */
    private static final long WORKER_ALIVE_GRACE_MIN = 5;

    /**
     * 纯函数（便于单测）：这个 running 任务该不该被回收。
     *
     * <ul>
     *   <li>worker 心跳正常且未过硬上限 → <b>不回收</b>（长任务，如对口型一个镜 25–30min）</li>
     *   <li>worker 失联 → 回收（真·卡死）</li>
     *   <li>过了硬上限 → 回收（worker 不死但任务永不结束的病态情况，不能永久占 GPU）</li>
     * </ul>
     */
    static boolean shouldReap(boolean workerAlive, boolean pastHardCap) {
        return pastHardCap || !workerAlive;
    }

    /** worker 是否仍在心跳（workerId 是节点 id 的字符串形式）。 */
    private boolean workerAliveRecently(String workerId, java.time.OffsetDateTime now) {
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        try {
            return nodes.findById(UUID.fromString(workerId))
                    .map(n -> n.lastSeenAt() != null
                            && n.lastSeenAt().isAfter(now.minusMinutes(WORKER_ALIVE_GRACE_MIN)))
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;   // 非 UUID（历史行）→ 当作失联，按老逻辑回收
        }
    }

    /**
     * P12 纯函数：按「勾选的镜」与「封版」过滤镜头清单（便于单测）。
     *
     * <ul>
     *   <li>{@code onlyShotNos} 非空 → 只留这些镜（用户显式勾选，含已封版镜也算明确要求）；</li>
     *   <li>{@code locked} 非空 → 剔除这些镜（批量生成默认跳过封版镜）。</li>
     * </ul>
     */
    static List<UUID> applyShotFilter(List<UUID> ids, List<Integer> onlyShotNos, Set<Integer> locked,
                                      java.util.function.ToIntFunction<UUID> shotNoOf) {
        List<UUID> out = new ArrayList<>(ids);
        if (onlyShotNos != null && !onlyShotNos.isEmpty()) {
            Set<Integer> want = new LinkedHashSet<>(onlyShotNos);
            out.removeIf(id -> !want.contains(shotNoOf.applyAsInt(id)));
        }
        if (locked != null && !locked.isEmpty()) {
            out.removeIf(id -> locked.contains(shotNoOf.applyAsInt(id)));
        }
        return out;
    }

    /**
     * P13：某剧情主体的定妆照存储 key（用于对口型「锁人」）；没有返回 null。
     *
     * <p>取数顺序（线上实测：只有第一条能命中）：
     * <ol>
     *   <li>{@code plan.subjects[].portraitAssetId} —— 方案里每个主体记的就是资产 id（权威来源）</li>
     *   <li>{@code kind=portrait} 且 snapshot.subject 匹配的产物（新链路）</li>
     *   <li>{@code kind=reference} 且 snapshot.subject 匹配的产物（早期把参考图直接当定妆照）</li>
     * </ol>
     * 实测坑：「那宝玉恍恍惚惚」项目里定妆照是 {@code kind=reference} 的 PNG，
     * 根本不在 portrait 表里 —— 只查 kind=portrait 会永远拿不到参考图，
     * 锁人静默退化成「取最大脸」，多人镜又回到配错人。
     */
    private String portraitKeyOf(JsonNode plan, UUID projectId, UUID workspaceId, String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String assetId = null;
        for (JsonNode s : plan.path("subjects")) {
            if (subject.equals(s.path("name").asText(""))) {
                assetId = s.path("portraitAssetId").asText("");
                break;
            }
        }
        if (!assetId.isBlank()) {
            try {
                var a = assetRepo.findByIdAndWorkspaceId(UUID.fromString(assetId), workspaceId).orElse(null);
                if (a != null) {
                    return a.storageKey();
                }
            } catch (IllegalArgumentException ignored) {
                // 非 UUID（脏数据）→ 继续下面的兜底查询
            }
        }
        for (String kind : List.of("portrait", "reference")) {
            for (studio.weaveora.asset.domain.Asset a : assetRepo
                    .findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(projectId, workspaceId, kind)) {
                if (subject.equals(studio.weaveora.asset.AssetService.subjectOf(a))) {
                    return a.storageKey();
                }
            }
        }
        return null;
    }

    /** 旧签名（不带 shotNos / includeLocked）：等于“全部且跳过封版”。 */
    private List<UUID> resolveVideoShots(UUID userId, UUID workspaceId, UUID projectId,
                                         UUID revisionId, UUID shotId, String kind) {
        return resolveVideoShots(userId, workspaceId, projectId, revisionId, shotId, kind, null, false);
    }

    /**
     * P13 对口型（lipsync）：把该镜的 **画面产物** 与 **该镜配音** 一起交给口型模型，
     * 输出「嘴型与台词对齐」的片段。
     *
     * <p>为什么必须单独一条任务：图生视频模型（Wan i2v 等）没有音频通道，
     * 出来的画面不可能对口型；口型要靠音频驱动的后处理（本地 LatentSync / 云端 lipsync）。
     *
     * <p>只对**有台词的镜**有意义；建议只在对话 + 特写/近景镜头上跑（远景/背影白花钱）。
     * 时长对齐要求：音频与画面基本等长 → 对话镜建议用 audio_first 模式（镜长=配音长）。
     *
     * <p><b>并发警告</b>：本机 3070 Ti 8GB 实测峰值显存 ~7.9GiB，对口型期间**不能再排其它 GPU 任务**
     * （本机 worker 单线程取任务，天然串行；payload 里带 gpuExclusive/gpuHint 给前端提示）。
     * 实测速度 ≈ 2.5 分钟 / 秒视频（4–5s 对话镜约 10–13 分钟），故 worker 超时设 1800s。
     */
    private List<GenerationJob> createLipsyncJobs(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                                 JsonNode plan, int revisionNo, UUID userId) {
        List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(),
                "clip", req.shotNos(), Boolean.TRUE.equals(req.includeLocked()));
        if (shotIds.isEmpty()) {
            throw new BizException(ErrorCode.SHOT_NOT_APPROVED, emptyShotReason(workspaceId, projectId, req));
        }
        // P13：对口型**必须**跑在本机 GPU（ComfyUI + LatentSync 工作流）—— 云 worker 没有
        // WEAVEORA_LIPSYNC_WORKFLOW，云端视频模型也没有音频通道/口型能力。
        // 绝不能用 resolveEngine(userId, "clip")：用户 video_engine=cloud 时会被派到云节点，
        // 秒失败 LIPSYNC_ERROR（2026-09-13 线上实例：那宝玉恍恍惚惚 / 第1镜 / engine_route=cloud）。
        String engineRoute = routeForKind("lipsync", engineSettings.resolveEngine(userId, "clip"));
        List<GenerationJob> created = new ArrayList<>();
        // 被跳过的镜及原因：显式勾选却没生成时，把原因回给用户（不再只给一句笼统提示）
        List<String> skipped = new ArrayList<>();
        for (UUID shotId : shotIds) {
            JsonNode shot = shotOf(plan, shotId);
            if (shot == null) {
                continue;
            }
            int shotNo = shot.path("shot_no").asInt();
            // 画面：该镜最新的 motion 片段（没有 motion 就用关键帧静帧，口型模型能处理静帧）
            studio.weaveora.asset.domain.Asset clip = pickNewestAsset(projectId, workspaceId, shotNo, "clip");
            studio.weaveora.asset.domain.Asset still = clip != null ? clip : pickNewestAsset(projectId, workspaceId, shotNo, "still");
            if (still == null) {
                skipped.add("第" + shotNo + "镜（缺画面：无 motion 也无关键帧）");
                continue;
            }
            // 音频：该镜全部配音段（按 line_index 升序，取每段最新）
            List<studio.weaveora.asset.domain.Asset> voices = newestVoicePerLine(projectId, workspaceId, shotNo);
            if (voices.isEmpty()) {
                skipped.add("第" + shotNo + "镜（缺配音）");
                continue;   // 没配音就没有可对的口型
            }
            ObjectNode payload = mapper().createObjectNode();
            payload.put("kind", "lipsync");
            payload.put("mode", "video");
            payload.put("revisionId", req.revisionId().toString());
            payload.put("shotId", shotId.toString());
            payload.put("shot_no", shotNo);
            payload.put("videoKey", still.storageKey());
            payload.put("videoIsStill", clip == null);
            payload.put("isStill", clip == null);
            com.fasterxml.jackson.databind.node.ArrayNode vk = payload.putArray("voiceKeys");
            for (studio.weaveora.asset.domain.Asset a : voices) {
                vk.add(a.storageKey());
            }
            payload.put("voiceCount", voices.size());
            payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
            payload.put("lipSync", shot.path("lip_sync").asBoolean(true));
            // P13：说话人信息 —— 双人对话必须知道「哪段台词是谁说的」。
            // narrations 每段带 subject（说话人）与 at_sec/end_sec（时间窗）；
            // 定妆照（kind=portrait + subject）作为「锁人」参考：worker 用 w600k_r50
            // 算人脸特征，逐帧只驱动与该特征最像的那张脸。
            // 为什么必须：LatentSync 逐帧取「面积最大的脸」，多人同框时两张脸的大小会
            // 在镜头中途互换（实测第 4 镜 frame34 左脸大、frame45 右脸大）→ 突然换人 → 画面坏掉。
            ObjectNode speakersNode = payload.putObject("speakers");   // {说话人: 定妆照 storageKey}
            var segmentsNode = payload.putArray("segments");
            java.util.LinkedHashSet<String> speakerNames = new java.util.LinkedHashSet<>();
            for (JsonNode n : shot.path("narrations")) {
                if (!"dialogue".equals(n.path("kind").asText(""))) {
                    continue;
                }
                String who = n.path("subject").asText("").trim();
                if (who.isEmpty()) {
                    continue;
                }
                speakerNames.add(who);
                ObjectNode seg = segmentsNode.addObject();
                seg.put("subject", who);
                seg.put("startMs", (int) Math.round(n.path("at_sec").asDouble(0) * 1000));
                seg.put("endMs", (int) Math.round(n.path("end_sec").asDouble(0) * 1000));
            }
            payload.put("speakerCount", speakerNames.size());
            payload.put("speakerNames", String.join("、", speakerNames));
            for (String who : speakerNames) {
                String pk = portraitKeyOf(plan, projectId, workspaceId, who);
                if (pk != null) {
                    speakersNode.put(who, pk);
                } else {
                    log.warn("lipsync 第{}镜说话人「{}」没有定妆照/参考图 —— 无法锁人，将退回「取最大脸」", shotNo, who);
                }
            }
            // 本机 LatentSync 实测：峰值显存 ~7.9GiB / 8GiB，跑的时候不能再有别的 GPU 任务。
            // 本机 worker 单线程取任务，天然串行；这两个字段是给前端/运维看的显式提示。
            payload.put("gpuExclusive", true);
            payload.put("gpuHint", "对口型会独占本机显存（~7.9/8GiB），同一时刻不要同时排其它 GPU 任务；"
                    + "实测约 2.5 分钟/秒视频（4–5s 对话镜约 10–13 分钟）");
            created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                    PRESET_CLIP, "lipsync", payload, userId, engineRoute));
        }
        if (created.isEmpty()) {
            String why = skipped.isEmpty() ? "" : "（" + String.join("、", skipped.subList(0, Math.min(4, skipped.size())))
                    + (skipped.size() > 4 ? " 等" : "") + "）";
            throw new BizException(ErrorCode.VALIDATION,
                    "没有可对口型的镜头：需要该镜已有 motion/关键帧**且**已生成配音" + why);
        }
        if (!skipped.isEmpty()) {
            log.warn("lipsync 跳过了 {} 个镜：{}", skipped.size(), skipped);
        }
        log.warn("lipsync jobs created project={} count={} —— 独占本机显存（~7.9/8GiB），勿与其它 GPU 任务并发",
                projectId, created.size());
        return created;
    }

    /** 该项目/镜号下最新的一条某类产物。 */
    private studio.weaveora.asset.domain.Asset pickNewestAsset(UUID projectId, UUID workspaceId, int shotNo, String kind) {
        List<studio.weaveora.asset.domain.Asset> list = assetRepo
                .findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(projectId, workspaceId, shotNo, kind);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 该镜每个 line_index 的最新配音产物（升序），用于对口型。 */
    private List<studio.weaveora.asset.domain.Asset> newestVoicePerLine(UUID projectId, UUID workspaceId, int shotNo) {
        List<studio.weaveora.asset.domain.Asset> all = assetRepo
                .findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(projectId, workspaceId, shotNo, "voice");
        Map<Integer, studio.weaveora.asset.domain.Asset> newest = new java.util.TreeMap<>();
        for (studio.weaveora.asset.domain.Asset a : all) {
            JsonNode snap = a.promptSnapshot();
            int li = (snap != null && snap.hasNonNull("line_index")) ? snap.path("line_index").asInt(0) : 0;
            newest.putIfAbsent(li, a);
        }
        return new ArrayList<>(newest.values());
    }

    /**
     * P13 定妆图（subject portrait）：用该主体勾选的素材图/上一版定妆图做输入，生成一张「标准角色设定图」。
     *
     * <p>这张图之后会作为该主体在所有分镜里的**唯一锚定图** —— 直接喂随手拍的用户图，
     * 风格/构图不可控，一致性会飘；先定妆再锚定才稳定。
     */
    private List<GenerationJob> createPortraitJob(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                                  JsonNode plan, int revisionNo, UUID userId) {
        String subject = req.subject() == null ? "" : req.subject().trim();
        if (subject.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "生成定妆图需要指定主体名（subject）");
        }
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        studio.weaveora.director.plan.PlanSubjects.Subject sub =
                studio.weaveora.director.plan.PlanSubjects.parse(plan).stream()
                        .filter(s -> subject.equals(s.name()))
                        .findFirst()
                        .orElse(null);
        java.util.List<UUID> ids = new ArrayList<>();
        // P13：界面上“当前点选的参考图”直接传进来时优先用它 —— 用户不必先保存/确认就能出定妆照
        for (String raw : (req.refAssetIds() == null ? java.util.List.<String>of() : req.refAssetIds())) {
            try {
                ids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // 忽略非法 id
            }
        }
        int explicitRefs = ids.size();
        if (sub != null) {
            if (sub.hasPortrait()) {
                try {
                    ids.add(UUID.fromString(sub.portraitAssetId()));
                } catch (IllegalArgumentException ignored) {
                    // 忽略非法 id
                }
            }
            for (studio.weaveora.director.plan.PlanSubjects.Ref r : sub.checkedRefs()) {
                try {
                    ids.add(UUID.fromString(r.assetId()));
                } catch (IllegalArgumentException ignored) {
                    // 忽略
                }
            }
        }
        if (sub == null && explicitRefs == 0) {
            throw new BizException(ErrorCode.VALIDATION,
                    "方案里没有主体「" + subject + "」，且没有指定参考图（先在参考图卡片里「一键生成主体」或手动添加）");
        }
        java.util.List<String> keys = new ArrayList<>();
        for (studio.weaveora.asset.domain.Asset a : assetRepo.findByIdInAndWorkspaceId(ids, workspaceId)) {
            keys.add(a.storageKey());
        }
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kind", "portrait");
        payload.put("mode", "video");
        payload.put("revisionId", req.revisionId().toString());
        payload.put("revision_no", revisionNo);
        payload.put("subject", subject);
        payload.put("portrait_version", (sub == null ? 1 : sub.portraitVersion() + 1));
        payload.put("positive_prompt", studio.weaveora.director.SubjectPrompts.portraitPrompt(subject, sub == null ? null : sub.kind(), keys.size()));
        payload.put("negative_prompt", "text, watermark, logo, multiple people, deformed face, extra limbs, lowres");
        payload.put("aspect_ratio", project.aspectRatio());
        int[] dd = dimsFor(project.aspectRatio());
        payload.set("params", mapper().createObjectNode().put("width", dd[0]).put("height", dd[1]));
        payload.put("seed", randomSeed());
        com.fasterxml.jackson.databind.node.ArrayNode keysNode = payload.putArray("referenceKeys");
        keys.forEach(keysNode::add);
        com.fasterxml.jackson.databind.node.ArrayNode subjNode = payload.putArray("referenceSubjects");
        keys.forEach(k -> subjNode.add(subject));
        payload.put("primarySubject", subject);
        stampRevisionMeta(payload, revisionNo, payload.path("positive_prompt").asText(""));
        String engine = engineSettings.resolveEngine(userId, "still");
        GenerationJob job = createOne(workspaceId, projectId, req.revisionId(), null, PRESET_STILL,
                "portrait", payload, userId, engine);
        log.info("portrait job created project={} subject={} refs={}", projectId, subject, keys.size());
        return List.of(job);
    }

    /** P12：镜头被过滤空时的准确原因（区分「没确认」和「全被封版跳过」）。 */
    /**
     * 运动帧数上限（按引擎）：
     *
     * <ul>
     *   <li>cloud（云 API）：上限来自**模型**——优先取项目里的「模型上限(s)」× fps，
     *       否则用配置 motion-frames-max-cloud（默认 300）。</li>
     *   <li>gpu（本机 ComfyUI）：motion-frames-max（默认 96，按 3070Ti 显存定）。</li>
     * </ul>
     */
    private int motionFramesMaxFor(String engineRoute, JsonNode plan, UUID userId) {
        if (!"cloud".equals(engineRoute)) {
            return Math.max(motionFramesMin, motionFramesMax);
        }
        int fps = Math.max(1, plan.path("edit_plan").path("fps").asInt(30));
        // 1) 项目里显式设了「模型上限(s)」-> 用它（最准）
        double capSec = plan.path("edit_plan").path("video_model_max_sec").asDouble(0);
        if (capSec > 0) {
            return Math.max(motionFramesMin, (int) Math.round(capSec * fps));
        }
        // 2) 否则看**模型 schema** 里帧数字段的上限（如 Wan num_frames.max=121）
        Integer schemaMax = engineSettings.videoSchemaFramesMax(userId);
        if (schemaMax != null && schemaMax > 0) {
            return Math.max(motionFramesMin, Math.min(schemaMax, motionFramesMaxCloud));
        }
        // 3) 都没有 -> 云配置兜底
        return Math.max(motionFramesMin, motionFramesMaxCloud);
    }

    /** 供接口下发：本项目的运动帧数可用区间（含来源说明）。 */
    public java.util.Map<String, Object> motionLimits(UUID userId, String kind, JsonNode plan) {
        String route = engineSettings.resolveEngine(userId, kind);
        int hi = motionFramesMaxFor(route, plan, userId);
        int fps = Math.max(1, plan.path("edit_plan").path("fps").asInt(30));
        return java.util.Map.of(
                "engine", route,
                "minFrames", motionFramesMin,
                "maxFrames", hi,
                "fps", fps,
                "maxClipSec", Math.round(hi * 100.0 / fps) / 100.0,
                "gpuMaxFrames", motionFramesMax,
                "cloudMaxFrames", motionFramesMaxCloud,
                "source", !"cloud".equals(route)
                        ? "本机 GPU 显存（motion-frames-max）"
                        : (plan.path("edit_plan").path("video_model_max_sec").asDouble(0) > 0
                            ? "项目「模型上限」× fps"
                            : (hi < motionFramesMaxCloud ? "云模型 schema 的帧数上限" : "云配置 motion-frames-max-cloud")));
    }

    private String resolveVideoShotsPlaceholder() {
        return "";
    }

    private String emptyShotReason(UUID workspaceId, UUID projectId, CreateJobRequest req) {
        int approved = planReader.approvedShotIds(req.revisionId()).size();
        if (approved == 0) {
            return "没有已确认的镜头可生成（先在方案里确认分镜）";
        }
        boolean explicit = req.shotId() != null
                || (req.shotNos() != null && !req.shotNos().isEmpty())
                || Boolean.TRUE.equals(req.includeLocked());
        if (!explicit) {
            Set<Integer> locked = shotLocks.lockedShotNosRaw(workspaceId, projectId);
            if (!locked.isEmpty()) {
                return "已确认的 " + approved + " 个镜头全部处于「封版」状态（第 " + locked + " 镜）—— "
                        + "本批已自动跳过；如需重做请取消封版，或在生成弹窗里显式勾选要跑的镜头";
            }
        }
        return "没有可生成的镜头（勾选的镜头不在当前确认稿里，或尚未确认）";
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
        payload.put("negative_prompt", negWithRefGuard(styledNegative(style, shot.path("negative_prompt").asText("")), refs));
        stampRevisionMeta(payload, revisionNo, pos);
        payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
        payload.put("fps", plan.path("edit_plan").path("fps").asInt(30));
        payload.put("seed", seed);
        // P12：画幅以**项目的视频格式**（竖屏/宽屏）为准 —— 不能让计划里的 aspect_ratio 覆盖，
        // 否则同一项目换个模型/换版方案就会出不同画幅（切换模型时尤其明显）。
        String aspect = project.aspectRatio();
        if (aspect == null || aspect.isBlank()) {
            aspect = plan.path("aspect_ratio").asText("16:9");
        }
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
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.shotNo(), a.kind(), a.mime(),
                a.width(), a.height(), a.durationMs(),
                studio.weaveora.asset.AssetService.lineIndexOf(a),
                studio.weaveora.asset.AssetService.subjectOf(a), null,
                studio.weaveora.asset.AssetService.snapshotKindOf(a),
                studio.weaveora.asset.AssetService.faceDetectedOf(a),
                studio.weaveora.asset.AssetService.faceFramesOf(a), a.createdAt());
    }

    private static long randomSeed() {
        return ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /** complete 请求中的资产元数据。 */
    public record CompleteAsset(String key, String mime, Integer width, Integer height, Long seed, Integer durationMs,
                                Boolean faceDetected, String faceFrames) {
    }
}
