package studio.weaveora.director;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import studio.weaveora.billing.QuotaService;
import studio.weaveora.infra.obs.Metrics;
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
    private final QuotaService quota;
    private final Metrics metrics;
    private final int planMaxSec;   // W8 分段导演：单次导演计划时长上限（默认 60s）

    public DirectorService(ProjectContextPort context, PromptRevisionRepository revisions,
                           ShotDraftRepository shots, DirectorLlm llm, ObjectMapper mapper,
                           SafetyGuard safety, QuotaService quota, Metrics metrics,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${weaveora.video.plan-max-sec:60}") int planMaxSec) {
        this.context = context;
        this.revisions = revisions;
        this.shots = shots;
        this.llm = llm;
        this.mapper = mapper;
        this.safety = safety;
        this.quota = quota;
        this.metrics = metrics;
        this.planMaxSec = planMaxSec;
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
            metrics.nsfwHit();
            throw new BizException(ErrorCode.BRIEF_BLOCKED,
                    "主体分档拦截：命中真人分档词「" + hit + "」——可识别真人需 v1.0 解锁"
                            + "（肖像授权 / AI 标识 / 深度合成合规，§11.4）。请改为产品/物体/场景或虚构人物描述。");
        }
        quota.checkDirector(userId);

        String system = loadSystemPrompt(mode);
        String user = buildUserPrompt(brief, project, mode);
        long t0 = System.nanoTime();
        JsonNode plan;
        try {
            plan = fetchPlan(system, user, brief, project, mode);
            enrich(plan, mode, project.aspectRatio());
            validateOrThrow(plan, mode, project.durationSec());
            metrics.director(System.nanoTime() - t0, true);
        } catch (RuntimeException e) {
            metrics.director(System.nanoTime() - t0, false);
            throw e;
        }
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

    /**
     * 单次导演或分段导演（W8 长片编排）：视频目标时长 > planMaxSec 时按 ≤planMax 自动分 K 段，
     * 每段单独调用导演并携带上一段结尾的衔接提示，合并为一个可整体校验/落库的方案。
     */
    private JsonNode fetchPlan(String system, String user, BriefSnapshot brief,
                               ProjectSnapshot project, String mode) {
        if ("video".equals(mode) && project.durationSec() != null
                && project.durationSec().intValue() > planMaxSec) {
            return generateSegmented(system, brief, project);
        }
        return callAndParse(system, user, brief, project, mode);
    }

    private JsonNode generateSegmented(String system, BriefSnapshot brief, ProjectSnapshot project) {
        int total = project.durationSec().intValue();
        int cap = Math.max(planMaxSec, 15);
        int k = (total + cap - 1) / cap;
        int base = total / k;
        int rem = total % k;

        ObjectNode merged = mapper.createObjectNode();
        merged.put("mode", "video");
        merged.put("title", clip(brief.rawText(), 40));
        merged.put("logline", clip(brief.rawText(), 120));
        merged.put("duration_sec", total);
        merged.put("aspect_ratio", project.aspectRatio());
        ObjectNode script = merged.putObject("script");
        script.put("theme", brief.rawText());
        script.putArray("acts");
        merged.putObject("audio").put("music_mood", "uniform, consistent");
        ObjectNode edit = merged.putObject("edit_plan");
        edit.put("fps", 30);
        edit.put("transition_default", "cut");
        edit.put("subtitle", false);

        ArrayNode allShots = merged.putArray("shots");
        ArrayNode segMeta = merged.putArray("segments");
        String prevNote = "";
        int start = 0;
        int globalNo = 0;
        for (int i = 1; i <= k; i++) {
            int chunk = base + (i <= rem ? 1 : 0);
            String note = i > 1 ? "（衔接上一段结尾：" + prevNote + "）" : "";
            StringBuilder scope = new StringBuilder()
                    .append("整片目标 ").append(total).append(" 秒，当前导演第 ").append(i)
                    .append("/").append(k).append(" 段，本段时长恰好 ").append(chunk)
                    .append(" 秒（镜头时长总和必须 == ").append(chunk).append("，单镜 <=10s）。")
                    .append("输出要精炼：每镜 positive_prompt <=60 个英文词（信息完整但勿啰嗦），镜头数尽量少而完整覆盖本段内容。")
                    .append("用户 Brief：").append(brief.rawText());
            if (brief.constraints() != null && !brief.constraints().isEmpty()) {
                scope.append(" 约束：").append(brief.constraints().toPrettyString());
            }
            String segUser = scope.toString() + note;
            JsonNode seg = callSegment(system, segUser, chunk, i, brief.rawText());
            for (JsonNode shot : seg.path("shots")) {
                ObjectNode copy = (ObjectNode) shot.deepCopy();
                globalNo++;
                copy.put("shot_no", globalNo);
                allShots.add(copy);
            }
            String last = seg.path("shots").size() > 0
                    ? seg.path("shots").get(seg.path("shots").size() - 1).path("action").asText(
                            seg.path("shots").get(seg.path("shots").size() - 1).path("positive_prompt").asText(""))
                    : seg.path("logline").asText("");
            if (!last.isBlank()) prevNote = clip(last, 160);
            ObjectNode m = segMeta.addObject();
            m.put("seq", i);
            m.put("duration_sec", chunk);
            m.put("start_sec", start);
            m.put("end_sec", start + chunk);
            start += chunk;
        }
        return merged;
    }

    private JsonNode callSegment(String system, String segUser, int chunk, int index, String briefText) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            // 段输出过长/非 JSON 时逐级压缩重出，避免同一超长内容反复失败
            String hint = switch (attempt) {
                case 2 -> "；上次输出无效。压缩重出：本段镜头 <=4 个，每镜 positive_prompt <=35 个英文词，字段齐全，只输出本段 JSON 对象";
                case 3 -> "；再压缩：本段镜头 <=3 个，每镜 positive_prompt <=25 个英文词，动作与内容要义完整，只输出 JSON 对象（不要任何解释）";
                default -> "";
            };
            LlmRequest req = new LlmRequest(system, segUser + hint, clip(briefText, 40), briefText, "video",
                    "16:9", new BigDecimal(chunk), null);
            try {
                String raw = llm.generateJson(req);
                JsonNode seg = normalizeSegment(mapper.readTree(raw), chunk);
                if (seg == null || !seg.isObject()) {
                    String head = raw == null ? "" : raw.replaceAll("\\s+", " ");
                    if (head.length() > 160) head = head.substring(0, 160);
                    String shown = head.isBlank() ? "（空/空白返回）" : head;
                    log.warn("第 {} 段第 {} 次返回非对象 JSON（长度 {}），开头: {}", index, attempt,
                            raw == null ? 0 : raw.length(), shown);
                    last = new IllegalArgumentException("plan 必须是 JSON 对象");
                    continue;
                }
                List<String> problems = DirectorPlanValidator.validate(seg, "video", new BigDecimal(chunk));
                if (problems.isEmpty()) {
                    return seg;
                }
                log.warn("第 {} 段第 {} 次校验不过: {}", index, attempt, String.join("；", problems));
                last = new IllegalArgumentException(String.join("；", problems));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                last = e;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED,
                "第 " + index + " 段导演失败（" + (last == null ? "" : last.getMessage()) + "）");
    }

    /** 容错：模型偶发返回 shots 数组或字符串 → 包装/重解析为段方案对象。 */
    private static JsonNode normalizeSegment(JsonNode raw, int chunk) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode node = raw;
        if (node != null && node.isTextual()) {
            String text = node.asText();
            node = tryParse(om, text);
            if (node == null) {
                // 尝试提取首个 {…} 或 […] 区间（模型可能带前后缀/围栏）
                int ob = text.indexOf('{');
                int ab = text.indexOf('[');
                int start = ob >= 0 && (ab < 0 || ob < ab) ? ob : ab;
                if (start >= 0) {
                    int end = start == ob ? text.lastIndexOf('}') : text.lastIndexOf(']');
                    if (end > start) {
                        node = tryParse(om, text.substring(start, end + 1));
                    }
                }
            }
            // 无法解析 → 保持原文本，由 caller 记录并重试
            if (node == null) {
                return raw;
            }
        }
        if (node != null && node.isArray()) {
            ObjectNode obj = om.createObjectNode();
            obj.put("mode", "video");
            obj.put("title", "Segment");
            obj.put("logline", "分段方案");
            obj.put("duration_sec", chunk);
            obj.put("aspect_ratio", "16:9");
            obj.set("shots", node);
            obj.putObject("script").put("theme", "");
            obj.putObject("audio").put("music_mood", "");
            obj.putObject("edit_plan").put("fps", 30).put("transition_default", "cut").put("subtitle", false);
            return obj;
        }
        return node;
    }

    private static JsonNode tryParse(com.fasterxml.jackson.databind.ObjectMapper om, String text) {
        try {
            JsonNode n = om.readTree(text);
            return (n != null && (n.isObject() || n.isArray())) ? n : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

        private static String clip(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toCharArray()) {
            if (c >= 32) sb.append(c);
        }
        String t = sb.toString().trim();
        return t.length() <= max ? t : t.substring(0, max).trim() + "…";
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
