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
    private final boolean motionAppendAction;
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
                              "${weaveora.job.running-timeout-minutes:60}") int runningTimeoutMin,
                              /**
                               * motion（clip）是否把计划里的 action 追加到正词末尾。
                               *
                               * <p>默认 false：导演生成的 positive_prompt 里**已经有**英文动作
                               * （如 "Baoyu leans close to shy Keqing, she turns away…"），
                               * 再拼一句中文动作 = 重复指令、反而稀释画面。
                               * 只有当某个方案的 prompt 确实缺动作时才把它打开。
                               */
                              @org.springframework.beans.factory.annotation.Value(
                                      "${weaveora.video.motion-append-action:false}") boolean motionAppendAction) {
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
        this.motionAppendAction = motionAppendAction;
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
        if (req.kind() == null || !List.of("still", "clip", "voice", "bgm", "portrait", "lipsync", "talk").contains(req.kind())) {
            throw new BizException(ErrorCode.VALIDATION, "kind 必须为 still|clip|voice|bgm|portrait|lipsync|talk");
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
        // jaw-lip（整脸音频驱动 / EchoMimic）：从**静帧**重新生成整段表演（嘴+下颌+表情），
        // 与 lipsync（在既有画面上换嘴、保留运镜）互补；产物按 lipsync 落库（前端「对口型」Tab 可见）。
        if ("talk".equals(req.kind())) {
            return createTalkJobs(workspaceId, projectId, req, plan, revisionNo, userId)
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
                        // ★ 逐帧位置：把帧号传进去，applyLayoutRegions 才能取 keyframes[fi].layout
                        ObjectNode payload = videoShotPayload("still", plan, shot, req.revisionId(), shotId,
                                revisionNo, raw, seed, project, style, refs, fi);
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
                        revisionNo, shot.path("positive_prompt").asText(""), seed, project, style, refs, -1);
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

    /**
     * A3：每个 worker 节点「上一个任务类型」，用于同 kind 优先认领（避免 still→clip→still 反复换大模型）。
     *
     * <p>进程内缓存即可：多实例部署时亲和只在各自实例内生效，最坏情况退化为旧行为（不会出错）。
     * 不落库是为了避免为这点优化加一次 schema 迁移。
     */
    private final Map<UUID, String> lastClaimedKind = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional
    public Map<String, Object> claim(UUID nodeId) {
        WorkerNode n = node(nodeId);
        UUID scope = n.workspaceId(); // NULL = 节点池 → 任意工作区
        // 引擎路由匹配：节点能力 engine（缺省 gpu）只认领同引擎任务
        String nodeEngine = n.capabilities() == null ? "gpu"
                : n.capabilities().path("engine").asText("gpu");

        // ── A3 同 kind 优先（重要）：先收集全部可选任务，再优先挑「与本节点上一个任务同类型」的那个。──
        // 为什么：ComfyUI 里出图（Qwen-Image ~28GB）、出片（A14B 双专家 ~26.6GB）、对口型、配乐
        // 各自要一套大权重；若按创建顺序交错认领（still→clip→still），每换一次都要卸载一套再加载另一套
        // （实测重启后首张图曾要 12 分钟）。同 kind 连跑可以让模型一直待在显存里。
        // 注意：**不影响单跑** —— 只有一个候选时行为与从前完全一致；跨 kind 也不会饿死（没同 kind 就取最早的）。
        List<GenerationJob> candidates = new ArrayList<>();
        for (GenerationJob candidate : jobs.findQueuedForClaim()) {
            if (scope != null && !scope.equals(candidate.workspaceId())) {
                continue;
            }
            if (!nodeEngine.equals(candidate.engineRoute())) {
                continue;
            }
            candidates.add(candidate);
        }
        String lastKind = lastClaimedKind.get(nodeId);
        GenerationJob picked = null;
        if (lastKind != null) {
            for (GenerationJob c : candidates) {
                if (lastKind.equals(c.kind())) {
                    picked = c;
                    break;
                }
            }
        }
        if (picked == null && !candidates.isEmpty()) {
            picked = candidates.get(0);
        }
        if (picked != null && lastKind != null && !lastKind.equals(picked.kind()) && candidates.size() > 1) {
            log.info("claim 切换任务类型（队列里没有同类型任务）：{} -> {}", lastKind, picked.kind());
        }
        if (picked != null) {
            int updated = jobs.claim(picked.id(), nodeId.toString(), OffsetDateTime.now());
            if (updated == 1) {
                GenerationJob running = jobs.findById(picked.id()).orElseThrow();
                lastClaimedKind.put(nodeId, running.kind());
                emit(running, Map.of("type", "job.queued", "state", "running"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("job", workerJobView(running));
                // 服务地址（配音/配乐、对口型、整脸口型、文生图、转写、人脸）随任务下发：
                // 换 GPU 服务器时用户在界面里改即可，不用再去改 worker 脚本/环境变量。
                // 空值/未配置的字段已在 servicesWithDefaults 里填了默认值。
                out.put("services", engineSettings.servicesOf(running.createdBy()));
                return out;
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("job", null);
        return empty;
    }

    @Transactional
    /**
     * 进度上报。
     *
     * <p>★ 返回值是**本任务是否已被取消**（cancelRequested）：worker 每几秒上报一次进度，
     * 顺便拿到这个标志 → 立刻中断 ComfyUI 里的 prompt 并退出。
     *
     * <p>为什么必须这样（2026-09-16 结构性缺口）：取消原本只在 API 侧把 state 置 cancelled，
     * worker 完全不知情，会继续等 ComfyUI 跑完（24 步 720p 要 100+ 秒），而 ComfyUI 是**串行**的
     * —— 僵尸 prompt 会把后面真正要跑的任务堵死，表现为「新任务一直 queued / 单张关键帧要等好几分钟」。
     */
    public boolean progress(UUID jobId, int progress, String stage) {
        GenerationJob job = requireRunning(jobId);
        job.progress(progress, stage);
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "job.progress");
        evt.put("progress", progress);
        evt.put("stage", stage == null ? "" : stage);
        GenerationJob saved = jobs.save(job);
        emit(saved, evt);
        return saved.cancelRequested();
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
        String kind = List.of("clip", "still", "voice", "bgm", "portrait", "lipsync", "talk").contains(job.kind()) ? job.kind() : "still";
        // talk（整脸音频驱动）的产物按 lipsync 落库：前端「对口型」Tab 直接可见、可与 LatentSync 版逐版对比；
        // 任务自身的 kind 仍是 talk，payload.source=talk / jawGain 仍在，便于审计。
        if ("talk".equals(kind)) {
            kind = "lipsync";
        }
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
        // ★ P5 显式主体（`shots[].cast`）：UI「本镜主体」勾选的结果，优先于文本匹配。
        //   null = 未指定（按文本自动）；[] = 明确的空镜（不注入任何人物参考图，只留风格图）；
        //   非空 = 本镜就这几个主体。用户实测反馈：不点名时后端会「回退为全部主体」→ 串脸，
        //   个别人物镜甚至看起来没拿到参考图 —— 所以让用户能明确指定。
        java.util.List<String> cast = castOf(shot);
        if (cast != null && cast.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode styleOnly = mapper().createArrayNode();
            for (JsonNode b : plan.path("referenceAssets")) {
                if (b.path("subject").asText("").isBlank()) {
                    styleOnly.add(b.deepCopy());
                }
            }
            log.info("refs: 第{}镜标记为空镜（cast=[]）→ 不注入人物参考图（仅保留 {} 张无主体风格图）",
                    shot == null ? 0 : shot.path("shot_no").asInt(), styleOnly.size());
            RefCtx only = bindFrom(styleOnly, text, workspaceId);
            return enforcePortraits(only == null ? RefCtx.empty() : only, plan, projectId, workspaceId,
                    shot == null ? 0 : shot.path("shot_no").asInt(), text);
        }
        // P13：按「剧情主体」取锚定资产 —— **定妆图优先**（一致性靠它），没有定妆图才退回勾选的素材图
        RefCtx fromSubjects = bindFromSubjects(plan, text, workspaceId, cast);
        RefCtx picked = null;
        if (fromSubjects != null) {
            picked = fromSubjects;
        } else {
            RefCtx fromPlan = bindFrom(plan == null ? null : plan.get("referenceAssets"), text, workspaceId);
            if (fromPlan != null) {
                picked = fromPlan;
            } else {
                RefCtx fromBrief = bindFrom(briefReferenceAssets(userId, workspaceId, projectId, revisionId), text, workspaceId);
                if (fromBrief != null) {
                    picked = fromBrief;
                } else {
                    RefCtx legacy = loadRefs(userId, workspaceId, projectId, revisionId);
                    picked = new RefCtx(legacy.ids(), legacy.keys(), legacy.subjects(), legacy.regions(), "", legacy.primarySubject());
                }
            }
        }
        return enforcePortraits(picked, plan, projectId, workspaceId,
                shot == null ? 0 : shot.path("shot_no").asInt(), text);
    }

    /**
     * ★ 身份锚定必须是**定妆照（kind=portrait）**：缺 / 绑错就“要么明确报错、要么踢掉”，绝不静默拿别的图凑合。
     *
     * <p>为什么（2026-09-15 用户实测：《那宝玉忧恍惚》）：方案里 4 个主体的 portraitAssetId 里
     * 有 3 个指向的是 **kind=reference 的上传素材**（不是定妆照），而资产库里真实存在的定妆照只有
     * 「警幻」1 张 + 「袭人」2 张 —— **根本没有宝玉的定妆照**。旧逻辑在这种情况下会把无关主体的图
     * 当身份锚定交给出图（甚至因为“镜文本没命中主体 → 带回全部”而把袭人的图塞进宝玉的镜）
     * → 关键帧里人物脸型不对、与分镜提示词脱节。
     *
     * <p>规则：
     * <ol>
     *   <li>逐主体找定妆照：
     *       <b>① 方案里 {@code portraitAssetId} 指定的那张就用它</b>（不论资产类型 —— 界面上
     *       「把所选参考图设为定妆照」就是把人选的上传/生成参考图直接指定为定妆照，
     *       资产 kind 仍是 reference；这是**用户明确指定**，必须尊重，不能因为 kind 不是 portrait 就丢掉）；
     *       ② 没绑定时才扫描资产库里 prompt_snapshot 命中主体名的 portrait（生成定妆照时写的是「标准角色设定图：<名字>…」）。</li>
     *   <li>有定妆照 → 用它的 key；</li>
     *   <li>真的没有（没绑定 + 库里也无）时：**主主体**直接报错（要用户去绑定，而不是偷偷生成错脸的图）；
     *       其他主体剔除并记日志。</li>
     * </ol>
     */
    private RefCtx enforcePortraits(RefCtx refs, JsonNode plan, UUID projectId, UUID workspaceId,
                                   int shotNo, String shotText) {
        if (refs == null || refs.subjects() == null || refs.subjects().isEmpty()) {
            return refs;   // 没有主体标注（纯风格参考图）→ 保持原样
        }
        List<String> ids = new java.util.ArrayList<>();
        List<String> keys = new java.util.ArrayList<>();
        List<String> subjects = new java.util.ArrayList<>();
        List<String> regions = new java.util.ArrayList<>();
        List<String> missing = new java.util.ArrayList<>();
        List<String> fixed = new java.util.ArrayList<>();
        for (int i = 0; i < refs.subjects().size(); i++) {
            String name = refs.subjects().get(i);
            if (name == null || name.isBlank()) {
                // 无主体标注的通用参考图：保留（它不是“某人的定妆照”）
                ids.add(i < refs.ids().size() ? refs.ids().get(i) : null);
                keys.add(i < refs.keys().size() ? refs.keys().get(i) : null);
                subjects.add(name == null ? "" : name);
                regions.add(i < refs.regions().size() ? refs.regions().get(i) : null);
                continue;
            }
            studio.weaveora.asset.domain.Asset p = portraitOf(plan, projectId, workspaceId, name);
            if (p == null) {
                missing.add(name);
                continue;                       // 没定妆照 → 不能拿别的图冒充当身份锚定
            }
            String oldKey = i < refs.keys().size() ? refs.keys().get(i) : null;
            if (oldKey != null && !oldKey.equals(p.storageKey())) {
                fixed.add(name);                // 原来绑的不是定妆照 → 改用真定妆照
            }
            ids.add(p.id().toString());
            keys.add(p.storageKey());
            subjects.add(name);
            regions.add(i < refs.regions().size() ? refs.regions().get(i) : null);
        }
        String primary = refs.primarySubject();
        boolean primaryMissing = primary != null && !primary.isBlank() && missing.contains(primary);
        if (primaryMissing) {
            throw new BizException(ErrorCode.VALIDATION,
                    (shotNo > 0 ? "第" + shotNo + "镜" : "本镜") + "主体「" + primary + "」没有可用的**定妆照**，已中止出图（不静默用别的图凑合）。\n"
                    + "  修法：到「资产库 → 人物」给「" + primary + "」生成/上传一张定妆照并在方案里绑定；"
                    + "或到「方案 → 主体」把 portrait 绑定改成该主体的定妆照。\n"
                    + (missing.size() > 1 ? "  另外这些主体也缺定妆照：" + String.join("、", missing) : ""));
        }
        if (!missing.isEmpty()) {
            log.warn("refs: 第{}镜 这些主体缺定妆照，已从锚定里剔除（避免拿错人的图）：{}", shotNo, missing);
        }
        if (!fixed.isEmpty()) {
            log.info("refs: 第{}镜 这些主体的绑定不是定妆照，已自动改用其定妆照：{}", shotNo, fixed);
        }
        if (keys.isEmpty()) {
            return RefCtx.empty();   // 剔除后没有可用锚定
        }
        StringBuilder mapping = new StringBuilder();
        for (int i = 0; i < subjects.size(); i++) {
            if (subjects.get(i).isBlank()) continue;
            if (mapping.length() > 0) mapping.append(", ");
            mapping.append("image").append(i + 1).append(" = ").append(subjects.get(i));
        }
        // 语言跟随镜文本：整套中文提示词时不要在这里塞英文句子（用户实测报过中英混杂）
        String anchor = mapping.length() > 0
                ? refAnchor(mapping.toString(), isZhText(shotText))
                : refs.anchor();
        return new RefCtx(ids, keys, subjects, regions, anchor,
                (primary == null || primary.isBlank()) ? refs.primarySubject() : primary);
    }

    /**
     * 主体 → 可用定妆照；找不到返回 null。
     *
     * <p>① **方案 {@code subjects[].portraitAssetId} 指定的那张**（只要资产存在就用 —— 不论 kind）：
     *   界面「把所选参考图设为定妆照」就是把人选的上传/生成参考图直接指定为定妆照，这是用户明确指定，
     *   不能因为它的 kind 是 reference 就当成“没定妆照”（否则会出现“UI 里明明显示了头像、出图却说缺定妆照”的矛盾）。
     *   kind 不是 portrait 时只记一条 info，便于排查。
     * <p>② 没绑定时才扫本项目 portrait 资产，看 {@code prompt_snapshot} 文本里是否命中主体名。
     */
    private studio.weaveora.asset.domain.Asset portraitOf(JsonNode plan, UUID projectId, UUID workspaceId,
                                                         String subject) {
        JsonNode p = plan == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : plan;
        // ★ 别称也认（2026-09-16 用户实测第 4 镜）：方案里主体叫「宝玉」、参考图上标的是「宝二爷」时，
        //   按名字精确匹配会当成两个主体 → 别名那个找不到定妆照 → 那一镜丢身份锚定。
        //   这里按「本名或别称」都算命中（与 PlanSubjects.parse 的归并同口径）。
        List<studio.weaveora.director.plan.PlanSubjects.Subject> subs =
                studio.weaveora.director.plan.PlanSubjects.parse(p);
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : subs) {
            if (!studio.weaveora.director.plan.PlanSubjects.isSameSubject(sub, subject)) {
                continue;
            }
            String pid = sub.portraitAssetId();
            if (pid == null || pid.isBlank()) {
                continue;
            }
            try {
                var a = assetRepo.findByIdAndWorkspaceId(UUID.fromString(pid), workspaceId).orElse(null);
                if (a != null && projectId.equals(a.projectId())) {
                    if (!"portrait".equals(a.kind())) {
                        log.info("refs: 主体「{}」的定妆照是本地上传/参考图（kind={}，用户指定）→ 照用不误",
                                subject, a.kind());
                    } else if (!sub.name().equals(subject)) {
                        log.info("refs: 「{}」按别称命中主体「{}」的定妆照", subject, sub.name());
                    }
                    return a;
                }
            } catch (IllegalArgumentException ignored) {
                // 脏数据 → 继续下面扫描
            }
        }
        // 兜底扫描：资产 prompt_snapshot 里命中**主体名或任一别称**都算（原来只按传入名精确包含）
        List<String> tokens = new ArrayList<>();
        tokens.add(subject);
        for (studio.weaveora.director.plan.PlanSubjects.Subject sub : subs) {
            if (studio.weaveora.director.plan.PlanSubjects.isSameSubject(sub, subject)) {
                tokens.add(sub.name());
                tokens.addAll(sub.aliases());
            }
        }
        for (studio.weaveora.asset.domain.Asset a : assetRepo
                .findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(projectId, workspaceId, "portrait")) {
            com.fasterxml.jackson.databind.JsonNode snap = a.promptSnapshot();
            String txt = snap == null ? "" : snap.toString();
            for (String tk : tokens) {
                if (tk != null && !tk.isBlank() && txt.contains(tk)) {
                    log.info("refs: 「{}」兜底扫描命中定妆照（快照里出现「{}」）", subject, tk);
                    return a;
                }
            }
        }
        return null;
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
    private RefCtx bindFromSubjects(JsonNode plan, String text, UUID workspaceId,
                                    java.util.List<String> cast) {
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
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> picked;
        if (cast != null && !cast.isEmpty()) {
            // 用户显式勾选优先：镜文本经常写不出全名（甚至只有环境描写），文本匹配会漏
            picked = enabled.stream().filter(s -> cast.contains(s.name())).toList();
            log.info("refs: 本镜主体由方案显式指定（cast）→ {}/{} 命中：{}",
                    picked.size(), cast.size(), picked.stream().map(studio.weaveora.director.plan.PlanSubjects.Subject::name).toList());
            if (picked.isEmpty()) {
                log.info("refs: cast 里的名字对不上任何已启用主体（可能改名）→ 退回镜文本匹配");
            }
        } else {
            picked = java.util.List.of();
        }
        if (picked.isEmpty()) {
            picked = enabled.stream()
                    .filter(s -> studio.weaveora.director.plan.PlanSubjects.matches(t, s))
                    .toList();
        }
        if (picked.isEmpty()) {
            picked = enabled;
            log.info("refs: 镜文本未命中任何主体，回退为全部 {} 个主体（{}）——建议在镜卡勾选「本镜主体」",
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
        StringBuilder detail = new StringBuilder();
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
            // imageN 口径：与 worker 的 LoadImage 槽位顺序一致（见 comfy_client._wf_set_image）
            if (mapping.length() > 0) {
                mapping.append(", ");
            }
            mapping.append("image").append(keys.size()).append(" = ").append(sub.name());
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(sub.name()).append(portrait ? "(定妆图)" : "(素材图)");
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
        String anchor = refAnchor(mapping.toString(), isZhText(t));
        log.info("refs: 本镜锚定 {} 张（{}）primary={}", keys.size(), detail, primary);
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
                if (mapping.length() > 0) mapping.append(", ");
                mapping.append("image").append(keys.size()).append(" = ").append(subject);
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
        // 位置句不再拼进 anchor（统一由 applyLayoutRegions 出，避免同一件事在正词里写两遍）
        String anchor = refAnchor(mapping.toString(), isZhText(t));
        return new RefCtx(okIds, keys, subjects, regions, anchor, primary);
    }

    /**
     * {@code shots[].cast}：本镜出镜主体（UI「本镜主体」勾选）。
     *
     * @return null = 未指定（按镜文本自动匹配）；空 list = 明确的空镜（不注入人物参考图）；
     *         非空 = 本镜就这几个主体
     */
    static java.util.List<String> castOf(JsonNode shot) {
        if (shot == null) {
            return null;
        }
        JsonNode c = shot.path("cast");
        if (!c.isArray()) {
            return null;
        }
        java.util.List<String> out = new ArrayList<>();
        for (JsonNode n : c) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
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

    /**
     * 提示词语言探测（P5，2026-09-16）：用户可能整套用中文（AI 更新提示词选「中文」）也可能是英文。
     * 系统**追加**的锚定句/负面守卫必须跟随用户提示词的语言，否则会出现「一半中文一半英文」
     * （用户实测报过：选了中文，负词里还留着英文负面项）。
     *
     * <p>判据：汉字数 ≥ 6 **且** 汉字在「汉字+拉丁字母」中占比 ≥ 20%。
     * 这样「英文提示词里带中文角色名（Baoyu (宝玉)）」不会误判为中文。
     */
    static boolean isZhText(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int cjk = 0, latin = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        return cjk >= 6 && cjk * 100 >= (cjk + latin) * 20;
    }

    /**
     * 参考图锚定句（追加到正词）。
     *
     * @param slots 每个参考图的 “imageN = 主体名” 片段（按 worker 的 LoadImage 槽位顺序拼好）
     * @param zh    是否用中文（跟随镜文本语言）
     */
    static String refAnchor(String slots, boolean zh) {
        // ★ 2026-09-16：这里**不再**列 imageN = 主体名 —— 那个清单连同位置一起由
        //   applyLayoutRegions 一次写清（用户反馈：同一件事在正词里出现两遍、排序写法不一致，
        //   模型会更难分清谁对应哪张图）。本方法只负责「身份约束」这一件事。
        return zh
                ? " 每个角色的身份、面容与服饰必须严格以其自己的参考图为依据；角色之间必须互相区分，"
                  + "禁止共用、混合或互换面容。"
                : " Each character's identity, face and costume must strictly follow its own reference image;"
                  + " keep the characters distinct and do not share, blend or swap their faces.";
    }

    /** 多主体时追加“防串脸”负词（语言跟随负词本身）。 */
    private static String negWithRefGuard(String neg, RefCtx refs) {
        if (refs == null) return neg;
        long distinct = refs.subjects().stream().filter(s -> s != null && !s.isBlank()).distinct().count();
        if (distinct <= 1) return neg;
        String extra = isZhText(neg)
                ? "同一张脸重复, 换脸, 同一人出现两次, 身份混淆, 复制脸庞"
                : "identical faces, face swap, same person repeated, mixed identities, cloned face";
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
    static final Set<String> SELF_HOSTED_KINDS = Set.of("voice", "bgm", "lipsync", "talk");

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
            // 画面（底片）：默认该镜最新的 motion 片段；没有 motion 就用关键帧静帧（口型模型能处理静帧）。
            //
            // A（逐镜可选）：方案可写 `shots[].lipsync_source = "still" | "clip"`（选镜弹窗每行可切）；
            // C（极端表情自动分流）：没显式指定时，如果这一镜是「惊叫/喊叫/失声…」这类
            //   **底片里嘴本来就大张**的镜头，而有静帧可选 → **默认改用静帧**：静帧只有一张干净的脸，
            //   嘴部状态单一；而 motion 片段里嘴已经在动，LatentSync 得先把嘴合上再按配音重开，
            //   嘴部掩码区大幅形变（2026-09-14《那宝玉恍恍惚惚》第5镜实测「画面被破坏」：
            //   action 宝玉失声惊叫、正词 `his mouth open in a terrified scream`）。
            studio.weaveora.asset.domain.Asset clip = pickNewestAsset(projectId, workspaceId, shotNo, "clip");
            studio.weaveora.asset.domain.Asset stillAsset = pickNewestAsset(projectId, workspaceId, shotNo, "still");
            String wantBase = shot.path("lipsync_source").asText("").trim().toLowerCase();
            // ★ 「片段首帧」底片（2026-09-15 新增）：既能跟随该镜 motion 画面的**画面/风格/人物**
            //   （底片就是片段本身的第一帧），又只有一张静止干净的嘴（避开了“片段里嘴一直在动/
            //   大张 → LatentSync 先把嘴合上再重开 → 嘴部区大幅形变”的老问题）。
            //   用户口径：“对口型要参考 motion 画面、别用新生成的图”。
            boolean firstFrame = "clip_first_frame".equals(wantBase) || "clip_first".equals(wantBase)
                    || "clip_best_frame".equals(wantBase) || "clip_best".equals(wantBase);
            // clip_best_frame：从片段里**挑嘴型最干净的一帧**当静帧底片（第5镜这种整段大张嘴的镜必需）
            boolean bestFrame = "clip_best_frame".equals(wantBase) || "clip_best".equals(wantBase);
            boolean expressionRisk = expressionRisk(shot);
            boolean useStill;
            if (firstFrame) {
                // 用片段首帧 → 必须先有片段；没片段就退回静帧/自动
                useStill = clip == null ? (stillAsset != null) : true;
            } else if ("still".equals(wantBase)) {
                useStill = stillAsset != null;                 // 要静帧：没静帧就退回片段（不静默不生成）
            } else if ("clip".equals(wantBase)) {
                useStill = clip == null;                       // 要片段：没片段就退回静帧
            } else {
                useStill = clip == null || (stillAsset != null && expressionRisk);
            }
            // 片段首帧模式下，底片资产取**片段**（worker 会抽第一帧当静帧用）
            studio.weaveora.asset.domain.Asset base = (firstFrame && clip != null) ? clip
                    : (useStill ? stillAsset : clip);
            if (base == null) {
                skipped.add("第" + shotNo + "镜（缺画面：无 motion 也无关键帧）");
                continue;
            }
            if (useStill && clip != null && !"still".equals(wantBase) && !firstFrame) {
                log.warn("lipsync 第{}镜底片自动改用静帧（expressionRisk={}）—— motion 片段里嘴部已在大幅运动/大张，"
                        + "直接当底片容易把嘴部画坏（如需强制用片段：方案里把 shots[].lipsync_source 设为 clip）",
                        shotNo, expressionRisk);
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
            payload.put("videoKey", base.storageKey());
            payload.put("videoIsStill", useStill);
            payload.put("isStill", useStill);
            // 片段首帧模式：底片是片段，但要让 worker **抽第一帧**当静帧用（并告知前端/审计真实来源）
            payload.put("useFirstFrame", firstFrame && clip != null);
            payload.put("useBestFrame", bestFrame && clip != null);
            // A/B/C 的下发字段：底片来源（给用户看 + worker 报告）+ 是否自动选的 + 极端表情标记
            payload.put("lipsyncSource", bestFrame && clip != null ? "clip_best_frame"
                    : (firstFrame && clip != null ? "clip_first_frame"
                    : (useStill ? "still" : "clip")));
            payload.put("lipsyncSourceAuto", wantBase.isEmpty());
            payload.put("expressionRisk", expressionRisk);
            payload.put("lipsyncForce", shot.path("lipsync_force").asBoolean(false));
            com.fasterxml.jackson.databind.node.ArrayNode vk = payload.putArray("voiceKeys");
            for (studio.weaveora.asset.domain.Asset a : voices) {
                vk.add(a.storageKey());
            }
            payload.put("voiceCount", voices.size());
            payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
            payload.put("lipSync", shot.path("lip_sync").asBoolean(true));
            // P13：说话人 + 分段信息 —— 多人对话必须知道「哪段台词是谁说的、对应哪段音频」，
            // 否则口型会把台词全配到同一张脸上（实测第4镜：宝玉/警幻 2 段台词全给了同一人，
            // 而且逐帧取最大脸还会中途换人 → 面部画面坏掉）。
            //
            // 数据源用 AudioPlan.lines(shot)：它的**下标就是 line_index**（配音资产按它写入），
            // 所以 segments[k].voiceKey 能精确关联到该段配音。
            ObjectNode speakersNode = payload.putObject("speakers");   // {说话人: 定妆照 storageKey}
            var segmentsNode = payload.putArray("segments");
            java.util.LinkedHashSet<String> speakerNames = new java.util.LinkedHashSet<>();
            java.util.List<studio.weaveora.director.plan.AudioPlan.Line> lines =
                    studio.weaveora.director.plan.AudioPlan.lines(shot);
            java.util.Map<Integer, studio.weaveora.asset.domain.Asset> voiceByLine = new java.util.HashMap<>();
            for (studio.weaveora.asset.domain.Asset a : voices) {
                Object li = studio.weaveora.asset.AssetService.lineIndexOf(a);
                if (li != null) {
                    voiceByLine.put((Integer) li, a);
                }
            }
            double shotDur = shot.path("duration_sec").asDouble(3);
            for (int k = 0; k < lines.size(); k++) {
                studio.weaveora.director.plan.AudioPlan.Line line = lines.get(k);
                String who = line.subject() == null ? "" : line.subject().trim();
                if (!line.dialogue() || who.isEmpty()) {
                    continue;   // 旁白没有说话人，不需要驱动嘴型（那段时间保留原帧）
                }
                double begin = Math.max(0, line.atSec());
                double end = line.hasEnd() ? line.endSec() : shotDur;
                if (end <= begin) {
                    continue;
                }
                speakerNames.add(who);
                ObjectNode seg = segmentsNode.addObject();
                seg.put("subject", who);
                seg.put("lineIndex", k);
                seg.put("startMs", (int) Math.round(begin * 1000));
                seg.put("endMs", (int) Math.round(end * 1000));
                studio.weaveora.asset.domain.Asset va = voiceByLine.get(k);
                if (va != null) {
                    seg.put("voiceKey", va.storageKey());
                }
            }
            // P13：用户在预览图上**点选**的人脸位置（`shots[].lipsync_targets = {主体: {x,y}}`，归一化 0–1）。
            // 为什么需要：实测在 480p/AI 古风这类风格化素材上，人脸识别（ArcFace）区分度崩了
            // —— 同一个人只有 0.2 上下、且宝玉/警幻/袭人互相混淆，任何阈值都把噪声当信号。
            // 用户点一下“谁是警幻”是最可靠的信号，且完全确定、不受画风影响。
            JsonNode targetsNode = shot.path("lipsync_targets");
            ObjectNode hintsNode = payload.putObject("faceHints");
            for (String who : speakerNames) {
                JsonNode t = targetsNode.path(who);
                if (t.isObject() && t.hasNonNull("x") && t.hasNonNull("y")) {
                    ObjectNode h = hintsNode.putObject(who);
                    h.put("x", t.path("x").asDouble());
                    h.put("y", t.path("y").asDouble());
                }
            }
            payload.put("faceHintCount", hintsNode.size());
            payload.put("speakerCount", speakerNames.size());
            payload.put("speakerNames", String.join("、", speakerNames));
            payload.put("segmentCount", segmentsNode.size());
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

    /**
     * C：该镜是不是「底片里嘴本来就大张」的高危镜头（惊叫/喊叫/失声…）。
     *
     * <p>为什么要判定：LatentSync 是「先合上再重开」的嘴部重绘。底片里人本来就在喊/惊叫时，
     * 嘴部掩码区形变最大，实测会把画面搞坏。线上实例（2026-09-14《那宝玉恍恍惚惚》）：
     * 第 5 镜 action「…抓住宝玉将他拖下溪去，宝玉失声惊叫」+ 正词 `his mouth open in a terrified
     * scream`，实测底片 mouth_open 静帧 1.232 / 片段 1.238（同项目**平静脸只有 0.44~0.66**，
     * 它确实是「大张口」→ B 会拒）；第 6 镜「梦醒…失声喊叫」文字口径也命中，但实测 0.589/0.776
     * 落在正常区间 —— B 不会拦，只是 C 保守地也给它静帧底片 + lips_expression 降到节点下限 1.0。
     *
     * <p>只看文字信号（不做图像分析）：判错的代价 = 默认多走一次静帧底片（静帧本来是合法底片），
     * 可接受；而漏判的代价是一段坏画面 + 十几分钟 GPU。用户仍可在选镜弹窗里手动改回片段。
     */
    static boolean expressionRisk(JsonNode shot) {
        StringBuilder sb = new StringBuilder();
        for (String f : new String[]{"action", "positive_prompt", "narration"}) {
            sb.append(shot.path(f).asText("")).append(' ');
        }
        for (JsonNode n : shot.path("narrations")) {
            sb.append(n.path("text").asText("")).append(' ');
        }
        String t = sb.toString().toLowerCase();
        if (t.isEmpty()) {
            return false;
        }
        return EXPRESSION_RISK_RE.matcher(t).find();
    }

    /** 喊叫/惊恐/大张口类词表（中英兼容）。 */
    private static final java.util.regex.Pattern EXPRESSION_RISK_RE = java.util.regex.Pattern.compile(
            "喊叫|尖叫|惊叫|惊呼|失声|呼喊|大叫|吼叫|嚎叫|嘶喊|大喊|张口|张嘴|大张|口大张"
            + "|scream|shriek|shout|yell|cry out|wail|mouth wide|wide.open.mouth|open mouth|mouth open");

    /**
     * jaw-lip（整脸音频驱动）：把该镜的**静帧**与该镜配音交给 talk 服务（GPU 机上的 EchoMimicV3 + 下颌曲线层）
     * → 得到「嘴/下颌/表情一起重新表演」的片段。
     *
     * <p>与 {@link #createLipsyncJobs} 的分工：
     * <ul>
     *   <li>lipsync（LatentSync）= 在**既有画面**上只换嘴 → 保留运镜/表演，但喊叫这类「下颌大开」做不了；</li>
     *   <li>talk（EchoMimic）= 从**静帧**重新表演 → 擅长喊叫/极端表情，但会重演镜头（运镜不保留）。</li>
     * </ul>
     *
     * <p>产物按 lipsync 落库（前端「对口型」Tab 可见），任务本身 kind=talk；payload 带 jawGain 等可审计参数：
     * {@code jaw_gain=1.0} 表示原生不动，{@code 1.25} 为建议的「喊叫增强」档（曲线层按音频响度额外下拉下颌）。
     */
    private List<GenerationJob> createTalkJobs(UUID workspaceId, UUID projectId, CreateJobRequest req,
                                              JsonNode plan, int revisionNo, UUID userId) {
        List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(),
                "clip", req.shotNos(), Boolean.TRUE.equals(req.includeLocked()));
        if (shotIds.isEmpty()) {
            throw new BizException(ErrorCode.SHOT_NOT_APPROVED, emptyShotReason(workspaceId, projectId, req));
        }
        // talk 服务在 GPU 机上（独立 venv），云节点不会认领 → 固定 gpu（与 lipsync 同理）
        String engineRoute = routeForKind("talk", engineSettings.resolveEngine(userId, "clip"));
        List<GenerationJob> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (UUID shotId : shotIds) {
            JsonNode shot = shotOf(plan, shotId);
            if (shot == null) {
                continue;
            }
            int shotNo = shot.path("shot_no").asInt();
            // 底图：必须是**静帧**（整脸生成从一张图起）——没有就跳过并说清原因
            studio.weaveora.asset.domain.Asset still = pickNewestAsset(projectId, workspaceId, shotNo, "still");
            if (still == null) {
                skipped.add("第" + shotNo + "镜（缺静帧：整脸口型要一张静帧当底图，先出关键帧）");
                continue;
            }
            List<studio.weaveora.asset.domain.Asset> voices = newestVoicePerLine(projectId, workspaceId, shotNo);
            if (voices.isEmpty()) {
                skipped.add("第" + shotNo + "镜（缺配音）");
                continue;
            }
            ObjectNode payload = mapper().createObjectNode();
            payload.put("kind", "talk");
            payload.put("source", "talk");
            payload.put("mode", "video");
            payload.put("revisionId", req.revisionId().toString());
            payload.put("shotId", shotId.toString());
            payload.put("shot_no", shotNo);
            payload.put("imageKey", still.storageKey());
            payload.put("prompt", shot.path("positive_prompt").asText(""));
            com.fasterxml.jackson.databind.node.ArrayNode vk = payload.putArray("voiceKeys");
            for (studio.weaveora.asset.domain.Asset a : voices) {
                vk.add(a.storageKey());
            }
            payload.put("voiceCount", voices.size());
            payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
            // 下颌曲线层参数：1.0=原生不动；1.25=建议增强档（可按镜在方案里覆盖 shots[].talk_gain）
            payload.put("jawGain", shot.path("talk_gain").asDouble(1.0));
            payload.put("jawStrength", shot.path("talk_strength").asDouble(0.35));
            payload.put("jawAttackMs", shot.path("talk_attack_ms").asDouble(50));
            payload.put("jawDecayMs", shot.path("talk_decay_ms").asDouble(130));
            // 显存/速度档：48G 卡实测 768²×113 帧会 OOM；默认 512²+81 帧+8 步（≈2–5 分钟/镜）
            payload.put("talkSteps", shot.path("talk_steps").asInt(8));
            payload.put("gpuExclusive", true);
            payload.put("gpuHint", "整脸口型（EchoMimic）与出片互斥：单卡串行，勿与 A14B 出片并发");
            created.add(createOne(workspaceId, projectId, req.revisionId(), shotId,
                    PRESET_CLIP, "talk", payload, userId, engineRoute));
        }
        if (created.isEmpty()) {
            String why = skipped.isEmpty() ? "" : "（" + String.join("、", skipped.subList(0, Math.min(4, skipped.size())))
                    + (skipped.size() > 4 ? " 等" : "") + "）";
            throw new BizException(ErrorCode.VALIDATION,
                    "没有可做整脸口型的镜头：需要该镜已有**静帧**且已生成配音" + why);
        }
        if (!skipped.isEmpty()) {
            log.warn("talk 跳过了 {} 个镜：{}", skipped.size(), skipped);
        }
        log.warn("talk jobs created project={} count={} —— 与出片共卡，需串行", projectId, created.size());
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
        if (sub != null && explicitRefs == 0) {
            // 没显式指定参考图 → 用该主体自己的：当前定妆照 + 方案里勾选的 refs
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
        } else if (explicitRefs > 0) {
            // Weaveora 2026-09-13：用户显式选了参考图 → **只用这些**，不再自动追加。
            //
            // 为什么必须这样做（用户报「换一版定妆照没使用我选的参考图」）：
            //   旧逻辑会把 sub.portraitAssetId()（= 用户正要替换掉的旧定妆照）和主体 refs
            //   无条件追加到末尾，参考图变成「用户选的 + 旧定妆照 + 其它 refs」；
            //   图片模型参考图越多越互相拉扯，旧脸往往把新脸压回去 → 新参考图看着“没生效”。
            //   另外前端那个过滤器把「没有 subject 标记」的图也当成本主体的（而导入的定妆照
            //   本来就没 subject）→ 其它角色的图也会混进来（实测把袭人的图带进了警幻的生成）。
            log.info("portrait explicit refs project={} subject={} refs={}（不追加旧定妆照与主体 refs）",
                    projectId, subject, explicitRefs);
        }
        // 去重保序（用户给的顺序即权重顺序，模型通常更看前面的）
        java.util.LinkedHashSet<UUID> dedup = new java.util.LinkedHashSet<>(ids);
        ids = new ArrayList<>(dedup);
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
        // ★ P13b（2026-09-16 用户要求）：定妆图也要像分镜一样**先弹正/负向提示词让用户改**。
        //   用户确认过的就照用（不再被默认模板覆盖）；没给（旧链路 / 直接调 API）才用默认模板。
        String positivePrompt = req.positivePrompt() == null ? "" : req.positivePrompt().trim();
        String negativePrompt = req.negativePrompt() == null ? "" : req.negativePrompt().trim();
        if (positivePrompt.isEmpty()) {
            positivePrompt = studio.weaveora.director.SubjectPrompts.portraitPrompt(
                    subject, sub == null ? null : sub.kind(), keys.size());
        } else {
            log.info("portrait 用户自定义正词 project={} subject={} len={}", projectId, subject, positivePrompt.length());
        }
        if (negativePrompt.isEmpty()) {
            negativePrompt = studio.weaveora.director.SubjectPrompts.portraitNegativePrompt();
        }
        payload.put("positive_prompt", positivePrompt);
        payload.put("negative_prompt", negativePrompt);
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
                                        StyleTemplate style, RefCtx refs, int keyframeIndex) {
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kind", kind);
        payload.put("mode", "video");
        payload.put("revisionId", revisionId.toString());
        payload.put("shotId", shotId.toString());
        payload.put("shot_no", shot.path("shot_no").asInt());
        String pos = styledPositive(style, positiveRaw);
        // ★ motion（clip）：把镜头的 **action** 也喂给模型。
        //   线上反馈（2026-09-15）：「宝玉靠向可卿，可卿躲开」这种动作根本没被理解 ——
        //   因为正词里只有画风/场景/参考图身份约束，动作中文只存在计划的 action 字段里。
        //   umt5 是多语言编码器，中文动作可直接拼进正词。
        if ("clip".equals(kind)) {
            String act = shot.path("action").asText("").trim();
            if (!act.isEmpty() && !pos.contains(act)) {
                pos = pos + "\nAction: " + act;
            }
        }
        if (refs != null && !refs.anchor().isBlank()) pos = pos + refs.anchor();
        payload.put("positive_prompt", pos);
        // P-motion：运动（clip）额外追加静态抑制负面词，只作用于 clip（关键帧 still 不受影响）
        String neg = negWithRefGuard(styledNegative(style, shot.path("negative_prompt").asText("")), refs);
        if ("clip".equals(kind)) {
            neg = studio.weaveora.director.plan.DirectorPlanValidator.mergeNegative(
                    neg, isZhText(neg)
                            ? studio.weaveora.director.plan.DirectorPlanValidator.MOTION_NEGATIVE_ZH
                            : studio.weaveora.director.plan.DirectorPlanValidator.MOTION_NEGATIVE);
        }
        payload.put("negative_prompt", neg);
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
        // ★ 位置优先（2026-09-16）：把「每个主体在画面里的位置」写进 referenceRegions，
        //   供 worker 做**区域条件**（ConditioningSetAreaPercentage）。来源优先级：
        //     ⓪ shots[].keyframes[帧].layout = [{subject,x,y,w,h}]（帧级；运镜镜头的每一帧可各摆各的）
        //     ① shots[].layout = [{subject,x,y,w,h}]（镜级；该镜所有帧的默认，UI 位置总控）
        //     ② 方案级 referenceAssets[].region / subjects[].refs[].region（「位置预览」卡的方案默认值）
        //     ③ shots[].lipsync_targets = {subject:{x,y}}（预览图点选，只有点 → 给默认框）
        //   与参考图顺序（refs.subjects()）严格对齐，没位置的填 null。
        //   同时把「image1=谁、image2=谁」写进正词（用户要求：positive_prompt 必须点名主体）。
        applyLayoutRegions(payload, plan, shot, keyframeIndex, refs);
        return payload;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * 位置 → referenceRegions（路线 B 备用）+ 正词里的「参考图→主体」映射（路线 A 生效中）。
     *
     * <p>位置三档来源，优先级从高到低（见 videoShotPayload 调用处注释）：
     * ① {@code shots[].layout} ②方案级 {@code referenceAssets[].region} / {@code subjects[].refs[].region}
     * ③ {@code shots[].lipsync_targets}。
     *
     * <p><b>为什么②要直接读 plan 而不是 {@code refs.regions()}</b>：走「剧情主体」路径
     * （{@code bindFromSubjects}，P13 起是默认路径）时 refs.regions() 恒为 null —— 它在按定妆照
     * 重建参考图列表，区域信息就丢了。所以这里从 plan 里按主体名重新取（两处都收：
     * {@code referenceAssets[{subject,region}]} 与 {@code subjects[{name,refs[{region}]}]}）。
     *
     * <p><b>为什么必须有「image1=谁」这段提示词</b>：多主体同框时，Edit 模型拿到 image1/image2
     * 却不知道哪张脸对应哪个角色名，只能自己猜 → 串脸/换人。用户要求 positive_prompt 必须点名主体，
     * 这里做**后端兜底**（不依赖 LLM 是否听话）：只要绑定了参考图，就把槽位映射写进正词。
     * 槽位与 worker 的 LoadImage 顺序一致（image1=referenceKeys[0] …，见 comfy_client._wf_set_image）。
     */
    static void applyLayoutRegions(ObjectNode payload, JsonNode plan, JsonNode shot, int keyframeIndex,
                                   RefCtx refs) {
        if (refs == null || refs.subjects() == null || refs.subjects().isEmpty()) {
            return;
        }
        java.util.Map<String, double[]> pos = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> src = new java.util.LinkedHashMap<>();
        // ② 方案级区域（「位置总控」卡的「方案默认（全片）」）
        collectPlanRegions(pos, src, plan == null ? null : plan.path("referenceAssets"));
        JsonNode planSubjects = plan == null ? null : plan.path("subjects");
        if (planSubjects != null && planSubjects.isArray()) {
            for (JsonNode sub : planSubjects) {
                String name = sub.path("name").asText("").trim();
                if (name.isEmpty()) {
                    continue;
                }
                // ②-a 新增：subjects[].region（按**主体**存，不依赖有没有素材图）。
                //     必须有它：4 个主体都只绑定定妆照、refs 为空时，方案级位置**无处可存**。
                double[] own = jsonBox(sub.path("region"));
                if (own != null && !pos.containsKey(name)) {
                    pos.put(name, own);
                    src.put(name, "plan");
                }
                // ②-b 兼容：subjects[].refs[].region（按素材图存的老口径）
                for (JsonNode ref : sub.path("refs")) {
                    double[] p = jsonBox(ref.path("region"));
                    if (p != null && !pos.containsKey(name)) {
                        pos.put(name, p);
                        src.put(name, "plan-ref");
                    }
                }
            }
        }
        // ②b 兼容旧路径：refs.regions() 里可能已带方案级区域（新「剧情主体」路径恒为 null）
        java.util.List<String> csvs = refs.regions();
        for (int i = 0; i < refs.subjects().size(); i++) {
            String s = refs.subjects().get(i);
            if (s == null || s.isBlank() || pos.containsKey(s)) {
                continue;
            }
            double[] p = csvBox(i < csvs.size() ? csvs.get(i) : null);
            if (p != null) {
                pos.put(s, p);
                src.put(s, "plan");
            }
        }
        // ③ 预览图点选：只有坐标 → 以该点为中心给默认框（宽 0.30 / 高 0.45）
        JsonNode targets = shot.path("lipsync_targets");
        if (targets.isObject()) {
            java.util.Iterator<String> names = targets.fieldNames();
            while (names.hasNext()) {
                String s = names.next();
                if (pos.containsKey(s)) {
                    continue;
                }
                JsonNode t = targets.path(s);
                double x = t.path("x").asDouble(-1), y = t.path("y").asDouble(-1);
                if (x < 0 || y < 0) {
                    continue;
                }
                pos.put(s, new double[]{Math.max(0, x - 0.15), Math.max(0, y - 0.20), 0.30, 0.45});
                src.put(s, "click");
            }
        }
        // ① 逐镜位置编辑器（最高优先，覆盖上面两档）
        JsonNode layout = shot.path("layout");
        if (layout.isArray()) {
            for (JsonNode it : layout) {
                String s = it.path("subject").asText("").trim();
                double x = it.path("x").asDouble(-1), y = it.path("y").asDouble(-1);
                double w = it.path("w").asDouble(-1), h = it.path("h").asDouble(-1);
                if (!s.isEmpty() && x >= 0 && y >= 0 && w > 0 && h > 0) {
                    pos.put(s, new double[]{x, y, w, h});
                    src.put(s, "layout");
                }
            }
        }
        // ⓪ 帧级位置（最高优先）：运镜镜头每一帧可以各摆各的（shots[].keyframes[fi].layout）
        if (keyframeIndex >= 0) {
            JsonNode frames = shot.path("keyframes");
            if (frames.isArray() && keyframeIndex < frames.size()) {
                JsonNode fl = frames.get(keyframeIndex).path("layout");
                if (fl.isArray()) {
                    for (JsonNode it : fl) {
                        String sub = it.path("subject").asText("").trim();
                        double x = it.path("x").asDouble(-1), y = it.path("y").asDouble(-1);
                        double w = it.path("w").asDouble(-1), h = it.path("h").asDouble(-1);
                        if (!sub.isEmpty() && x >= 0 && y >= 0 && w > 0 && h > 0) {
                            pos.put(sub, new double[]{x, y, w, h});
                            src.put(sub, "frame" + keyframeIndex);
                        }
                    }
                }
            }
        }
        if (!pos.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode regions = payload.putArray("referenceRegions");
            for (String subject : refs.subjects()) {
                double[] p = (subject == null) ? null : pos.get(subject);
                if (p == null) {
                    regions.addNull();
                    continue;
                }
                regions.addObject()
                        .put("x", clamp01(p[0])).put("y", clamp01(p[1]))
                        .put("w", clamp01(p[2])).put("h", clamp01(p[3]));
            }
            log.info("refs: 位置下发 {} 个主体（来源 {}）：{}", pos.size(), src, pos.keySet());
        }

        // ★ 路线 A（2026-09-16）：位置先以**提示词**形式生效。
        //   为什么不用区域条件（referenceRegions → ConditioningSetAreaPercentage + ConditioningCombine）：
        //   Qwen-Image-Edit 的 conditioning（TextEncodeQwenImageEditPlus，带参考图 latent）被区域包裹/合并后，
        //   KSampler 会抛 `IndexError: tuple index out of range`（实测两轮），所以先走提示词描述；
        //   referenceRegions 仍然保留，供后续路线 B（离线调通后再切）。
        //
        //   注：「imageN = 谁」的点名与位置**由这里一次写清**（refAnchor 只保留身份约束）。
        //   为什么要合并成一句（2026-09-16 用户反馈）：原来「参考图对应关系」和「主体位置」
        //   两句都带 `imageN = 主体名`，写法/排序略有差别 → 模型更难分清谁对应哪张图。
        String cur = payload.path("positive_prompt").asText("");
        boolean zh = isZhText(cur);
        // 即使一个位置都没设，也照写「imageN = 主体名」—— 这是「positive_prompt 必须点名主体」
        // 的后端兜底（不依赖 LLM 听话）。无 subject 名的风格参考图不点名（否则凭空造角色）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.subjects().size(); i++) {
            String name = refs.subjects().get(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(zh ? "；" : "; ");
            }
            double[] p = pos.get(name);
            // ★ 2026-09-16：槽位口径用**模型自己的命名**。TextEncodeQwenImageEditPlus 内部把参考图
            //   拼成 `Picture 1: <|vision_start|>…`（见节点源码 _get_qwen_prompt_embeds），
            //   官方 2511 模板/社区写法也都是 `Picture 1` / `Picture 2`；我们之前只写 `image1`，
            //   模型未必能把两者对上 → 多主体时张冠李戴。现在两种名字并列写，怎么读都不歧义。
            sb.append("Picture ").append(i + 1).append(" (image").append(i + 1).append(") = ").append(name);
            if (p != null) {
                sb.append(zh ? "（" : " (")
                  .append(zh ? posHintZh(p[0], p[1]) : posHint(p[0], p[1]))
                  .append(zh ? "，x=" : ", x=").append(fmt2(p[0]))
                  .append(zh ? "，y=" : ", y=").append(fmt2(p[1]))
                  .append(zh ? "，框 " : ", box ").append(fmt2(p[2])).append("x").append(fmt2(p[3]))
                  .append(zh ? "）" : ")");
            }
        }
        if (sb.length() > 0) {
            // ★ 2026-09-16 加「位置以本清单为准」的覆盖句。
            //   用户实测第 4 镜反复「位置错位」的真因就在这里：镜文案由 LLM 写成
            //   「宝玉在前景中央…可卿在宝玉右侧…警幻居后景」，而用户在「位置总控」里设的框是
            //   x=0.24 / x=0.06 / x=0.65 —— 两套方位**直接矛盾**，模型只能猜，于是左右/前后乱。
            //   现在明确：描述给出动作与氛围，位置/大小以本清单为最终裁定。
            String add = zh
                    ? "\n参考图与主体对应（按送入顺序）：" + sb
                      + "。请严格按这个对应关系：每个角色只用自己的参考图，并放在括号里给的位置与相对大小上"
                      + "（归一化画面坐标，原点在左上；y 越小越靠上，框越大越靠近镜头）；角色之间保持明显分开。"
                      + "【位置以本清单为准】上面的动作/氛围描述仅供理解剧情，其中任何方位词（前后景、左右、远近）"
                      + "若与本清单不一致，一律以本清单给出的位置与框大小为准。"
                    : "\nReference mapping (in input order): " + sb
                      + ". Follow it strictly: each character uses only its own reference image and is placed at"
                      + " the given position and relative size (normalized frame coordinates, origin top-left;"
                      + " smaller y = higher in frame, larger box = closer to camera); keep them clearly apart."
                      + " [Authoritative placement] The action/mood description above is for story context only;"
                      + " if any spatial wording in it (foreground/background, left/right, near/far) conflicts with"
                      + " this list, the positions and box sizes given here always win.";
            payload.put("positive_prompt", cur + add);
            log.info("refs: 参考图↔主体（含位置={}）已写入正词：{}", !pos.isEmpty(), sb);
        }
    }

    /** 方案级 region 收集：{@code referenceAssets[{subject,region}]} → pos/src（先到先得）。 */
    private static void collectPlanRegions(java.util.Map<String, double[]> pos,
                                           java.util.Map<String, String> src, JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode b : arr) {
            String s = b.path("subject").asText("").trim();
            if (s.isEmpty()) {
                continue;
            }
            double[] p = jsonBox(b.path("region"));
            if (p != null && !pos.containsKey(s)) {
                pos.put(s, p);
                src.put(s, "plan");
            }
        }
    }

    /** 归一化 region 节点（0–1）→ 框；不是合法框就回 null。 */
    private static double[] jsonBox(JsonNode r) {
        if (r == null || !r.isObject()) {
            return null;
        }
        double x = r.path("x").asDouble(-1), y = r.path("y").asDouble(-1);
        double w = r.path("w").asDouble(-1), h = r.path("h").asDouble(-1);
        return (x < 0 || y < 0 || w <= 0 || h <= 0) ? null : new double[]{x, y, w, h};
    }

    /** 方案级 region 的 "x,y,w,h" csv → 归一化框；非法回 null。 */
    private static double[] csvBox(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        String[] p = csv.split(",");
        if (p.length < 4) {
            return null;
        }
        try {
            double x = Double.parseDouble(p[0].trim()), y = Double.parseDouble(p[1].trim());
            double w = Double.parseDouble(p[2].trim()), h = Double.parseDouble(p[3].trim());
            return (x < 0 || y < 0 || w <= 0 || h <= 0) ? null : new double[]{x, y, w, h};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 0–1 → 两位小数，便于提示词里对齐数字。 */
    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 归一化坐标 → 方位词（左/中/右 + 上/中/下），让模型更容易听懂。 */
    private static String posHint(double x, double y) {
        String h = x < 0.34 ? "left" : (x > 0.66 ? "right" : "center");
        String v = y < 0.34 ? "upper" : (y > 0.66 ? "lower" : "middle");
        return v + "-" + h;
    }

    /** 同上，中文口径（提示词整套中文时保持一致，别中英混杂）。中文方位习惯「左上 / 右下」。 */
    private static String posHintZh(double x, double y) {
        String h = x < 0.34 ? "左" : (x > 0.66 ? "右" : "");
        String v = y < 0.34 ? "上" : (y > 0.66 ? "下" : "");
        if (h.isEmpty() && v.isEmpty()) return "正中";
        if (h.isEmpty()) return v + "方";
        if (v.isEmpty()) return h + "侧";
        return h + v;
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
