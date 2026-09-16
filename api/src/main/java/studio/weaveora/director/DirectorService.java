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
            "video", "你是电影摄影指导+分镜师。输出且只输出 JSON（视频导演方案：mode/title/logline/duration_sec/aspect_ratio/script/shots/audio/edit_plan；镜头时长总和==目标时长，每镜 positive_prompt 20–1200）。关键规则：① 有台词的镜头尽量不用正脸大特写，改用过肩/侧脸/听者反应/手部或环境特写（图生视频模型无法对口型，正脸会让嘴型穿帮）；② 若镜头必须出现正脸说话，positive_prompt 里加 speaking、mouth moving，并置 shots[].lip_sync=true；③ 旁白镜头 lip_sync=false；④ 【动态】positive_prompt 必须写清可见的动态（主体动作/表情变化/眼神方向/次级运动/多主体互动 至少覆盖 3 类，用现在分词写进行中动作），禁写静态构图，negative 带 static, motionless, frozen —— 否则图生视频会输出几乎静止的慢动作；⑤ 【点名主体】已绑定参考图的主体必须用主体名点名（如 Baoyu (宝玉)），写明其位置（left/right/center、foreground/background），禁写 a man / the woman 这类泛称 —— 多主体同框不点名会串脸；参考图按顺序映射为 image1/image2。");

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
        JsonNode prev = latestPlan(projectId, project.approvedRevisionId(), mode);
        String user = buildUserPrompt(brief, project, mode, prev);
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
        // 新一版保留上一版同镜的中文描述与旁白（zh/narration 是用户编辑字段，LLM 不自带）
        mergePrevMeta(plan, prev);
        // P8/P9：音频设置（音色/克隆音色/角色绑定/配乐段）LLM 永远不会产出，必须继承
        mergePrevAudio(plan, prev);
        ensureShotSyncedDefault(plan);

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
        edit.put("subtitle", true);

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
                    .append(" 秒（镜头时长总和必须 == ").append(chunk).append("，单镜 <=10s）。");
            if (project.shotDurationSec() != null && project.shotDurationSec().doubleValue() >= 1.5) {
                double sd = project.shotDurationSec().doubleValue();
                int segShots = Math.max(1, (int) Math.round(chunk / sd));
                scope.append("分镜规则：每镜时长约 ").append(sd)
                        .append(" 秒，本段镜头数约 ").append(segShots)
                        .append("（镜头尽量等长 ≈每镜时长，可 ±0.5s 消化余量）。");
            }
            scope.append("输出要精炼：每镜 positive_prompt <=60 个英文词（信息完整但勿啰嗦），镜头数尽量少而完整覆盖本段内容。")
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
            obj.putObject("edit_plan").put("fps", 30).put("transition_default", "cut").put("subtitle", true);
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

    /**
     * P13：**就地保存方案**（不另存版本、不改确认态）——用于「逐条重生成/试听配音」这类
     * 正在迭代当前 take 的操作，避免每改一条就另存 vN+1 从而反复要求确认。
     *
     * <p>与 {@link #patchRevision} 的区别：后者在已确认稿上会 fork 新版本 + 需重新确认。
     */
    @Transactional
    /** 取某版本的方案 JSON（供「运动帧数上限」等接口使用；只做读取与权限校验）。 */
    public com.fasterxml.jackson.databind.JsonNode planOf(UUID userId, UUID workspaceId, UUID projectId,
                                                         UUID revisionId) {
        context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        com.fasterxml.jackson.databind.JsonNode p = r.schemaJson();
        return p == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : p;
    }

    public RevisionDetailResponse patchPlanInPlace(UUID userId, UUID workspaceId, UUID projectId,
                                                   UUID revisionId, com.fasterxml.jackson.databind.JsonNode plan) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        if (plan == null || !plan.isObject()) {
            throw new BizException(ErrorCode.VALIDATION, "方案内容为空");
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj = plan.deepCopy();
        String curMode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        enrich(obj, curMode, project.aspectRatio());
        // P13：就地保存放宽「时长一致性」（镜头时长由配音决定，总和必然变化）
        validateOrThrow(obj, curMode, project.durationSec(), false);
        r.replacePlan(obj);
        revisions.save(r);
        // 注意：这里**不能** syncShots()！
        // syncShots 是「删除并重建 shot_drafts 行」，而已有生成任务通过
        // generation_jobs.shot_id 外键引用这些行 → 重建会抛外键冲突（500 内部错误，实测踩过），
        // 且会重置分镜的 approved 状态导致后续生成被拒。
        // 逐条调音只关心 plan 里的 narrations/音色（任务从 plan 读段落地），不动分镜行即可。
        log.info("plan patched in place: project={} rev={}", projectId, revisionId);
        return toDetail(r, project.approvedRevisionId());
    }

    /**
     * P13：只更新「主体元数据」（别名 / 参与勾选）——**就地改当前版本，不另存新版本、不改变确认态**。
     *
     * <p>为什么单独开一个口子：别名/勾选这类只影响“分镜文案↔主体的匹配关系”，
     * 用户改一次就要「另存 vN+1 + 重新确认」太重（实测抱怨）。
     * 注意：不动 prompt/分镜/台词等会影响生成的字段，所以不破坏确认稿的语义。
     */
    @Transactional
    public RevisionDetailResponse patchSubjectMeta(UUID userId, UUID workspaceId, UUID projectId,
                                                   UUID revisionId, com.fasterxml.jackson.databind.JsonNode body) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        com.fasterxml.jackson.databind.JsonNode plan = r.schemaJson();
        if (plan == null || !plan.isObject()) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不存在");
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj = plan.deepCopy();
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> subs =
                new java.util.ArrayList<>(studio.weaveora.director.plan.PlanSubjects.parse(obj));
        for (com.fasterxml.jackson.databind.JsonNode in : body.path("subjects")) {
            String name = in.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            for (int i = 0; i < subs.size(); i++) {
                studio.weaveora.director.plan.PlanSubjects.Subject cur = subs.get(i);
                if (!cur.name().equals(name)) {
                    continue;
                }
                java.util.List<String> aliases = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode a : in.path("aliases")) {
                    String v = a.asText("").trim();
                    if (!v.isEmpty() && !v.equals(name)) {
                        aliases.add(v);
                    }
                }
                // P13：除别名/勾选，也支持「把选定参考图直接设为定妆照」——就地写 portraitAssetId/Version
                String portraitId = in.has("portraitAssetId")
                        ? in.path("portraitAssetId").asText("") : cur.portraitAssetId();
                int portraitVer = in.has("portraitVersion")
                        ? in.path("portraitVersion").asInt(cur.portraitVersion()) : cur.portraitVersion();
                subs.set(i, new studio.weaveora.director.plan.PlanSubjects.Subject(
                        cur.name(), cur.kind(), in.has("aliases") ? aliases : cur.aliases(),
                        in.has("enabled") ? in.path("enabled").asBoolean(true) : cur.enabled(),
                        cur.locked(), cur.refs(), portraitId, portraitVer));
            }
        }
        studio.weaveora.director.plan.PlanSubjects.write(obj, subs);
        // 注：**允许**多个主体共用同一张定妆照（双胞胎/同人等就是合理需求），不拦；
        //     只记一条日志，便于排查“怎么两个人长得一样”。
        r.replacePlan(obj);
        revisions.save(r);
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (studio.weaveora.director.plan.PlanSubjects.Subject cur : subs) {
            String pid = cur.portraitAssetId();
            if (pid != null && !pid.isBlank()) {
                String other = seen.put(pid, cur.name());
                if (other != null) {
                    log.info("多个主体共用同一张定妆照：{} / {}（允许，如双胞胎）", other, cur.name());
                }
            }
        }
        log.info("subjects meta patched in place: project={} rev={} n={}", projectId, revisionId, subs.size());
        return toDetail(r, project.approvedRevisionId());
    }

    @Transactional
    public RevisionDetailResponse patchRevision(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                                PatchRevisionRequest req) {
        ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        boolean copyFromApproved = revisionId.equals(project.approvedRevisionId());
        JsonNode incoming = req.plan();
        String curMode = r.schemaJson() == null ? "" : r.schemaJson().path("mode").asText("");
        String newMode = incoming.path("mode").asText(curMode);
        if (!curMode.equals(newMode)) {
            throw new BizException(ErrorCode.VALIDATION, "不可通过编辑切换导演模式（请新建项目/brief）");
        }
        enrich(incoming, curMode, project.aspectRatio());
        validateOrThrow(incoming, curMode, project.durationSec());
        if (copyFromApproved) {
            // 在已确认版本上微调 → 另存新版本号（保留旧确认稿；前端切到新版本，重新确认即 vN+1）
            int no = nextRevisionNo(projectId);
            PromptRevision copy = PromptRevision.create(workspaceId, projectId, r.briefId(), no,
                    "user", incoming, userId);
            revisions.save(copy);
            if ("video".equals(curMode)) {
                syncShots(copy.id(), incoming);
            }
            return toDetail(copy, project.approvedRevisionId());
        }
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
        JsonNode plan = r.schemaJson();
        // 确认前：对改过画面动作但未 AI 同步的镜头自动补 EN 提示词（已同步则跳过，不再额外调 LLM）
        if ("video".equals(plan.path("mode").asText("")) && autoSyncPending(plan)) {
            r.replacePlan(plan);
            revisions.save(r);
            syncShots(r.id(), plan);
        }
        validateOrThrow(plan, plan.path("mode").asText("image"), project.durationSec());
        if ("video".equals(plan.path("mode").asText(""))) {
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
        validateOrThrow(plan, mode, durationSec, true);
    }

    /**
     * @param strictDuration 是否严格校验时长一致性。
     *       P13：「按配音校准时长」会按配音实际时长改写镜头时长 → 对**就地保存**放宽，
     *       否则用户校准完存不进去（AI 生成方案仍走严格校验）。
     */
    private void validateOrThrow(JsonNode plan, String mode, BigDecimal durationSec, boolean strictDuration) {
        List<String> problems = DirectorPlanValidator.validate(plan, mode, durationSec, strictDuration);
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

    /** ① 中文描述 → LLM 重写该镜正/负提示词（供前端确认后再应用）。 */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> rewritePrompt(UUID userId, UUID workspaceId, UUID projectId,
                                                       String rawText, String originalPositive,
                                                       String originalNegative, String lang) {
        context.require(userId, workspaceId, projectId);
        boolean amend = originalPositive != null && !originalPositive.isBlank();
        // ★ 语言可选（2026-09-16）：lang='zh' → 正/负向词都用**中文**（Qwen 系模型对中文理解好；
        //   用户明确要求“选中文就返回中文提示词并填充两个框”）；'en'（默认）保持原行为。
        boolean zh = lang != null && "zh".equalsIgnoreCase(lang.trim());
        String sysBase = zh
                ? "你是专业提示词工程师。positive_prompt 与 negative_prompt 均使用**中文**；"
                + "positive_prompt 含主体（要点名角色名）/镜头/光线/氛围/质感细节（<=60 个中文词）；"
                + "negative_prompt 为中文常见负面项（模糊、低质量、畸形、多余肢体、重复、水印、文字、过曝 等）。"
                + "只输出 JSON：{\"positive_prompt\":\"...\",\"negative_prompt\":\"...\"}"
                : "你是专业提示词工程师。positive_prompt 与 negative_prompt 均使用英文；"
                + "positive_prompt 含主体/镜头/光线/氛围/质感细节（<=60 英文词）；"
                + "negative_prompt 为英文常见负面项（blurry, low quality, distorted, extra limbs, "
                + "duplicated, watermark, text, oversaturated 等）。只输出 JSON：{\"positive_prompt\":\"...\",\"negative_prompt\":\"...\"}";
        // ★ 点名主体（2026-09-16 用户要求）：原正词里的角色名是身份锚定信号，不能被泛称冲掉
        sysBase += " 硬规则：原正词里的角色名（专有名词）必须**保留并点名**，不得换成 a man / the woman / 一个男人这类泛称；"
                + "多主体同框时写清各自位置与左右关系（left/right/center、foreground/background）。";
        String system;
        String user;
        if (amend) {
            // 修正模式：保留原有画面/风格基础上，按新的中文动作做补充或修正，而非整句翻译
            system = sysBase + " 当提供“原正向/负向提示词”与“新的中文动作描述”时：基于原正向词，结合新动作的差异"
                    + "做补充/修正（保留镜头主体、风格、光线基调的一致性），不要整句直译中文，不要丢掉原有可取内容；"
                    + "如动作无实质改动则保持原词不动。";
            user = "原 positive_prompt：\n" + originalPositive
                    + "\n\n原 negative_prompt：\n" + (originalNegative == null ? "" : originalNegative)
                    + "\n\n新的中文动作描述（action）：\n" + rawText
                    + "\n\n请输出修正后的 JSON。";
        } else {
            system = sysBase;
            user = "中文描述：\n" + rawText + "\n请按上述要求输出 JSON。";
        }
        LlmRequest req = new LlmRequest(system, user, "rewrite", rawText, "image", "16:9", null, null);
        try {
            String raw = llm.generateJson(req);
            JsonNode n = mapper.readTree(raw);
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            out.put("positive_prompt", n.path("positive_prompt").asText(""));
            out.put("negative_prompt", n.path("negative_prompt").asText(""));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("提示词重写失败: " + e.getMessage(), e);
        }
    }

    private String buildUserPrompt(BriefSnapshot brief, ProjectSnapshot project, String mode,
                                   JsonNode prevPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目标题/画幅：").append(project.aspectRatio());
        if (project.durationSec() != null) sb.append("，目标时长 ").append(project.durationSec()).append(" 秒");
        // 分镜规则：每镜时长偏好 → 镜头数 = 总时长 / 每镜时长（倍数关系），镜头尽量等长
        if ("video".equals(project.mode()) && project.shotDurationSec() != null
                && project.durationSec() != null) {
            double sd = project.shotDurationSec().doubleValue();
            if (sd >= 1.5) {
                int n = Math.max(1, (int) Math.round(project.durationSec().intValue() / sd));
                sb.append("；分镜规则：每镜时长约 ").append(sd)
                        .append(" 秒，全片镜头数约 ").append(n)
                        .append("（按总时长与每镜时长的倍数关系定镜头数，各镜尽量等长 ≈每镜时长，可 ±0.5s 消化余量，单镜 ≤10s）");
            }
        }
        sb.append("\n\n用户 Brief：\n").append(brief.rawText());
        if (brief.constraints() != null && !brief.constraints().isEmpty()) {
            sb.append("\n\n约束（constraints）：\n").append(brief.constraints().toPrettyString());
        }
        if (prevPlan != null) {
            sb.append("\n\n上一版方案（作为本版基准）：\n").append(planSummary(prevPlan));
            sb.append("\n\n要求：基于上一版方案重导出新一版——镜头顺序与叙事保持连贯，")
                    .append("若无新需求则延续上一版结构/文案并做打磨精修；仅当用户 Brief 有新要求时才调整镜头内容与数量。");
        }
        // P4：告知导演“哪些主体有参考图”（LLM 看不到图；plan 级 + brief 级合并去重）
        java.util.LinkedHashSet<String> refSubjects = new java.util.LinkedHashSet<>();
        collectRefSubjects(refSubjects, prevPlan == null ? null : prevPlan.get("referenceAssets"));
        collectRefSubjects(refSubjects, brief.constraints() == null ? null : brief.constraints().get("referenceAssets"));
        if (!refSubjects.isEmpty()) {
            StringBuilder map = new StringBuilder();
            int slot = 1;
            for (String s : refSubjects) {
                if (map.length() > 0) map.append("、");
                map.append("image").append(slot++).append("=").append(s);
            }
            sb.append("\n\n可用参考图主体：”").append(String.join("、", refSubjects))
                    .append("”。这些主体的形象（面容/服饰）已有参考图锚定；请在各镜 positive_prompt 里明确写出该主体形象以参考图为准")
                    .append("（英文可写 character appearance strictly follows the provided reference image），")
                    .append("并在 action 中保留主体名，便于系统为对应镜头绑定参考图。")
                    // ★ 点名主体（2026-09-16 用户要求）：映射顺序与系统下发参考图的顺序一致（image1=第一个主体）
                    .append("\n【硬规则·点名主体】该主体出镜的镜头，positive_prompt 必须**用上面的主体名点名**（中文名可保留，如 Baoyu (宝玉)），")
                    .append("并写清其位置与左右关系（left/right/center、foreground/background）；禁止用 a man / the woman 这类泛称替代。")
                    .append("多主体同框时必须逐个点名并写清相互关系（例：Baoyu on the left, Keqing on the right, facing each other）。")
                    .append("系统按参考图顺序把主体映射为 image1 / image2 …（").append(map)
                    .append("）；需要时可在 positive_prompt 里显式写 Baoyu (image1) 加固对应关系。");
        }
        sb.append("\n\n请按 System Prompt 的 JSON 结构输出。");
        return sb.toString();
    }

    /** 收集 referenceAssets[{assetId,subject}] 里的主体名（去重）。 */
    private static void collectRefSubjects(java.util.Set<String> out, JsonNode arr) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode b : arr) {
            String s = b.path("subject").asText("");
            if (!s.isBlank()) out.add(s);
        }
    }

    /** 最近一版同模式方案（供“再导演基于上一版”注入）。 */
    /** 再导演参考：优先最新已确认版本方案；无确认稿则用最新 revision。 */
    private JsonNode latestPlan(UUID projectId, UUID approvedId, String mode) {
        try {
            PromptRevision pick = null;
            if (approvedId != null) {
                pick = revisions.findById(approvedId).orElse(null);
            }
            if (pick == null) {
                pick = revisions.findTopByProjectIdOrderByRevisionNoDesc(projectId).orElse(null);
            }
            if (pick == null || pick.schemaJson() == null) return null;
            String m = pick.schemaJson().path("mode").asText("");
            return m.equals(mode) ? pick.schemaJson() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 上一版方案摘要（控制 token，逐镜给画面与正词要点）。 */
    private static String planSummary(JsonNode plan) {
        StringBuilder s = new StringBuilder();
        s.append("标题：").append(plan.path("title").asText("")).append("；logline：")
                .append(plan.path("logline").asText("").length() > 100
                        ? plan.path("logline").asText("").substring(0, 100) + "…"
                        : plan.path("logline").asText(""))
                .append("；总时长 ").append(plan.path("duration_sec").asDouble(0)).append("s");
        int i = 0;
        for (JsonNode sh : plan.path("shots")) {
            i++;
            if (i > 12) { s.append("\n…共 ").append(plan.path("shots").size()).append(" 镜（其余略）"); break; }
            s.append("\n镜头").append(sh.path("shot_no").asInt(i))
                    .append("[").append(sh.path("duration_sec").asDouble(0)).append("s]");
            String act = sh.path("action").asText("");
            if (!act.isBlank()) s.append(" 画面:").append(act.length() > 120 ? act.substring(0, 120) + "…" : act);
            String pos = sh.path("positive_prompt").asText("");
            if (!pos.isBlank()) s.append(" pos:").append(pos.length() > 160 ? pos.substring(0, 160) + "…" : pos);
            String nar = sh.path("narration").asText("");
            if (!nar.isBlank()) s.append(" 旁白:").append(nar);
            String zh = sh.path("zh").asText("");
            if (!zh.isBlank()) s.append(" 中文描述:").append(zh.length() > 120 ? zh.substring(0, 120) + "…" : zh);
        }
        return s.toString();
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

    /** 把上一版每镜的用户字段（zh/narration/en_synced）迁移到新一版对应镜位。 */
    /**
     * 继承上一版的用户编辑字段（按 shot_no 对齐，退化到下标）。
     *
     * <p>LLM 新出的镜头里没有这些字段，若不继承，用户填的旁白/多段语音会凭空消失。
     * P8 的 {@code narrations}（含说话人/起点/结束点/音色覆盖/语速）也在这一步保留。
     */
    static void mergePrevMeta(JsonNode plan, JsonNode prev) {
        if (prev == null || !prev.has("shots") || !plan.has("shots")) return;
        JsonNode ps = prev.path("shots");
        int i = 0;
        for (JsonNode ns : plan.path("shots")) {
            if (!ns.isObject()) continue;
            JsonNode p = findPrevShot(ps, ns, i);
            i++;
            if (p == null || !p.isObject()) continue;
            ObjectNode o = (ObjectNode) ns;
            for (String f : new String[]{"zh", "narration"}) {
                if (p.has(f) && !p.path(f).asText("").isBlank()
                        && o.path(f).asText("").isBlank()) {
                    o.put(f, p.path(f).asText(""));
                }
            }
            // P8：镜内多段语音（旁白+台词）——LLM 不产出，新镜没有就整体继承
            JsonNode pn = p.get("narrations");
            JsonNode cn = o.get("narrations");
            boolean currEmpty = cn == null || !cn.isArray() || cn.isEmpty();
            if (currEmpty && pn != null && pn.isArray() && !pn.isEmpty()) {
                o.set("narrations", pn.deepCopy());
            }
            if (p.has("en_synced") && !o.has("en_synced")) {
                o.put("en_synced", p.path("en_synced").asBoolean(true));
            }
        }
    }

    /** 优先按 shot_no 找上一版同镜（镜头数变了也能对上），否则按下标。 */
    private static JsonNode findPrevShot(JsonNode prevShots, JsonNode newShot, int index) {
        int no = newShot.path("shot_no").asInt(0);
        if (no > 0) {
            for (JsonNode p : prevShots) {
                if (p.path("shot_no").asInt(-1) == no) return p;
            }
        }
        return index < prevShots.size() ? prevShots.get(index) : null;
    }

    /**
     * P8/P9：继承上一版的**用户专属音频设置**。
     *
     * <p>{@code voice / voiceBindings / voicePresets / music} 这四个字段 LLM 永远写不出来，
     * 全是用户在界面上配的。导演新一版时如果不继承，用户录好的音色和配好的配乐段就凭空消失了
     * （实际踩到：用户问“怎么把角色音色绑定搞没了”）。
     *
     * <p>只在新方案对应字段为空时覆盖；{@code music_mood} 不动（LLM 可能会给，且用户可改）。
     */
    static void mergePrevAudio(JsonNode plan, JsonNode prev) {
        if (prev == null || !(plan instanceof ObjectNode po)) return;
        JsonNode pa = prev.path("audio");
        if (!pa.isObject()) return;
        ObjectNode a = po.path("audio").isObject()
                ? (ObjectNode) po.get("audio") : po.putObject("audio");
        for (String f : new String[]{"voice", "voicePresets", "voiceBindings", "music"}) {
            JsonNode v = pa.get(f);
            if (blank(v)) continue;
            if (!blank(a.get(f))) continue;   // 新方案已有值（罕见）则不覆盖
            a.set(f, v.deepCopy());
        }
    }

    private static boolean blank(JsonNode v) {
        return v == null || v.isNull()
                || (v.isArray() && v.isEmpty())
                || (v.isTextual() && v.asText().isBlank());
    }

    /** 新导出的镜头若无同步标记，视为已同步（LLM 自带 EN）；用户改动作后由前端置 false。 */
    private static void ensureShotSyncedDefault(JsonNode plan) {
        if (plan == null || !plan.has("shots")) return;
        for (JsonNode ns : plan.path("shots")) {
            if (ns.isObject() && !ns.has("en_synced")) {
                ((ObjectNode) ns).put("en_synced", true);
            }
        }
    }

    /** 确认前自动补词：仅同步“en_synced!=true 且 action 非空”的镜头（一次 LLM 批量）。 */
    private boolean autoSyncPending(JsonNode plan) {
        if (plan == null || !plan.has("shots")) return false;
        java.util.List<ObjectNode> pend = new java.util.ArrayList<>();
        for (JsonNode ns : plan.path("shots")) {
            if (!ns.isObject()) continue;
            ObjectNode o = (ObjectNode) ns;
            boolean synced = o.path("en_synced").asBoolean(true);
            String act = o.path("action").asText("");
            if (!synced && !act.isBlank()) {
                pend.add(o);
            }
        }
        if (pend.isEmpty()) return false;
        StringBuilder sb = new StringBuilder("以下镜头“画面动作(action)已修改”，请基于各自原有的 positive/negative 提示词做补充/修正"
                + "（保留主体/风格/光线基调，按新动作差异调整；不要整句直译中文；动作无实质变化则保持原词）。\n");
        for (ObjectNode o : pend) {
            sb.append("镜头").append(o.path("shot_no").asInt(0))
                    .append(" 原positive：").append(o.path("positive_prompt").asText("")).append("\n")
                    .append("  原negative：").append(o.path("negative_prompt").asText("")).append("\n")
                    .append("  新action：").append(o.path("action").asText("")).append("\n");
        }
        sb.append("输出 JSON：{\"shots\":[{\"shot_no\":N,\"positive_prompt\":\"...\",\"negative_prompt\":\"...\"}]}");
        String sys = "你是专业提示词工程师。positive 与 negative 均使用英文；positive<=60 个英文词（主体/镜头/光线/氛围/质感）；negative 为英文常见负面项。";
        try {
            String raw = llm.generateJson(new LlmRequest(sys, sb.toString(), "autosync", "", "video",
                    plan.path("aspect_ratio").asText("16:9"), null, null));
            JsonNode out = mapper.readTree(raw);
            for (JsonNode s : out.path("shots")) {
                int no = s.path("shot_no").asInt(-1);
                for (ObjectNode o : pend) {
                    if (o.path("shot_no").asInt() == no) {
                        if (s.has("positive_prompt") && !s.path("positive_prompt").asText("").isBlank()) {
                            o.put("positive_prompt", s.path("positive_prompt").asText(""));
                        }
                        if (s.has("negative_prompt") && !s.path("negative_prompt").asText("").isBlank()) {
                            o.put("negative_prompt", s.path("negative_prompt").asText(""));
                        }
                        o.put("en_synced", true);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("autoSyncPending failed: {}", e.getMessage());
            return false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
