package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.director.AiAudioService;
import studio.weaveora.director.DirectorService;
import studio.weaveora.director.PlanReader;
import studio.weaveora.director.plan.AudioPlan;
import studio.weaveora.director.plan.PlanSubjects;
import studio.weaveora.director.plan.ShotTimingPlanner;
import studio.weaveora.engine.EngineSettingsService;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.job.api.CreateJobRequest;
import studio.weaveora.job.api.JobView;
import studio.weaveora.job.domain.ProductionStageApproval;
import studio.weaveora.job.domain.ProductionStageApprovalRepository;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一键成片「制作流程」（P0/P1，2026-09-29）。
 *
 * <p><b>目标 UX</b>：用户只关注剧本 → 系统按<b>引擎能力 + 台词/旁白时长</b>自动拆镜 →
 * 用户依次点「确定」走完 定妆照 → 关键帧 → 运动 → 配音配乐 → 成片。
 *
 * <p><b>设计取舍</b>：阶段是否跑完由**已有的 generations_jobs + assets 派生**（不另做一套会卡死的 saga），
 * 只把「用户的确认动作」落库（{@link ProductionStageApproval}），满足 §0-3 的确认闸门。
 * 这样：任务/资产是唯一真源，重启/多实例都一致；确认按钮只是「批量下发 + 请下一个阶段」。
 *
 * <p><b>门禁</b>（都写死在代码里，不靠 UI）：关键帧要求「人物主体已定妆」；运动要求「关键帧齐」；
 * 配音要求「运动齐」；成片要求「运动齐且配音齐」。
 */
@Service
public class ProductionRunService {

    private static final Logger log = LoggerFactory.getLogger(ProductionRunService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 阶段顺序（用户口径：定妆照 → 关键帧 → 运动 → 配音配乐 → 成片）。 */
    public static final List<String> STAGES = List.of("portraits", "keyframes", "motion", "audio", "render");

    /** 自动台词的上限镜数：超过它不自动跑（一次 LLM/镜，太多会把请求拖太久）。 */
    private static final int AUTO_LINES_MAX_SHOTS = 16;

    private final JobService jobService;
    private final PlanReader planReader;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final AssetRepository assetRepo;
    private final AssetService assetService;
    private final ProductionStageApprovalRepository approvals;
    private final EngineSettingsService engineSettings;
    private final DirectorService directorService;
    private final AiAudioService aiAudioService;
    private final studio.weaveora.export.ConcatService concatService;

    public ProductionRunService(JobService jobService, PlanReader planReader, ProjectContextPort projects,
                                WorkspaceGuard guard, AssetRepository assetRepo, AssetService assetService,
                                ProductionStageApprovalRepository approvals,
                                EngineSettingsService engineSettings, DirectorService directorService,
                                AiAudioService aiAudioService,
                                studio.weaveora.export.ConcatService concatService) {
        this.jobService = jobService;
        this.planReader = planReader;
        this.projects = projects;
        this.guard = guard;
        this.assetRepo = assetRepo;
        this.assetService = assetService;
        this.approvals = approvals;
        this.engineSettings = engineSettings;
        this.directorService = directorService;
        this.aiAudioService = aiAudioService;
        this.concatService = concatService;
    }

    // ------------------------------------------------------------------ 视图

    public record StageView(String stage, String label, String state, int total, int done, int failed,
                           String hint, String approvedAt, boolean canConfirm) {
    }

    public record RunStatus(UUID projectId, UUID revisionId, int revisionNo, String projectStatus,
                            boolean isApprovedRevision,
                            double targetSec, double plannedSec, int shotCount,
                            double maxShotSec, int nativeFps, String motionEngine, String engineRoute,
                            String timingMode, String oversizePolicy, boolean timingReady,
                            List<StageView> stages, String nextStage) {
    }

    public record PrepareResult(int voiceBindingsAdded, int linesAdded, int shotCount,
                                double plannedSec, double targetSec, List<String> notes) {
    }

    /** 制作流程总览（只读，不建任务）。 */
    @Transactional(readOnly = true)
    public RunStatus status(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        requireRevision(projectId, workspaceId, revisionId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "只有视频项目有制作流程");
        }
        Map<String, String> approvedAt = new LinkedHashMap<>();
        for (ProductionStageApproval a : approvals
                .findByProjectIdAndRevisionIdAndWorkspaceId(projectId, revisionId, workspaceId)) {
            approvedAt.put(a.stage(), a.approvedAt() == null ? "" : a.approvedAt().toString());
        }

        List<Asset> assets = assetRepo.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId);
        List<JobView> jobs = jobService.listByProject(userId, workspaceId, projectId);

