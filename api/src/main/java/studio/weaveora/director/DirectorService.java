package studio.weaveora.director;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.director.api.GenerateRequest;
import studio.weaveora.director.api.GenerateResponse;
import studio.weaveora.director.api.PatchRevisionRequest;
import studio.weaveora.director.api.RevisionApproveResult;
import studio.weaveora.director.api.RevisionDetailResponse;
import studio.weaveora.director.api.RevisionSummaryResponse;
import studio.weaveora.director.api.ShotApproveResponse;
import studio.weaveora.director.api.ShotView;
import studio.weaveora.director.domain.PromptRevision;
import studio.weaveora.director.domain.PromptRevisionRepository;
import studio.weaveora.director.domain.ShotDraft;
import studio.weaveora.director.domain.ShotDraftRepository;
import studio.weaveora.director.plan.AspectPixels;
import studio.weaveora.director.plan.DirectorPlanValidator;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.BriefSnapshot;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 导演层：brief → LLM(§10.1) → 校验(§10.3) → 落 PromptRevision(+ShotDraft) → 项目 directing（§20.1）。
 * 确认闸门：approve 钉 approved_revision_id；PATCH 仅未确认版本（§7.4）。
 */
@Service
public class DirectorService {

    private static final Logger log = LoggerFactory.getLogger(DirectorService.class);

    private static final List<String> CONCRETE_MODES = List.of("image", "video");
    private static final Map<String, String> SYSTEM_FALLBACK = Map.of(
            "image", "你是电影摄影指导+分镜师。输出且只输出 JSON（图片导演方案：mode/title/logline/prompt_zh/positive_prompt/negative_prompt/camera/lighting/palette/params/variations）。",
            "video", "你是电影摄影指导+分镜师。输出且只输出 JSON（视频导演方案：mode/title/logline/duration_sec/aspect_ratio/script/shots/audio/edit_plan；镜头时长总和==目标时长，每镜 positive_prompt 20–1200）。");

    private final ProjectContextPort context;
    private final PromptRevisionRepository revisions;
    private final ShotDraftRepository shots;
    private final DirectorLlm llm;
    private final ObjectMapper mapper;
    private final SafetyGuard safety;

    public DirectorService(ProjectContextPort context, PromptRevisionRepository revisions,
                           ShotDraftRepository shots, DirectorLlm llm, ObjectMapper mapper,
                           SafetyGuard safety) {
        this.context = context;
        this.revisions = revisions;
        this.shots = shots;
        this.llm = llm;
        this.mapper = mapper;
        this.safety = safety;
    }

    @Transactional
    public GenerateResponse generate(UUID userId, UUID workspaceId, UUID projectId, GenerateRequest req) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        BriefSnapshot brief = context.requireBrief(userId, workspaceId, projectId, req.briefId());
        String mode = resolveMode(req.mode(), brief.mode(), project.mode());
        if ("video".equals(mode) && project.durationSec() == null) {
            throw new BizException(ErrorCode.VALIDATION, "视频项目缺少目标时长 durationSec（创建项目时指定）");
        }
        String hit = safety.matchRealPerson(brief.rawText()).orElse(null);
        if (hit != null) {
            throw new BizException(ErrorCode.BRIEF_BLOCKED,
                    "主体分档拦截：命中真人分档词「" + hit + "」——可识别真人需 v1.0 解锁"
                            + "（肖像授权 / AI 标识 / 深度合成合规，§11.4）。请改为产品/物体/场景或虚构人物描述。");
        }

        String system = loadSystemPrompt(mode);
        String user = buildUserPrompt(brief, project, mode);
        JsonNode plan = callAndParse(system, user, brief, project, mode);

        enrich(plan, mode, project.aspectRatio());
        validateOrThrow(plan, mode, project.durationSec());
        if (plan instanceof ObjectNode obj && !obj.has("mode")) {
            obj.put("mode", mode);
        }

