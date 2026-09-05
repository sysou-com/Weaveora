package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.identity.api.WorkspaceGuard;
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
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final UUID PRESET_STILL = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PRESET_CLIP = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final List<String> TERMINAL = List.of("succeeded", "failed", "cancelled");

    private final GenerationJobRepository jobs;
    private final WorkerNodeRepository nodes;
    private final AssetService assets;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final JobWsHandler ws;
    private final studio.weaveora.director.PlanReader planReader;
    private final studio.weaveora.asset.domain.AssetRepository assetRepo;

    public JobService(GenerationJobRepository jobs, WorkerNodeRepository nodes, AssetService assets,
                      StoragePort storage, ProjectContextPort projects, WorkspaceGuard guard, JobWsHandler ws,
                      studio.weaveora.director.PlanReader planReader,
                      studio.weaveora.asset.domain.AssetRepository assetRepo) {
        this.jobs = jobs;
        this.nodes = nodes;
        this.assets = assets;
        this.storage = storage;
        this.projects = projects;
        this.guard = guard;
        this.ws = ws;
        this.planReader = planReader;
        this.assetRepo = assetRepo;
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
        // W4 一致性锚定：把项目 approved 方案的参考图(storage key)带给引擎
        RefCtx refs = loadRefs(userId, workspaceId, projectId, req.revisionId());

        List<GenerationJob> created = new ArrayList<>();
        if ("video".equals(planMode)) {
            List<UUID> shotIds = resolveVideoShots(userId, workspaceId, projectId, req.revisionId(), req.shotId(), req.kind());
            if (shotIds.isEmpty()) {
                throw new BizException(ErrorCode.SHOT_NOT_APPROVED, "没有已确认的镜头可生成");
            }
            for (UUID shotId : shotIds) {
                JsonNode shot = shotOf(plan, shotId);
                ObjectNode payload = mapper().createObjectNode();
                payload.put("kind", req.kind());
                payload.put("mode", "video");
                payload.put("revisionId", req.revisionId().toString());
                payload.put("shotId", shotId.toString());
                payload.put("shot_no", shot.path("shot_no").asInt());
                payload.put("positive_prompt", shot.path("positive_prompt").asText(""));
                payload.put("negative_prompt", shot.path("negative_prompt").asText(""));
                payload.put("duration_sec", shot.path("duration_sec").asDouble(3));
                payload.put("seed", shot.path("seed").asLong(0) == 0 ? randomSeed() : shot.path("seed").asLong(0));
                attachRefs(payload, refs);
                GenerationJob job = createOne(workspaceId, projectId, req.revisionId(), shotId,
                        "clip".equals(req.kind()) ? PRESET_CLIP : PRESET_STILL, req.kind(), payload, userId);
                created.add(job);
            }
        } else {
            if ("clip".equals(req.kind())) {
                throw new BizException(ErrorCode.VALIDATION, "图片项目只能生成 still");
            }
            int count = req.count() == null ? 1 : req.count();
            if (count != 1 && count != 2 && count != 4) {
                throw new BizException(ErrorCode.VALIDATION, "图片张数须为 1/2/4（§7.5）");
            }
            for (int i = 0; i < count; i++) {
                ObjectNode payload = mapper().createObjectNode();
                payload.put("kind", "still");
                payload.put("mode", "image");
                payload.put("revisionId", req.revisionId().toString());
                payload.put("positive_prompt", plan.path("positive_prompt").asText(""));
                payload.put("negative_prompt", plan.path("negative_prompt").asText(""));
                JsonNode params = plan.path("params");
                payload.set("params", params.deepCopy());
                payload.put("seed", randomSeed());
                payload.put("title", plan.path("title").asText(""));
                attachRefs(payload, refs);
                GenerationJob job = createOne(workspaceId, projectId, req.revisionId(), null,
                        PRESET_STILL, "still", payload, userId);
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
        if ("running".equals(job.state())) {
            jobs.requestCancel(jobId);
            emit(job, Map.of("type", "job.cancel_requested"));
            return toView(job);
        }
        job.cancel();
        emit(job, Map.of("type", "job.cancelled"));
        return toView(jobs.save(job));
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
        for (GenerationJob candidate : jobs.findQueuedForClaim()) {
            if (scope != null && !scope.equals(candidate.workspaceId())) {
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
        return Map.of("job", (Object) null);
    }

    @Transactional
    public void progress(UUID jobId, int progress, String stage) {
        GenerationJob job = requireRunning(jobId);
        job.progress(progress, stage);
        emit(jobs.save(job), Map.of("type", "job.progress", "progress", progress, "stage", stage));
    }

    @Transactional
    public List<AssetResponse> complete(UUID jobId, List<CompleteAsset> items) {
        GenerationJob job = requireRunning(jobId);
        List<AssetResponse> created = new ArrayList<>();
        if (job.cancelRequested()) {
            job.cancel();
            emit(jobs.save(job), Map.of("type", "job.cancelled"));
            throw new BizException(ErrorCode.JOB_NOT_CANCELLABLE, "任务已请求取消");
        }
        job.succeed();
        jobs.save(job);
        String kind = "clip".equals(job.kind()) ? "clip" : "still";
        for (CompleteAsset a : items) {
            AssetResponse resp = toAssetResponse(assets.createOutput(
                    job.workspaceId(), job.projectId(), job.id(), job.shotId(), kind,
                    a.key(), a.mime(), a.width(), a.height(), a.seed()));
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
            return;
        }
        job.fail(code == null ? "WORKER_ERROR" : code, message);
        emit(jobs.save(job), Map.of("type", "job.failed", "code", job.errorCode()));
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

    record RefCtx(List<String> ids, List<String> keys) {
        static RefCtx empty() { return new RefCtx(List.of(), List.of()); }
    }

    private RefCtx loadRefs(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        try {
            UUID briefId = planReader.revisionBriefId(revisionId);
            BriefSnapshot brief = projects.requireBrief(userId, workspaceId, projectId, briefId);
            if (brief.constraints() == null || !brief.constraints().has("referenceAssetIds")) {
                return RefCtx.empty();
            }
            List<UUID> ids = new ArrayList<>();
            for (JsonNode n : brief.constraints().get("referenceAssetIds")) {
                try { ids.add(UUID.fromString(n.asText())); } catch (IllegalArgumentException ignored) { }
            }
            if (ids.isEmpty()) return RefCtx.empty();
            List<String> keys = assetRepo.findByIdInAndWorkspaceId(ids, workspaceId).stream()
                    .map(studio.weaveora.asset.domain.Asset::storageKey)
                    .toList();
            return new RefCtx(ids.stream().map(UUID::toString).toList(), keys);
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
                                    UUID presetId, String kind, JsonNode payload, UUID userId) {
        String idem = "j:" + workspaceId + ":" + projectId + ":" + revisionId + ":" + shotId + ":"
                + kind + ":" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        GenerationJob job = GenerationJob.create(workspaceId, projectId, revisionId, shotId, presetId,
                kind, idem, payload, userId);
        GenerationJob saved = jobs.save(job);
        emit(saved, Map.of("type", "job.queued", "state", "queued"));
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
        m.put("projectId", j.projectId().toString());
        m.put("workspaceId", j.workspaceId().toString());
        m.put("revisionId", j.revisionId() == null ? null : j.revisionId().toString());
        m.put("shotId", j.shotId() == null ? null : j.shotId().toString());
        m.put("payload", j.payload());
        return m;
    }

    private AssetResponse toAssetResponse(studio.weaveora.asset.domain.Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.kind(), a.mime(), a.width(), a.height(), a.createdAt());
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