        List<JsonNode> shots = new ArrayList<>();
        plan.path("shots").forEach(shots::add);

        EngineSettingsService.VideoCaps caps = engineSettings.videoCaps(userId, plan);

        // 各阶段计数
        StageView portraits = portraitsStage(plan, assets, approvedAt, jobs);
        StageView keyframes = shotAssetStage("keyframes", "① 关键帧", shots, assets, jobs, "still",
                approvedAt, portraits.done() >= portraits.total());
        StageView motion = shotAssetStage("motion", "② 运动(motion)", shots, assets, jobs, "clip",
                approvedAt, keyframes.done() >= keyframes.total());
        StageView audio = audioStage(plan, assets, jobs, approvedAt, motion.done() >= motion.total());
        StageView render = renderStage(assets, approvedAt, motion.done() >= motion.total() && audio.done() >= audio.total());

        List<StageView> stages = List.of(portraits, keyframes, motion, audio, render);
        String next = null;
        for (StageView s : stages) {
            if (!"done".equals(s.state())) {
                next = s.stage();
                break;
            }
        }
        int planned = plan.path("shots").size();
        double plannedSec = 0;
        for (JsonNode s : plan.path("shots")) {
            plannedSec += s.path("duration_sec").asDouble(0);
        }
        JsonNode edit = plan.path("edit_plan");
        String rawTiming = edit.path("timing_mode").asText("");
        String mode = rawTiming.isEmpty() ? "shot_fixed" : rawTiming;
        return new RunStatus(projectId, revisionId, planReader.revisionNo(revisionId), project.status(),
                revisionId.equals(project.approvedRevisionId()), project.durationSec() == null ? 0
                : project.durationSec().doubleValue(),
                Math.round(plannedSec * 10) / 10.0, planned,
                caps.maxShotSec(), caps.nativeFps(), caps.motionEngine(), caps.route(),
                mode,
                edit.path("oversize_policy").asText("stretch"),
                !"shot_fixed".equals(mode),
                stages, next);
    }

    private StageView portraitsStage(JsonNode plan, List<Asset> assets, Map<String, String> approvedAt,
                                     List<JobView> jobs) {
        List<PlanSubjects.Subject> people = personSubjects(plan);
        int total = people.size();
        int done = 0;
        List<String> missing = new ArrayList<>();
        for (PlanSubjects.Subject s : people) {
            boolean bound = s.hasPortrait() && portraitExists(assets, s.portraitAssetId());
            boolean hasAsset = assets.stream().anyMatch(a -> "portrait".equals(a.kind())
                    && s.name().equals(AssetService.subjectOf(a)));
            if (bound) {
                done++;
            } else {
                missing.add(s.name() + (hasAsset ? "(待绑定)" : ""));
            }
        }
        int failed = failedJobs(jobs, "portrait");
        String hint = total == 0 ? "方案里还没有人物主体：先在「主体设定」里加人（或让导演抽取）"
                : (missing.isEmpty() ? "全部人物已绑定定妆照"
                : "待定妆/待绑定：" + String.join("、", missing));
        return stage("portraits", "⓪ 定妆照", total, done, failed, hint, approvedAt, true);
    }

    private StageView shotAssetStage(String stage, String label, List<JsonNode> shots, List<Asset> assets,
                                     List<JobView> jobs, String kind, Map<String, String> approvedAt,
                                     boolean unblocked) {
        int total = shots.size();
        int done = 0;
        for (JsonNode s : shots) {
            int no = s.path("shot_no").asInt(-1);
            if (hasShotAsset(assets, no, kind)) {
                done++;
            }
        }
        int failed = failedJobs(jobs, kind);
        String hint = done >= total ? "全部 " + total + " 镜已有产物"
                : ("已有 " + done + "/" + total + " 镜；缺 " + (total - done) + " 镜");
        StageView v = stage(stage, label, total, done, failed, hint, approvedAt, true);
        return unblocked ? v : blocked(v);
    }

    private StageView audioStage(JsonNode plan, List<Asset> assets, List<JobView> jobs,
                                 Map<String, String> approvedAt, boolean unblocked) {
        int totalLines = AudioPlan.totalLines(plan);
        boolean wantBgm = !AudioPlan.musicCues(plan).isEmpty();
        int total = totalLines + (wantBgm ? 1 : 0);
        int done = 0;
        for (JsonNode shot : plan.path("shots")) {
            int no = shot.path("shot_no").asInt(-1);
            List<AudioPlan.Line> lines = AudioPlan.lines(shot);
            for (int i = 0; i < lines.size(); i++) {
                if (hasVoiceAsset(assets, no, i)) {
                    done++;
                }
            }
        }
        if (wantBgm && assets.stream().anyMatch(a -> "bgm".equals(a.kind()))) {
            done++;
        }
        int failed = failedJobs(jobs, "voice") + failedJobs(jobs, "bgm");
        String hint = total == 0 ? "还没有台词/旁白：可点「准备（自动台词）」按剧情生成，或手动加"
                : ("已有 " + done + "/" + total + " 段；缺 " + (total - done) + " 段");
        StageView v = stage("audio", "③ 配音/配乐", total, done, failed, hint, approvedAt, true);
        return unblocked ? v : blocked(v);
    }

    private StageView renderStage(List<Asset> assets, Map<String, String> approvedAt, boolean unblocked) {
        int done = assets.stream().anyMatch(a -> "master".equals(a.kind())) ? 1 : 0;
        StageView v = stage("render", "④ 成片", 1, done, 0, done > 0 ? "已渲染成片" : "点「确定」自动拼接成片",
                approvedAt, true);
        return unblocked ? v : blocked(v);
    }

    private StageView stage(String key, String label, int total, int done, int failed, String hint,
                            Map<String, String> approvedAt, boolean canConfirm) {
        String state;
        if (total == 0) {
            state = "done";
        } else if (done >= total) {
            state = "done";
        } else if (failed > 0) {
            state = "failed";
        } else if (done > 0) {
            state = "running";
        } else {
            state = "ready";
        }
        return new StageView(key, label, state, total, done, failed, hint, approvedAt.get(key), canConfirm);
    }

    private StageView blocked(StageView v) {
        if ("done".equals(v.state())) {
            return v;
        }
        return new StageView(v.stage(), v.label(), "blocked", v.total(), v.done(), v.failed(),
                "上一步完成后才能开始：" + v.hint(), v.approvedAt(), false);
    }

    // ------------------------------------------------------------------ 准备（P1：自动音色 + 自动台词 + 自动重排时长）

    /**
     * 「准备」：为人物主体补音色绑定 → 按剧情生成台词（缺失时）→ 按**台词时长 + 引擎能力**重排镜头时长。
     *
     * <p>每一步都可单独失败而不影响其它：没主体就跳过音色，没台词就跳过铺排，最后一定把能算的时长写完。
     */
    @Transactional
    public PrepareResult prepare(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                 boolean withLines) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        requireRevision(projectId, workspaceId, revisionId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "只有视频项目有制作流程");
        }
        ObjectNode obj = (ObjectNode) plan.deepCopy();
        List<String> notes = new ArrayList<>();

        int bindingsAdded = ensureVoiceBindings(obj, notes);

        int linesAdded = 0;
        if (withLines && AudioPlan.totalLines(obj) == 0) {
            int shots = obj.path("shots").size();
            if (shots > AUTO_LINES_MAX_SHOTS) {
                notes.add("镜头较多（" + shots + "），未自动生成台词；可点「AI 一键台词」分批生成");
            } else {
                try {
                    var r = aiAudioService.generateLines(userId, workspaceId, projectId, revisionId, null, false);
                    linesAdded = applyLines(obj, r);
                    notes.addAll(r.notes());
                } catch (RuntimeException e) {
                    notes.add("自动台词失败（" + e.getMessage() + "），已跳过");
                }
            }
        }

        // P1：逐镜配乐策略（不一定每镜都要配乐）—— 必须在**重排时长之后**派生，
        // 否则 cue 的时间窗还是旧时长（
        // 先把镜头时长定下来，再按新窗切配乐段）。
        List<Asset> assets = assetRepo.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId);
        EngineSettingsService.VideoCaps caps = engineSettings.videoCaps(userId, obj);
        ShotTimingPlanner.Caps plannerCaps = new ShotTimingPlanner.Caps(
                caps.maxShotSec(), 1.0, 0.3, caps.nativeFps(), 32);
        ShotTimingPlanner.Result timing = replan(obj, assets, caps, plannerCaps, notes);
        notes.addAll(timing.notes());
        String musicNote = deriveMusicCues(obj);
        if (musicNote != null) {
            notes.add(musicNote);
        }

        directorService.patchPlanInPlace(userId, workspaceId, projectId, revisionId, obj);
        log.info("run prepare: project={} rev={} bindings+={} lines+={} shots={} planned={}s target={}s",
                projectId, revisionId, bindingsAdded, linesAdded, timing.timings().size(),
                timing.totalSec(), timing.targetSec());
        return new PrepareResult(bindingsAdded, linesAdded, timing.timings().size(),
                timing.totalSec(), timing.targetSec(), notes);
    }

    /**
     * P1：把导演逐镜给的 {@code shots[].music}（none|bed|hit）派生成 {@code audio.music[]} 段落。
     *
     * <p>只在以下情况写：方案里**没有**显式 {@code audio.music[]}（有就是用户/AI 的定义，尊重），
     * 且至少有一镜显式标了 music（否则保持旧行为：整片一段 mood）。{@code none} 的镜没有 cue，即无配乐。
     *
     * @return 给用户看的一句说明；没做事返回 null
     */
    private String deriveMusicCues(ObjectNode plan) {
        if (plan.path("audio").path("music").isArray() && !plan.path("audio").path("music").isEmpty()) {
            return null;
        }
        boolean anyExplicit = false;
        List<studio.weaveora.director.plan.MusicCuePlanner.ShotMusic> shots = new ArrayList<>();
        double cursor = 0;
        for (JsonNode shot : plan.path("shots")) {
            double dur = shot.path("duration_sec").asDouble(0);
            String policy = shot.path("music").asText("");
            if (!policy.isBlank()) {
                anyExplicit = true;
            }
            shots.add(new studio.weaveora.director.plan.MusicCuePlanner.ShotMusic(
                    shot.path("shot_no").asInt(-1), cursor, cursor + dur, policy));
            cursor += dur;
        }
        if (!anyExplicit) {
            return null;
        }
        List<studio.weaveora.director.plan.MusicCuePlanner.Cue> cues =
                studio.weaveora.director.plan.MusicCuePlanner.plan(shots, cursor);
        ObjectNode audio = plan.hasNonNull("audio") && plan.get("audio").isObject()
                ? (ObjectNode) plan.get("audio") : plan.putObject("audio");
        ArrayNode arr = audio.putArray("music");
        String mood = audio.path("music_mood").asText("").trim();
        int i = 0;
        for (studio.weaveora.director.plan.MusicCuePlanner.Cue c : cues) {
            ObjectNode o = arr.addObject();
            o.put("id", "m" + (++i));
            o.put("start_sec", Math.round(c.startSec() * 10) / 10.0);
            o.put("end_sec", Math.round(c.endSec() * 10) / 10.0);
            if (!mood.isEmpty()) {
                o.put("mood", mood);
            }
            o.put("gain_db", c.gainDb());
            o.put("fade_in_sec", 0.5);
            o.put("fade_out_sec", 1.0);
            o.put("loop", true);
            o.put("duck", true);
        }
        int noneCount = 0;
        for (JsonNode shot : plan.path("shots")) {
            if (studio.weaveora.director.plan.MusicCuePlanner.NONE.equals(
                    studio.weaveora.director.plan.MusicCuePlanner.normalize(shot.path("music").asText("")))) {
                noneCount++;
            }
        }
        if (cues.isEmpty()) {
            return "按剧情把全片标成了「无配乐」（" + noneCount + " 镜）";
        }
        return "已按逐镜配乐策略生成 " + cues.size() + " 段配乐"
                + (noneCount > 0 ? "，其中 " + noneCount + " 镜无配乐" : "");
    }

    /** revision 必须属于该项目 / 工作区（防跨项目 / 跨工作区读写）。 */
    private void requireRevision(UUID projectId, UUID workspaceId, UUID revisionId) {
        if (!planReader.revisionInProject(revisionId, projectId, workspaceId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不属于该项目");
        }
    }

    /**
     * 只重排镜头时长（确定性、不跑 LLM、不改音色/配乐）—— 前端打开项目页时**自动**调用一次，
     * 保证「分镜已经按当前引擎能力 + 台词时长排好」。
     */
    @Transactional
    public PrepareResult replanTiming(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        requireRevision(projectId, workspaceId, revisionId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "只有视频项目有制作流程");
        }
        ObjectNode obj = (ObjectNode) plan.deepCopy();
        List<String> notes = new ArrayList<>();
        List<Asset> assets = assetRepo.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId);
        EngineSettingsService.VideoCaps caps = engineSettings.videoCaps(userId, obj);
        ShotTimingPlanner.Caps plannerCaps = new ShotTimingPlanner.Caps(
                caps.maxShotSec(), 1.0, 0.3, caps.nativeFps(), 32);
        ShotTimingPlanner.Result timing = replan(obj, assets, caps, plannerCaps, notes);
        notes.addAll(timing.notes());
        directorService.patchPlanInPlace(userId, workspaceId, projectId, revisionId, obj);
        log.info("run replan: project={} rev={} shots={} planned={}s target={}s",
                projectId, revisionId, timing.timings().size(), timing.totalSec(), timing.targetSec());
        return new PrepareResult(0, 0, timing.timings().size(), timing.totalSec(), timing.targetSec(), notes);
    }

    private int ensureVoiceBindings(ObjectNode plan, List<String> notes) {
        List<PlanSubjects.Subject> people = personSubjects(plan);
        if (people.isEmpty()) {
            return 0;
        }
        ObjectNode audio = plan.hasNonNull("audio") && plan.get("audio").isObject()
                ? (ObjectNode) plan.get("audio") : plan.putObject("audio");
        ArrayNode bindings = audio.hasNonNull("voiceBindings") && audio.get("voiceBindings").isArray()
                ? (ArrayNode) audio.get("voiceBindings") : audio.putArray("voiceBindings");
        Set<String> bound = new LinkedHashSet<>();
        for (JsonNode b : bindings) {
            String s = b.path("subject").asText("").trim();
            if (!s.isEmpty()) {
                bound.add(s);
            }
        }
        int added = 0;
        for (PlanSubjects.Subject p : people) {
            if (bound.contains(p.name())) {
                continue;
            }
            String gender = p.traits() == null ? "" : p.traits().gender();
            String voice = "female".equals(gender) ? "中文女" : "中文男";
            ObjectNode b = bindings.addObject();
            b.put("subject", p.name());
            b.put("voice", voice);
            b.put("speed", 1.0);
            added++;
        }
        if (added > 0) {
            notes.add("已为 " + added + " 个角色按性别分配默认音色（可在「角色音色绑定」里改）");
        }
        return added;
    }

    private int applyLines(ObjectNode plan, AiAudioService.LinesResult r) {
        Map<Integer, AiAudioService.ShotLines> byNo = new LinkedHashMap<>();
        for (AiAudioService.ShotLines sl : r.shots()) {
            byNo.put(sl.shotNo(), sl);
        }
        int n = 0;
        for (JsonNode shot : plan.path("shots")) {
            AiAudioService.ShotLines sl = byNo.get(shot.path("shot_no").asInt(-1));
            if (sl == null || sl.lines().isEmpty()) {
                continue;
            }
            ObjectNode s = (ObjectNode) shot;
            ArrayNode narr = s.putArray("narrations");
            for (AiAudioService.FittedLine f : sl.lines()) {
                ObjectNode o = narr.addObject();
                o.put("at_sec", f.atSec());
                if (f.endSec() > f.atSec()) {
                    o.put("end_sec", f.endSec());
                }
                o.put("text", f.text());
                o.put("kind", f.kind());
                if (f.subject() != null && !f.subject().isBlank()) {
                    o.put("subject", f.subject());
                }
                o.put("speed", f.speed());
                n++;
            }
        }
        return n;
    }

    /** 用「台词/旁白时长 + 引擎能力」重排时长；返回规划结果（不改 plan，调用方决定何时写回）。 */
    private ShotTimingPlanner.Result replan(ObjectNode plan, List<Asset> assets,
                                            EngineSettingsService.VideoCaps caps,
                                            ShotTimingPlanner.Caps plannerCaps, List<String> notes) {
        List<ShotTimingPlanner.ShotInput> inputs = new ArrayList<>();
        for (JsonNode shot : plan.path("shots")) {
            int no = shot.path("shot_no").asInt(-1);
            double speech = speechSecFor(shot, no, assets);
            inputs.add(new ShotTimingPlanner.ShotInput(no, shot.path("duration_sec").asDouble(3), speech));
        }
        double target = plan.path("duration_sec").asDouble(0);
        ShotTimingPlanner.Result r = ShotTimingPlanner.plan(inputs, target, plannerCaps,
                ShotTimingPlanner.POLICY_STRETCH);
        ShotTimingPlanner.applyTo(plan, r, plannerCaps, ShotTimingPlanner.POLICY_STRETCH);
        if (caps.maxShotSec() < 10) {
            notes.add("已按当前出片引擎上限 " + caps.maxShotSec() + "s/镜 自动拆镜（超长镜用本地重定时拉伸，不多花钱）");
        }
        for (ShotTimingPlanner.ShotTiming t : r.timings()) {
            if (t.warn() != null) {
                notes.add("第 " + t.shotNo() + " 镜：" + t.warn());
            }
        }
        return r;
    }

    /**
     * 某镜的语音总时长（秒）：优先用**已生成配音的真实时长**，缺失的段按文本估算。
     * 段间留 0.25s 间隙（与 {@code AiAudioService.GAP_SEC} 一致）。
     */
    private double speechSecFor(JsonNode shot, int shotNo, List<Asset> assets) {
        List<AudioPlan.Line> lines = AudioPlan.lines(shot);
        if (lines.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < lines.size(); i++) {
            AudioPlan.Line line = lines.get(i);
            Integer ms = voiceAssetDurationMs(assets, shotNo, i);
            sum += ms != null && ms > 0 ? ms / 1000.0 : AiAudioService.speechSec(line.text());
        }
        sum += 0.25 * Math.max(0, lines.size() - 1);
        return Math.round(sum * 10) / 10.0;
    }

    // ------------------------------------------------------------------ 确认（批量下发 + 落库）

    /** 用户点「确定」：记录确认 + 为缺失项批量下发任务（幂等，重复点不会重复建）。 */
    @Transactional
    public RunStatus confirm(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId, String stage) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (!revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先在分镜台确认这一版方案，再走制作流程");
        }
        Stage normalized = Stage.of(stage);
        JsonNode plan = planReader.revisionPlan(revisionId);
        // 门禁（写死在代码，不靠 UI）：上一步未完成 → 不允许确认本阶段（防绕过前端直调）
        StageView target = status(userId, workspaceId, projectId, revisionId).stages().stream()
                .filter(v -> v.stage().equals(normalized.key())).findFirst().orElse(null);
        if (target != null && "blocked".equals(target.state())) {
            throw new BizException(ErrorCode.VALIDATION, "上一步尚未完成：" + target.hint());
        }
        List<Asset> assets = assetRepo.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId);
        List<JsonNode> shots = new ArrayList<>();
        plan.path("shots").forEach(shots::add);

        switch (normalized) {
            case portraits -> createPortraits(userId, workspaceId, projectId, revisionId, plan, assets);
            case keyframes -> createMissingShotJobs(userId, workspaceId, projectId, revisionId, shots, assets, "still");
            case motion -> createMissingShotJobs(userId, workspaceId, projectId, revisionId, shots, assets, "clip");
            case audio -> createAudioJobs(userId, workspaceId, projectId, revisionId, plan, assets);
            case render -> concatService.renderMaster(userId, workspaceId, projectId, revisionId, "cut");
        }
        approvalUpsert(userId, workspaceId, projectId, revisionId, normalized.key(), "确定 " + normalized.label());
        log.info("run confirm: project={} rev={} stage={}", projectId, revisionId, normalized.key());
        return status(userId, workspaceId, projectId, revisionId);
    }

    /** 重试该阶段所有失败任务（§20.2：生成新 job）。 */
    @Transactional
    public RunStatus retryFailed(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId, String stage) {
        Stage s = Stage.of(stage);
        List<UUID> failed = new ArrayList<>();
        for (JobView j : jobService.listByProject(userId, workspaceId, projectId)) {
            if ("failed".equals(j.state()) && s.kinds().contains(j.kind())) {
                failed.add(j.id());
            }
        }
        if (!failed.isEmpty()) {
            jobService.retry(userId, workspaceId, projectId, failed);
        }
        return status(userId, workspaceId, projectId, revisionId);
    }

    private void createPortraits(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                 JsonNode plan, List<Asset> assets) {
        for (PlanSubjects.Subject s : personSubjects(plan)) {
            if (s.hasPortrait() && portraitExists(assets, s.portraitAssetId())) {
                continue;
            }
            jobService.create(userId, workspaceId, projectId, req(revisionId, "portrait", b -> b.subject = s.name()));
        }
    }

    private void createMissingShotJobs(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                       List<JsonNode> shots, List<Asset> assets, String kind) {
        List<Integer> missing = new ArrayList<>();
        for (JsonNode s : shots) {
            int no = s.path("shot_no").asInt(-1);
            if (!hasShotAsset(assets, no, kind)) {
                missing.add(no);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        final List<Integer> shotNos = missing;
        jobService.create(userId, workspaceId, projectId,
                req(revisionId, kind, b -> b.shotNos = shotNos));
    }

    /**
     * 配音/配乐任务：**只补缺失的段**。
     *
     * <p>为什么不整镜重生成：一镜可能 3 段语音，只缺第 2 段时整镜重建会多出 2 段重复产物
     * （多花 GPU；混音取最新一条虽不会叠音，但白烧一次）。所以按 {@code (shotId, lineIndex)} 逐段补。
     */
    private void createAudioJobs(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                 JsonNode plan, List<Asset> assets) {
        Map<Integer, UUID> shotIds = new LinkedHashMap<>();
        for (UUID sid : planReader.shotIds(revisionId)) {
            shotIds.put(planReader.shotNoOf(sid), sid);
        }
        for (JsonNode shot : plan.path("shots")) {
            int no = shot.path("shot_no").asInt(-1);
            UUID shotId = shotIds.get(no);
            if (shotId == null) {
                continue;
            }
            List<AudioPlan.Line> lines = AudioPlan.lines(shot);
            for (int i = 0; i < lines.size(); i++) {
                if (hasVoiceAsset(assets, no, i)) {
                    continue;
                }
                jobService.create(userId, workspaceId, projectId, new CreateJobRequest(
                        revisionId, shotId, "voice", null, null, null, i, null, null, null, null, null, null, null));
            }
        }
        boolean wantBgm = !AudioPlan.musicCues(plan).isEmpty();
        if (wantBgm && assets.stream().noneMatch(a -> "bgm".equals(a.kind()))) {
            jobService.create(userId, workspaceId, projectId, req(revisionId, "bgm", b -> { }));
        }
    }

    private void approvalUpsert(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                String stage, String note) {
        ProductionStageApproval a = approvals
                .findByProjectIdAndRevisionIdAndStageAndWorkspaceId(projectId, revisionId, stage, workspaceId)
                .orElse(null);
        if (a == null) {
            approvals.save(ProductionStageApproval.create(workspaceId, projectId, revisionId, stage, userId, note));
        } else {
            a.touch(userId, note);
            approvals.save(a);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 简易的建任务参数构造器（14 个字段的 record 写起来太长）。 */
    private static class ReqBuilder {
        Integer count;
        Integer frames;
        Integer lineIndex;
        List<Integer> shotNos;
        String subject;
    }

    private CreateJobRequest req(UUID revisionId, String kind, java.util.function.Consumer<ReqBuilder> fill) {
        ReqBuilder b = new ReqBuilder();
        fill.accept(b);
        return new CreateJobRequest(revisionId, null, kind, b.count, b.frames, null, b.lineIndex,
                b.shotNos, null, b.subject, null, null, null, null);
    }

    private static List<PlanSubjects.Subject> personSubjects(JsonNode plan) {
        List<PlanSubjects.Subject> out = new ArrayList<>();
        for (PlanSubjects.Subject s : PlanSubjects.parse(plan)) {
            if (PlanSubjects.KIND_PERSON.equals(s.kind()) && s.enabled()) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean portraitExists(List<Asset> assets, String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return false;
        }
        try {
            UUID id = UUID.fromString(assetId);
            return assets.stream().anyMatch(a -> id.equals(a.id()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasShotAsset(List<Asset> assets, int shotNo, String kind) {
        if (shotNo < 0) {
            return false;
        }
        return assets.stream().anyMatch(a -> kind.equals(a.kind()) && a.shotNo() != null
                && a.shotNo() == shotNo);
    }

    private boolean hasVoiceAsset(List<Asset> assets, int shotNo, int lineIndex) {
        for (Asset a : assets) {
            if (!"voice".equals(a.kind()) || a.shotNo() == null || a.shotNo() != shotNo) {
                continue;
            }
            Integer li = assetService.lineIndexOf(a);
            if (li != null && li == lineIndex) {
                return true;
            }
        }
        return false;
    }

    private Integer voiceAssetDurationMs(List<Asset> assets, int shotNo, int lineIndex) {
        for (Asset a : assets) {
            if (!"voice".equals(a.kind()) || a.shotNo() == null || a.shotNo() != shotNo) {
                continue;
            }
            Integer li = assetService.lineIndexOf(a);
            if (li != null && li == lineIndex) {
                return a.durationMs();
            }
        }
        return null;
    }

    private static int failedJobs(List<JobView> jobs, String kind) {
        int n = 0;
        for (JobView j : jobs) {
            if (kind.equals(j.kind()) && "failed".equals(j.state())) {
                n++;
            }
        }
        return n;
    }

    /** 阶段枚举 + 展示名 + 归属的 job kind。 */
    public enum Stage {
        portraits("portraits", "定妆照", List.of("portrait")),
        keyframes("keyframes", "关键帧", List.of("still")),
        motion("motion", "运动(motion)", List.of("clip")),
        audio("audio", "配音/配乐", List.of("voice", "bgm")),
        render("render", "成片", List.of());

        private final String key;
        private final String label;
        private final List<String> kinds;

        Stage(String key, String label, List<String> kinds) {
            this.key = key;
            this.label = label;
            this.kinds = kinds;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public List<String> kinds() {
            return kinds;
        }

        public static Stage of(String raw) {
            if (raw == null) {
                throw new BizException(ErrorCode.VALIDATION, "缺少阶段名");
            }
            String v = raw.trim().toLowerCase();
            for (Stage s : values()) {
                if (s.key.equals(v)) {
                    return s;
                }
            }
            throw new BizException(ErrorCode.VALIDATION, "未知阶段：" + raw + "（可选 portraits|keyframes|motion|audio|render）");
        }
    }
}