        int revisionNo = nextRevisionNo(projectId);
        PromptRevision rev = PromptRevision.create(workspaceId, projectId, brief.id(), revisionNo,
                llm.source(), plan, userId);
        revisions.save(rev);
        if ("video".equals(mode)) {
            syncShots(rev.id(), plan);
        }
        context.markDirecting(workspaceId, projectId);
        log.info("director generate project={} revision={} source={}", projectId, revisionNo, llm.source());
        return new GenerateResponse(rev.id(), revisionNo, llm.source(), "directing", plan);
    }

    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRevisions(UUID userId, UUID workspaceId, UUID projectId) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        return revisions.findByProjectIdAndWorkspaceIdOrderByRevisionNoDesc(projectId, workspaceId).stream()
                .map(r -> toSummary(r, project.approvedRevisionId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public RevisionDetailResponse getRevision(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        return toDetail(r, project.approvedRevisionId());
    }

    @Transactional
    public RevisionDetailResponse patchRevision(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                                PatchRevisionRequest req) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        if (revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_LOCKED, "该版本已是本项目确认稿，修改请另存新版本");
        }
        JsonNode incoming = req.plan();
        String curMode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        String newMode = incoming.path("mode").asText(curMode);
        if (!curMode.equals(newMode)) {
            throw new BizException(ErrorCode.VALIDATION, "不可通过编辑切换导演模式（请新建项目/brief）");
        }
        enrich(incoming, curMode, project.aspectRatio());
        validateOrThrow(incoming, curMode, project.durationSec());
        r.replacePlan(incoming);
        r.setSource("user");
        revisions.save(r);
        if ("video".equals(curMode)) {
            syncShots(r.id(), incoming);
        }
        return toDetail(r, project.approvedRevisionId());
    }

    @Transactional
    public RevisionApproveResult approveRevision(UUID userId, UUID workspaceId, UUID projectId,
                                                 UUID revisionId) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        validateOrThrow(r.schemaJson(), r.schemaJson().path("mode").asText("image"), project.durationSec());
        if ("video".equals(r.schemaJson().path("mode").asText("image"))) {
            shots.findByRevisionIdOrderByShotNo(r.id()).forEach(ShotDraft::approve);
        }
        context.markApproved(workspaceId, projectId, r.id());
        return new RevisionApproveResult(r.id(), true, "approved");
    }

    @Transactional
    public ShotApproveResponse approveShot(UUID userId, UUID workspaceId, UUID projectId, UUID shotId) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        ShotDraft shot = shots.findById(shotId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "镜头不存在"));
        PromptRevision r = revisions.findById(shot.revisionId())
                .filter(x -> x.projectId().equals(projectId) && x.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "镜头不属于该项目"));
        shot.approve();
        shots.save(shot);
        return new ShotApproveResponse(r.id(), shot.id(), shot.shotNo(), shot.status());
    }

    // ---------- 内部 ----------

    private JsonNode callAndParse(String system, String user, BriefSnapshot brief,
                                  ProjectSnapshot project, String mode) {
        LlmRequest req = new LlmRequest(system, user,
                "", brief.rawText(), mode, project.aspectRatio(), project.durationSec(), brief.constraints());
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llm.generateJson(req);
                return mapper.readTree(raw);
            } catch (JsonProcessingException e) {
                log.warn("director 输出非 JSON（第 {} 次），重试: {}", attempt, e.getMessage());
            } catch (Exception e) {
                log.warn("director 网关调用异常（第 {} 次）: {}", attempt, e.getMessage());
            }
        }
        throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "导演服务暂时不可用或返回不可解析，请稍后重试或精简需求");
    }

    private void enrich(JsonNode plan, String mode, String aspect) {
        if (!(plan instanceof ObjectNode obj)) {
            return;
        }
        if (!obj.has("mode")) obj.put("mode", mode);
        if ("video".equals(mode)) {
            JsonNode shotsNode = obj.get("shots");
            if (shotsNode != null && shotsNode.isArray()) {
                for (JsonNode s : shotsNode) {
                    if (s instanceof ObjectNode so) {
                        so.put("negative_prompt",
                                DirectorPlanValidator.mergeNegative(
                                        so.path("negative_prompt").asText(null), DirectorPlanValidator.DEFAULT_NEGATIVE));
                    }
                }
            }
        } else {
            obj.put("negative_prompt",
                    DirectorPlanValidator.mergeNegative(
                            obj.path("negative_prompt").asText(null), DirectorPlanValidator.DEFAULT_NEGATIVE));
            ObjectNode params = obj.has("params") && obj.get("params").isObject()
                    ? (ObjectNode) obj.get("params") : obj.putObject("params");
            AspectPixels.Dim dim = AspectPixels.forImage(aspect);
            if (!params.has("width") || params.get("width").isNull()) params.put("width", dim.width());
            if (!params.has("height") || params.get("height").isNull()) params.put("height", dim.height());
        }
    }

    private void validateOrThrow(JsonNode plan, String mode, BigDecimal durationSec) {
        List<String> problems = DirectorPlanValidator.validate(plan, mode, durationSec);
        if (!problems.isEmpty()) {
            String msg = String.join("；", problems.stream().limit(4).toList());
            throw new BizException(ErrorCode.VALIDATION, "方案校验未通过：" + msg);
        }
    }

    /** 依据 plan.shots 全量重建 shot_drafts（generate/patch 均调用；重建时保留已确认镜头状态）。 */
    private void syncShots(UUID revisionId, JsonNode plan) {
        Map<Integer, String> status = new HashMap<>();
        List<ShotDraft> existing = shots.findByRevisionIdOrderByShotNo(revisionId);
        for (ShotDraft old : existing) {
            status.put(old.shotNo(), old.status());
        }
        shots.deleteAll(existing);
        shots.flush(); // 先落删除，避免 (revision_id, shot_no) 唯一键与后续插入冲突
        JsonNode list = plan.get("shots");
        if (list == null || !list.isArray()) return;
        for (JsonNode s : list) {
            ShotDraft d = ShotDraft.create(
                    revisionId,
                    s.path("shot_no").asInt(0),
                    s.path("duration_sec").decimalValue(),
                    textOrNull(s, "shot_size"),
                    textOrNull(s, "camera_move"),
                    textOrNull(s, "action"),
                    s.path("positive_prompt").asText(""),
                    s.path("negative_prompt").asText(""),
                    s.path("seed_lock").asBoolean(true),
                    s.hasNonNull("ref_shot_no") ? s.get("ref_shot_no").asInt() : null);
            if ("approved".equals(status.get(d.shotNo()))) {
                d.approve(); // patch 后保留已确认镜头状态（§9.2 允许只确认第 N 镜）
            }
            shots.save(d);
        }
        shots.flush();
    }

    private int nextRevisionNo(UUID projectId) {
        return revisions.findTopByProjectIdOrderByRevisionNoDesc(projectId)
                .map(r -> r.revisionNo() + 1).orElse(1);
    }

    private PromptRevision findRevision(UUID workspaceId, UUID projectId, UUID revisionId) {
        return revisions.findByIdAndProjectIdAndWorkspaceId(revisionId, projectId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "方案不存在或不属于该项目"));
    }

    private String resolveMode(String reqMode, String briefMode, String projectMode) {
        String[] candidates = {reqMode, briefMode, projectMode};
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || "auto".equals(candidate)) continue;
            if (CONCRETE_MODES.contains(candidate)) return candidate;
            if ("mixed".equals(candidate)) {
                throw new BizException(ErrorCode.VALIDATION, "mixed 项目需指定导演模式：image 或 video");
            }
        }
        throw new BizException(ErrorCode.VALIDATION, "无法确定导演模式（brief.mode / project.mode 均未提供）");
    }

    private String loadSystemPrompt(String mode) {
        String file = "image".equals(mode) ? "director_image_system.md" : "director_video_system.md";
        try {
            return new String(new ClassPathResource("prompts/" + file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("prompts/{} 读取失败，用内置兜底", file);
            return SYSTEM_FALLBACK.getOrDefault(mode, SYSTEM_FALLBACK.get("image"));
        }
    }

    private String buildUserPrompt(BriefSnapshot brief, ProjectSnapshot project, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目标题/画幅：").append(project.aspectRatio());
        if (project.durationSec() != null) sb.append("，目标时长 ").append(project.durationSec()).append(" 秒");
        sb.append("\n\n用户 Brief：\n").append(brief.rawText());
        if (brief.constraints() != null && !brief.constraints().isEmpty()) {
            sb.append("\n\n约束（constraints）：\n").append(brief.constraints().toPrettyString());
        }
        sb.append("\n\n请按 System Prompt 的 JSON 结构输出。");
        return sb.toString();
    }

    private RevisionSummaryResponse toSummary(PromptRevision r, UUID approvedId) {
        String mode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        return new RevisionSummaryResponse(r.id(), r.revisionNo(), r.source(), r.title(), r.logline(),
                mode, r.id().equals(approvedId), r.createdAt());
    }

    private RevisionDetailResponse toDetail(PromptRevision r, UUID approvedId) {
        String mode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        List<ShotView> shotViews = "video".equals(mode)
                ? shots.findByRevisionIdOrderByShotNo(r.id()).stream().map(this::toShotView).toList()
                : List.of();
        return new RevisionDetailResponse(r.id(), r.briefId(), r.revisionNo(), r.source(),
                r.id().equals(approvedId), r.schemaJson(), shotViews, r.createdAt());
    }

    private ShotView toShotView(ShotDraft s) {
        return new ShotView(s.id(), s.shotNo(), s.durationSec(), s.shotSize(), s.cameraMove(), s.action(),
                s.positivePrompt(), s.negativePrompt(), s.seedLock(), s.refShotNo(), s.status());
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
