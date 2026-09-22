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
import studio.weaveora.director.plan.SceneSwitchNotices;
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
            "video", "你是电影摄影指导+分镜师。输出且只输出 JSON（视频导演方案：mode/title/logline/setting{era,notes}/duration_sec/aspect_ratio/script/shots/audio/edit_plan；镜头时长总和==目标时长，每镜 positive_prompt 20–1200）。关键规则：① **必须给出 setting.era（剧情年代，如“清代 · 康熙年间”），且全片不得混用年代元素**；主体档案由用户在面板维护，你只遵守不强编；② 有台词的镜头尽量不用正脸大特写，改用过肩/侧脸/听者反应/手部或环境特写（图生视频模型无法对口型，正脸会让嘴型穿帮）；③ 若镜头必须出现正脸说话，positive_prompt 里加 speaking、mouth moving，并置 shots[].lip_sync=true；④ 旁白镜头 lip_sync=false；⑤ 【动态】positive_prompt 必须写清可见的动态（主体动作/表情变化/眼神方向/次级运动/多主体互动 至少覆盖 3 类，用现在分词写进行中动作），禁写静态构图，negative 带 static, motionless, frozen —— 否则图生视频会输出几乎静止的慢动作；⑥ 【点名主体】已绑定参考图的主体必须用主体名点名（如 Baoyu (宝玉)），禁写 a man / the woman 这类泛称；且描写必须与其**性别/年龄段/体态**一致（**不得把男性写成女性**）；⑦ 【不写画面方位】禁止 left/right/center、foreground/background 这类画面坐标 —— 位置由用户在「位置总控」设的区域框统一由系统下发，文案里猜的方位会与之冲突导致位置错位；参考图按顺序映射为 Picture 1/Picture 2。不要输出 subjects 字段。");

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
        String promptLang = resolvePromptLang(req, brief);
        if ("zh".equals(promptLang)) {
            // 用户 2026-09-17 裁定：转项目时可选提示词语言（默认中文）→ **首次生成就跟随**。
            // ⚠️ 2026-09-17 实测教训：只把「语言覆盖」追加到 **user** 消息时，模型仍输出英文
            // （系统词里“prompt 字段用英文”出现多次，权重更高）。所以挂到 **system** 上 —— 
            // 追加在系统词末尾且标明最高优先级，才是真正能压过它的位置。
            system = system + PROMPT_LANG_ZH_OVERRIDE;
        }
        // ★ 2026-09-22（用户要求「模板也进导演首次生成」）：把**官方口径的提示词模板**也随首次生成带给 LLM。
        //   与「AI 生成/更新提示词」共用同一份模板文件（单一真源），不往 .md 里复制内容 ——
        //   否则两处漂移（改了模板忘了改系统词）。video 模式：分镜正词=图生视频（运动+运镜）、
        //   关键帧正词=出图；image 模式：均为出图正词。
        String tplFirst = officialTemplateBlock("video".equals(mode) ? "shot" : "image", null);
        if (!tplFirst.isEmpty()) {
            system = system + tplFirst;
        }
        log.info("导演首次生成：模式={}、模板={}（语言={}）", mode, !tplFirst.isEmpty(), promptLang);
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
        // 把语言钉进方案（项目级真源）：项目页「AI 更新提示词」的默认语言、后续重生成都看它
        if (plan instanceof ObjectNode objLang) {
            objLang.put("promptLang", promptLang);
        }
        // 新一版保留上一版同镜的中文描述与旁白（zh/narration 是用户编辑字段，LLM 不自带）
        mergePrevMeta(plan, prev);
        // ★ P14：主体（定妆照/素材图/人物档案）与「设定年代」都是**用户拥有**的数据，LLM 不得覆盖
        mergePrevSubjectsAndSetting(plan, prev);
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
        List<String> notices = SceneSwitchNotices.of(plan);
        if (!notices.isEmpty()) {
            // 只提示不拦（用户口径）：场景切换过密/一镜内换场景 —— 记录到日志便于排查，不打回生成。
            log.info("plan notices project={} revision={}: {}", projectId, revisionNo, String.join(" / ", notices));
        }
        log.info("director generate project={} revision={} source={} shots={}", projectId, revisionNo,
                llm.source(), plan.path("shots").size());
        return new GenerateResponse(rev.id(), revisionNo, llm.source(), "directing", plan, notices);
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
        // ★ 防“旧草稿洗掉 setting”：前端草稿若是在 setting 写入之前加载的，提交里就没这个键
        if (inheritSettingIfAbsent(obj, r.schemaJson())) {
            log.info("plan/inplace 继承上一版 setting（提交里没有该键）: project={} rev={}", projectId, revisionId);
        }
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
            java.util.List<String> aliases = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode a : in.path("aliases")) {
                String v = a.asText("").trim();
                if (!v.isEmpty() && !v.equals(name)) {
                    aliases.add(v);
                }
            }
            // P13：除别名/勾选，也支持「把选定参考图直接设为定妆照」——就地写 portraitAssetId/Version
            // 先按「本名或别称」找（别称命中也要认，否则前端拿着别称名提交会凭空多出一个主体）
            int hit = -1;
            for (int i = 0; i < subs.size(); i++) {
                if (subs.get(i).name().equals(name)) {
                    hit = i;
                    break;
                }
                if (hit < 0 && studio.weaveora.director.plan.PlanSubjects.isSameSubject(subs.get(i), name)) {
                    hit = i;    // 别称命中（继续找精确同名，精确优先）
                }
            }
            if (hit < 0) {
                // ★ 2026-09-16 新增主体（用户要求：「剧情主体」区要有「+ 新增主体」按钮）——
                //   前端提交的整份主体列表里，方案里还不存在的名字即为新增。
                String kind = in.path("kind").asText(studio.weaveora.director.plan.PlanSubjects.KIND_PERSON)
                        .trim().toLowerCase();
                if (!java.util.List.of(studio.weaveora.director.plan.PlanSubjects.KIND_PERSON,
                        studio.weaveora.director.plan.PlanSubjects.KIND_VEHICLE,
                        studio.weaveora.director.plan.PlanSubjects.KIND_OBJECT,
                        studio.weaveora.director.plan.PlanSubjects.KIND_SCENE).contains(kind)) {
                    kind = studio.weaveora.director.plan.PlanSubjects.KIND_PERSON;
                }
                subs.add(new studio.weaveora.director.plan.PlanSubjects.Subject(name, kind, aliases,
                        !in.has("enabled") || in.path("enabled").asBoolean(true), false, java.util.List.of(),
                        in.path("portraitAssetId").asText(""), in.path("portraitVersion").asInt(0),
                        studio.weaveora.director.plan.PlanSubjects.Traits.parse(in)));
                log.info("subject created in place: project={} rev={} name={} kind={}", projectId, revisionId, name, kind);
                continue;
            }
            {
                studio.weaveora.director.plan.PlanSubjects.Subject cur = subs.get(hit);
                boolean exact = cur.name().equals(name);
                String portraitId = in.has("portraitAssetId")
                        ? in.path("portraitAssetId").asText("") : cur.portraitAssetId();
                int portraitVer = in.has("portraitVersion")
                        ? in.path("portraitVersion").asInt(cur.portraitVersion()) : cur.portraitVersion();
                // 别称命中时不覆盖已有别名（否则「宝二爷」那条空别名会把「宝玉」的别名洗掉），改成并集
                java.util.List<String> nextAliases = !in.has("aliases") ? cur.aliases()
                        : exact ? aliases
                        : java.util.stream.Stream.concat(cur.aliases().stream(), aliases.stream())
                            .distinct().toList();
                // ★ P14：主体设定（性别/年龄/身高/体态/性格/外貌）就地可改。
                //   只要提交里带了 traits（以 "hasTraits" 标记），就用提交的那份（允许清空）。
                studio.weaveora.director.plan.PlanSubjects.Traits nextTraits = in.path("hasTraits").asBoolean(false)
                        ? studio.weaveora.director.plan.PlanSubjects.Traits.parse(in)
                        : cur.traitsOrEmpty();
                subs.set(hit, new studio.weaveora.director.plan.PlanSubjects.Subject(
                        cur.name(), cur.kind(), nextAliases,
                        in.has("enabled") ? in.path("enabled").asBoolean(true) : cur.enabled(),
                        cur.locked(), cur.refs(), portraitId, portraitVer, nextTraits));
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
        // ★ 防“旧草稿洗掉 setting”（2026-09-16 夜实测：rev52→rev53 就是这么丢的年代）
        if (incoming instanceof ObjectNode inc && inheritSettingIfAbsent(inc, r.schemaJson())) {
            log.info("patchRevision 继承上一版 setting（提交里没有该键）: project={} rev={}", projectId, revisionId);
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

    /**
     * P13b/P14：定妆图默认正/负向提示词（前端弹框预填）。
     *
     * <p>kind 与人物档案（性别/年龄/身高/体态/性格/外貌）从**方案 subjects[]** 读，
     * 保证「弹框里看到的就是 createPortraitJob 真正会用的那份」。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> portraitPromptDefaults(UUID userId, UUID workspaceId, UUID projectId,
                                                                 UUID revisionId, String subject, int refCount) {
        context.require(userId, workspaceId, projectId);
        PromptRevision r = findRevision(workspaceId, projectId, revisionId);
        JsonNode plan = r.schemaJson() == null ? mapper.createObjectNode() : r.schemaJson();
        String kind = null;
        studio.weaveora.director.plan.PlanSubjects.Traits traits =
                studio.weaveora.director.plan.PlanSubjects.Traits.EMPTY;
        for (studio.weaveora.director.plan.PlanSubjects.Subject s
                : studio.weaveora.director.plan.PlanSubjects.parse(plan)) {
            if (studio.weaveora.director.plan.PlanSubjects.isSameSubject(s, subject)) {
                kind = s.kind();
                traits = s.traitsOrEmpty();
                break;
            }
        }
        if (kind == null && (subject == null || subject.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION, "缺少 subject（要生成哪个主体的定妆图）");
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("positivePrompt", studio.weaveora.director.SubjectPrompts.portraitPrompt(
                subject, kind, Math.max(0, refCount), traits));
        out.put("negativePrompt", studio.weaveora.director.SubjectPrompts.portraitNegativePrompt());
        out.put("kind", kind == null ? "" : kind);
        out.put("traits", traits.isEmpty() ? java.util.Map.of() : java.util.Map.of(
                "gender", traits.gender() == null ? "" : traits.gender(),
                "age", traits.age() == null ? "" : traits.age(),
                "height", traits.height() == null ? "" : traits.height(),
                "build", traits.build() == null ? "" : traits.build(),
                "personality", traits.personality() == null ? "" : traits.personality(),
                "appearance", traits.appearance() == null ? "" : traits.appearance()));
        return out;
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

    /**
     * 提示词语言：请求参数 &gt; {@code brief.constraints.promptLang} &gt; 默认 {@code en}。
     *
     * <p>默认 en 是**刻意保留的历史口径**（{@code director_video_system.md} 里写着 prompt 字段用英文），
     * 只有显式选中文（转项目弹层默认中文 / 项目页选了中文重新生成）才覆盖成中文。
     */
    private static String resolvePromptLang(GenerateRequest req, BriefSnapshot brief) {
        String v = req == null ? null : req.promptLang();
        if (v == null || v.isBlank()) {
            JsonNode c = brief == null ? null : brief.constraints();
            v = c == null || c.isNull() ? "" : c.path("promptLang").asText("");
        }
        return "zh".equalsIgnoreCase(v.trim()) ? "zh" : "en";
    }

    /** 中文提示词的显式覆盖指令（追加到**系统词末尾**，才压得过系统词里「prompt 字段用英文」）。 */
    private static final String PROMPT_LANG_ZH_OVERRIDE = """

            【语言覆盖·最高优先级（优先于本文档前面所有关于语言的要求）】
            本项目的提示词语言 = **中文**：
            · positive_prompt / negative_prompt / keyframes[].positive_prompt 一律用**中文**书写；
            · 专有名词可用「宝玉 (Baoyu)」这种中英并列形式，但**不要整句英文**；
            · 其它描述性字段（action / composition 等）也用中文。
            前面所有写「prompt 字段用英文」「英文 positive_prompt」的规则**本次全部不适用**。
            """;

    /**
     * ★ 2026-09-22（用户要求）：「使用模板」—— 把**官方口径的提示词模板**随请求带给 LLM。
     *
     * <p>为什么要有：官方口径很明确（图生视频 = <b>运动 + 运镜</b>，图像已确定主体/场景/风格；
     * 图像编辑 = 1–3 张图 + <b>一条</b>指令 + 「图N」按送入顺序指代 + 短句不冗余 + 冲突要拆步），
     * 但这些规则散在官方文档里；LLM 每次自由发挥就会漂回老路（把静态构图/外观/人物档案也写进视频正词）。
     * 把模板原文注入请求，保证写法正确；模板只约束**写法**，不改变**输出语言**（语言仍由 lang 决定）。
     *
     * <p>模板文件：{@code prompts/prompt_template_motion.md}（图生视频）、
     * {@code prompts/prompt_template_still.md}（出图/关键帧）。文件读不到时返回空串（不阻塞重写）。
     *
     * @param scope  {@code image} = 只写出图正词（项目级「AI 生成提示词」）；
     *               其它/null = 分镜（{@code positive_prompt} 是图生视频正词 + {@code keyframes[]} 是出图正词，两份都给）
     * @param frames 仅用于日志（帧数）
     */
    static String officialTemplateBlock(String scope, java.util.List<RewriteFrame> frames) {
        boolean shot = scope == null || scope.isBlank() || !"image".equalsIgnoreCase(scope.trim());
        StringBuilder sb = new StringBuilder();
        appendTemplateResource(sb, "prompt_template_motion.md",
                shot ? "图生视频正词（= positive_prompt）" : null);
        appendTemplateResource(sb, "prompt_template_still.md",
                shot ? "出图正词（= keyframes[].positive_prompt）" : "出图正词（= positive_prompt）");
        if (sb.length() == 0) {
            return "";
        }
        return "\n\n================ 提示词模板（官方口径 · 用户已勾选「使用模板」） ================\n"
                + "下面模板是**写法硬约束**，本次输出必须遵守；它们**不改变输出语言**（语言仍按 lang 决定）。\n"
                + sb
                + "\n================ 模板结束 ================";
    }

    /** 读一个模板资源并带标题拼到 sb；title 为 null 表示本次不需要该模板。 */
    private static void appendTemplateResource(StringBuilder sb, String file, String title) {
        if (title == null) {
            return;
        }
        try {
            String t = new String(new ClassPathResource("prompts/" + file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!t.isBlank()) {
                sb.append("\n---\n【").append(title).append("｜模板 prompts/").append(file).append("】\n")
                  .append(t.trim()).append('\n');
            }
        } catch (IOException e) {
            log.warn("prompts/{} 读取失败，本次不带该模板（不阻塞重写）：{}", file, e.toString());
        }
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

    /**
     * 运镜关键帧（P2）：每帧有自己的构图与正词。
     *
     * <p>为什么 AI 更新提示词必须**逐帧**一起更新（2026-09-16 用户实测）：第 3 镜是 2 帧运镜镜头，
     * 「AI 同步提示词」只改了 shot 级 positive_prompt，`keyframes[].positive_prompt` 还是旧稿
     * → 生成时**每帧用的是自己的正词**（JobService 逐帧建 still 任务），于是帧提示词与主正词语言/内容
     * 都对不上（用户看到的「关键帧还是英文」）。所以重写必须整镜（含帧）一起做。
     */
    public record RewriteFrame(String label, String composition, String positivePrompt, String negativePrompt) {
    }

    /**
     * ① 中文描述 → LLM 重写该镜正/负提示词（含运镜关键帧逐帧重写）。
     *
     * @param lang   'zh' → 正/负词全部中文（含每帧）；'en'（默认）→ 英文
     * @param frames 运镜关键帧；空/单帧时为 null（普通单帧镜头）
     * @return {positive_prompt, negative_prompt, keyframes:[{positive_prompt, negative_prompt}]}
     */
    public java.util.Map<String, Object> rewritePrompt(UUID userId, UUID workspaceId, UUID projectId,
                                                       String rawText, String originalPositive,
                                                       String originalNegative, String lang,
                                                       java.util.List<RewriteFrame> frames,
                                                       Boolean useTemplate, String templateScope) {
        ProjectContextPort.ProjectSnapshot project = context.require(userId, workspaceId, projectId);
        boolean amend = originalPositive != null && !originalPositive.isBlank();
        boolean zh = lang != null && "zh".equalsIgnoreCase(lang.trim());
        boolean multi = frames != null && frames.size() > 1;
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
                + "多主体同框时写清相互关系与动作互动。"
                // ★ 2026-09-16 修正：禁止在文案里猜画面方位（与「位置总控」的区域框会直接矛盾 → 位置错位）
                + "但**不要写画面方位**（left/right/center、foreground/background 以及“在画面左侧”这类词）："
                + "主体在画面中的位置与大小由用户设置的区域框统一由系统下发给模型，文案里再写一套会互相冲突。"
                + "若原正词里有方位词，改写时请**删掉**它们。";
        // ★ 语言纯度（2026-09-16 用户报「一半中文一半英文」）：语言必须**整条一致**，不得只换一半
        sysBase += zh
                ? "【语言硬规则】输出必须**全部为中文**：禁止混入英文单词（负面词、质量词也一样），"
                + "角色专有名词/模型名可保留原文。negative_prompt 必须是中文负面词清单，不得含 blurry/watermark 这类英文词。"
                : "【语言硬规则】输出必须**全部为英文**，不得混入中文（角色专有名词可保留原文）。";
        // ★ P14（2026-09-16）：把方案的「设定年代」与「人物档案」也告诉重写模型。
        //   不告诉它，它就会照自己想象写（“宝玉”常被写成女性/写出现代元素），重写一次的代价就是重出一遍图。
        JsonNode rwPlan = null;
        if (project.approvedRevisionId() != null) {
            rwPlan = revisions.findByIdAndProjectIdAndWorkspaceId(project.approvedRevisionId(), projectId, workspaceId)
                    .map(PromptRevision::schemaJson).orElse(null);
        }
        String settingBlock = settingAndSubjectBlock(rwPlan);
        StringBuilder sysPlus = new StringBuilder(sysBase);
        if (!settingBlock.isEmpty()) {
            sysPlus.append("\n\n").append(settingBlock)
                    .append("\n【硬规则·一致性】你写的 positive_prompt 必须与上面的**年代/性别/年龄/体态**一致：")
                    .append("尤其不得把男性写成女性（或反之），不得写与年龄不符的称谓，不得出现与年代不符的物件。");
        }
        sysBase = sysPlus.toString();
        String system;
        String user;
        String outSpec = zh ? "中文" : "英文";
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
        if (multi) {
            // 逐帧重写：帧数必须一致，语言一致，每帧正词必须匹配它自己的构图（composition）
            system = sysBase + " 本镜是**运镜镜头**（一条相机路径分多帧）：必须逐帧重写每帧的正/负向词，"
                    + "每帧的正词必须与它自己的 composition（机位/朝向/遮挡/前景关系）相符，"
                    + "帧与帧之间保持主体、风格、光线一致，但**构图按各自 composition 变化**；语言与 shot 级保持一致。"
                    + "输出 JSON：{\"positive_prompt\":\"...\",\"negative_prompt\":\"...\","
                    + "\"keyframes\":[{\"positive_prompt\":\"...\",\"negative_prompt\":\"...\"}]}"
                    + "；keyframes 个数必须与输入帧**逐个对应**、顺序不变；`positive_prompt` 仍填**结束帧**作为单帧兼容值"
                    + "（与 keyframes 最后一帧一致）。";
            StringBuilder fb = new StringBuilder("\n\n本镜运镜关键帧（请逐帧重写，共 " + frames.size() + " 帧）：");
            for (int i = 0; i < frames.size(); i++) {
                RewriteFrame f = frames.get(i);
                fb.append("\n【第 ").append(i + 1).append(" 帧】")
                  .append(f.label() == null || f.label().isBlank() ? "" : "" + f.label())
                  .append(f.composition() == null || f.composition().isBlank() ? "" : "｜构图：" + f.composition())
                  .append("\n  原正词：").append(f.positivePrompt() == null ? "" : f.positivePrompt())
                  .append("\n  原负词：").append(f.negativePrompt() == null ? "" : f.negativePrompt());
            }
            user = user + fb + "\n\n请输出 **" + outSpec + "** 的 JSON（含 keyframes 数组，" + frames.size() + " 个元素）。";
        }
        // ★ 2026-09-22（用户要求「AI 生成提示词 / AI 更新提示词 增加使用模板」）：
        //   勾选时把**官方口径模板**随请求带给 LLM（模板文件见 prompts/prompt_template_*.md）。
        //   默认开：useTemplate == null 也当作 true（旧客户端/未传字段时不下发变化）。
        if (useTemplate == null || useTemplate) {
            String tpl = officialTemplateBlock(templateScope, frames);
            if (!tpl.isEmpty()) {
                system = system + tpl;
            }
            log.info("rewrite-prompt：使用模板={}（scope={}、frames={}）", !tpl.isEmpty(), templateScope,
                    frames == null ? 0 : frames.size());
        } else {
            log.info("rewrite-prompt：用户取消勾选「使用模板」，本次不带官方模板");
        }
        LlmRequest req = new LlmRequest(system, user, "rewrite", rawText, "image", "16:9", null, null);
        try {
            String raw = llm.generateJson(req);
            JsonNode n = mapper.readTree(raw);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("positive_prompt", n.path("positive_prompt").asText(""));
            out.put("negative_prompt", n.path("negative_prompt").asText(""));
            if (multi) {
                java.util.List<java.util.Map<String, String>> kfs = new java.util.ArrayList<>();
                JsonNode arr = n.path("keyframes");
                for (int i = 0; i < frames.size(); i++) {
                    JsonNode k = arr.isArray() && i < arr.size() ? arr.get(i) : null;
                    java.util.Map<String, String> one = new java.util.LinkedHashMap<>();
                    String kp = k == null ? "" : k.path("positive_prompt").asText("");
                    String kn = k == null ? "" : k.path("negative_prompt").asText("");
                    // 模型漏帧/少给 → 宁可回退到该帧原词，也不要把空词写回方案（空词=出图无约束）
                    one.put("positive_prompt", kp.isBlank() ? frames.get(i).positivePrompt() : kp);
                    one.put("negative_prompt", kn.isBlank() ? n.path("negative_prompt").asText("") : kn);
                    kfs.add(one);
                }
                out.put("keyframes", kfs);
                log.info("rewrite: 逐帧重写 {} 帧（lang={}，模型返回 {} 帧）", frames.size(), outSpec,
                        arr.isArray() ? arr.size() : 0);
            }
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
        // ★ P14（2026-09-16）：主体可能**只有定妆图没有素材图**（本项目就是），
        //   只扫 referenceAssets 会漏掉它们 → 导演既不知道有哪些主体，也拿不到人物档案。
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> planSubs =
                studio.weaveora.director.plan.PlanSubjects.parse(prevPlan);
        for (studio.weaveora.director.plan.PlanSubjects.Subject s : planSubs) {
            if (!s.name().isBlank()) {
                refSubjects.add(s.name());
            }
        }
        // ★ P14：设定年代 + 人物档案（用户要求：项目要交代剧情时间/年代，主体要有年龄/性别/身高/体态/性格）
        String settingBlock = settingAndSubjectBlock(prevPlan);
        if (!settingBlock.isEmpty()) {
            sb.append("\n\n").append(settingBlock)
                    .append("\n【硬规则·主体属性】凡镜中出现上述主体，positive_prompt 里对该主体的描写")
                    .append("**必须与其性别/年龄段/体态/外貌一致**：")
                    .append("尤其**不得把男性写成女性（或反之）**（man/woman、he/she、少年/少女 这类用词必须对得上）；")
                    .append("不得写与年龄不符的称谓（少年不得写成老者/孩童）；服饰发式必须与年代和身份相符。")
                    .append("未列出属性的主体按剧情自行判断，但同一主体在全片必须保持一致。");
        }
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
                    .append("禁止用 a man / the woman 这类泛称替代。")
                    .append("多主体同框时必须逐个点名并写清相互关系与动作互动（例：Baoyu and Keqing facing each other, Baoyu speaking while Keqing listens）。")
                    // ★ 2026-09-16 修正（用户实测第 4 镜反复“位置错位”）：**禁止在文案里猜画面方位**。
                    //   原因：用户在「位置总控」里设的区域框会由系统作为**最终位置**下发；
                    //   文案里再写一套 left/right/foreground 极易与框矛盾（实测第 4 镜：文案“居后景追来/在宝玉右侧”
                    //   而框是 x=0.65 / x=0.06，直接相反）→ 模型两头听，左右与前后乱。
                    .append("【不要写画面方位】禁止写 on the left / on the right / in the center / foreground / background / left of him")
                    .append("这类**画面坐标**描述 —— 主体在画面里的位置与大小由用户在「位置总控」里设的区域框统一由系统下发；")
                    .append("你在文案里猜的方位若与之不一致，就会变成位置错位。前/后景只能写叙事必要的逻辑关系（如 she runs after them from behind），不要写画面左右。")
                    .append("系统按参考图顺序把主体映射为 Picture 1 / Picture 2 …（等同旧口径 image1 / image2：“").append(map)
                    .append("”）；需要时可在 positive_prompt 里显式写 Baoyu (Picture 1) 加固对应关系。");
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

    /**
     * ★ P14（2026-09-16 用户要求）：「设定年代 + 剧情主体人物档案」提示词块。
     *
     * <p>为什么要单独抽出来：**导演生成**（buildUserPrompt）与**单镜重写**（rewritePrompt）
     * 必须拿到**同一份**口径，否则会出现「导演写的是少年男性、重写一次变成女子」——
     * 用户实测的「宝玉被当女性」就是这么来的（模型只能从名字自己猜性别）。
     *
     * @return 空串 = 方案里既没年代也没主体
     */
    static String settingAndSubjectBlock(JsonNode plan) {
        if (plan == null || plan.isMissingNode() || !plan.isObject()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String era = plan.path("setting").path("era").asText("").trim();
        String notes = plan.path("setting").path("notes").asText("").trim();
        if (!era.isEmpty() || !notes.isEmpty()) {
            sb.append("【设定年代/世界观（硬约束）】");
            if (!era.isEmpty()) {
                sb.append(era);
            }
            if (!notes.isEmpty()) {
                sb.append(era.isEmpty() ? "" : "；").append(notes);
            }
            sb.append("。全片所有镜头的人物造型/服饰/发式/道具/建筑/环境都必须符合该年代，")
                    .append("禁写该年代不存在的元素（如现代服装、手机、电线、现代建筑、现代交通工具）；称谓与身份也要符合年代。");
        }
        java.util.List<studio.weaveora.director.plan.PlanSubjects.Subject> subs =
                studio.weaveora.director.plan.PlanSubjects.parse(plan);
        if (!subs.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【剧情主体设定（必须遵守）】");
            int i = 1;
            for (studio.weaveora.director.plan.PlanSubjects.Subject s : subs) {
                String d = s.traitsOrEmpty().describe(false);
                sb.append("\n  ").append(i++).append(". ").append(s.name())
                        .append("（kind=").append(s.kind()).append("）")
                        .append(d.isEmpty() ? "——未填属性（请按剧情自行判断，全片保持一致）" : "：" + d);
            }
        }
        return sb.toString();
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
        // ★ P14：把设定年代与人物档案也带进摘要 —— 不然「再导演一版」会把这些设定丢掉
        String block = settingAndSubjectBlock(plan);
        if (!block.isEmpty()) {
            s.append(block).append('\n');
        }
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
                r.id().equals(approvedId), r.schemaJson(), shotViews, r.createdAt(),
                SceneSwitchNotices.of(r.schemaJson()));
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

    /**
     * ★ P14（2026-09-16）：新一版继承「剧情主体」与「设定年代」。
     *
     * <p>为什么必须单独做一步（而不是靠 LLM 自觉）：
     * <ul>
     *   <li>{@code subjects[]} 里装的是**用户拥有的资产**（定妆照 id、素材图 refs、别名、勾选、人物档案），
     *       LLM 根本产不出这些；而它在 user 消息里看到过主体清单，很可能“顺手”输出一份只有名字的 subjects
     *       → 一旦落库，用户配好的定妆照/属性就被清空（等价于“一致性突然全崩”）。</li>
     *   <li>{@code setting}（年代/世界观）虽由 LLM 产出，但用户可能手改过；LLM 漏给时不能把用户的设定丢了。</li>
     * </ul>
     * 规则：上一版有 subjects 就以上一版为准（丢弃 LLM 的）；setting 只在 LLM 没给时继承。
     */
    static void mergePrevSubjectsAndSetting(JsonNode plan, JsonNode prev) {
        if (!(plan instanceof ObjectNode po) || prev == null || !prev.isObject()) {
            return;
        }
        JsonNode prevSubs = prev.get("subjects");
        if (prevSubs != null && prevSubs.isArray() && !prevSubs.isEmpty()) {
            if (po.has("subjects")) {
                System.out.println("[DirectorService] 丢弃 LLM 产出的 subjects（主体是用户数据，以上一版为准）");
            }
            po.set("subjects", prevSubs.deepCopy());
            JsonNode prevRefs = prev.get("referenceAssets");
            if (prevRefs != null && prevRefs.isArray()) {
                po.set("referenceAssets", prevRefs.deepCopy());
            }
        }
        JsonNode prevSetting = prev.get("setting");
        if (prevSetting != null && prevSetting.isObject() && !po.has("setting")) {
            po.set("setting", prevSetting.deepCopy());
        }
    }

    /**
     * P14 补丁（2026-09-16 夜）：**保存路径**也要保护 {@code setting}。
     *
     * <p>为什么 LLM 那条路不够：{@code setting} 是 P14 才新增的顶层键，而浏览器里
     * **已经打开的旧页面**（草稿里没有这个键）一保存就会整份 plan 回写 →
     * {@code setting} 键连同 era/notes 一起**静默消失**。实测：rev52 已有
     * {@code setting}（21:05 写入），用户 21:08 / 21:29 / 21:46 从旧草稿连存三版
     * （rev53/54/55），后两版都没了这个键 —— 那批任务的出图正词里就没有【设定年代】，
     * 而“年代”正是 P14 要解决的问题。
     *
     * <p>规则（**逐字段补空**，与 {@code subjects} 的 {@code subjects/meta} 同一套心理模型）：
     * <ul>
     *   <li>提交里 <b>没有</b> {@code setting} 键，或这字段是空串，而上一版有值 → 继承上一版的；</li>
     *   <li>提交里写了非空值（哪怕是改成另一个年代）→ 一律以提交为准；</li>
     *   <li>两版都空 → 不写空对象（保持方案干净）。</li>
     * </ul>
     * 代价说明：{@code era}/{@code notes} 一旦写过，就**不能再靠“清空”删掉**（要改就改成非空的值）。
     * 这是故意的 —— {@code setting.era} 在 §7.5.2 里是**必填语义**（前端未填会红字提醒），
     * 而“被旧草稿洗掉”是真实且频发的损失；宁可把这两种情形都当成“保留原值”。
     *
     * @return true 表示发生了继承/补空（调用方打日志用）
     */
    static boolean inheritSettingIfAbsent(ObjectNode incoming, JsonNode prev) {
        if (incoming == null) {
            return false;
        }
        ObjectNode own = incoming.has("setting") && incoming.get("setting").isObject()
                ? (ObjectNode) incoming.get("setting") : null;
        JsonNode prevSetting = prev == null ? null : prev.get("setting");
        boolean prevUsable = prevSetting != null && prevSetting.isObject() && !allBlankSetting(prevSetting);
        if (!prevUsable) {
            if (own != null && allBlankSetting(own)) {
                incoming.remove("setting");     // 上一版没值、提交也是空 → 不保留空壳
            }
            return false;
        }
        ObjectNode cur = own != null ? own : incoming.putObject("setting");
        boolean changed = false;
        for (String k : new String[]{"era", "notes"}) {
            String mine = cur.path(k).asText("").trim();
            String old = prevSetting.path(k).asText("").trim();
            if (mine.isEmpty() && !old.isEmpty()) {
                cur.put(k, old);
                changed = true;
            }
        }
        if (!changed && allBlankSetting(cur)) {
            incoming.remove("setting");     // 两边都空就不留空对象
        }
        return changed;
    }

    /** {@code setting} 是否“等于没写”（没键 / 没值 / 值全是空白）。 */
    private static boolean allBlankSetting(JsonNode setting) {
        for (String k : new String[]{"era", "notes"}) {
            if (!setting.path(k).asText("").trim().isEmpty()) {
                return false;
            }
        }
        return true;
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
