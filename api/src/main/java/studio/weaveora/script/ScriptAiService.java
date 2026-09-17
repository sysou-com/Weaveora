package studio.weaveora.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;
import studio.weaveora.script.api.AiCondensedResult;
import studio.weaveora.script.api.AiFieldRequest;
import studio.weaveora.script.api.AiFieldResult;
import studio.weaveora.script.api.AiGuideResult;
import studio.weaveora.script.api.AiNextEpisodeRequest;
import studio.weaveora.script.api.AiNextEpisodeResult;
import studio.weaveora.script.api.ScriptConflict;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptEpisode;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本 AI 能力（全部走 {@link DirectorLlm} 网关；未配置 LLM 时 source=stub，返回可编辑的离线示例）。
 *
 * <p>职责边界：本类**只产出值**，不写库。落库由 {@code ScriptService} 在用户确认（Q3 diff / Q4 确认）后执行——
 * 呼应项目铁律「不要让 LLM 拥有用户数据」。
 */
@Service
public class ScriptAiService {

    private static final Logger log = LoggerFactory.getLogger(ScriptAiService.class);

    private final DirectorLlm llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScriptAiService(DirectorLlm llm) {
        this.llm = llm;
    }

    /** 一集的草稿（尚未落库）。 */
    public record EpisodeDraft(int episodeNo, String title, String summary, String content) {
    }

    public String source() {
        return llm.source();
    }

    private boolean stub() {
        return "stub".equals(llm.source());
    }

    // ------------------------------------------------------------ 字段生成 / 更新

    /**
     * 单字段生成/更新。
     *
     * @param stored 【B】已持久化的提纲：若段数与本次目标一致且用户未要求刷新，则**直接复用**
     *               （省一次调用，也避免每次重写得结构不一致）
     */
    public AiFieldResult generateField(Script script, List<ScriptEpisode> episodes, ScriptField field,
                                       AiFieldRequest req, ScriptPrompts.Outline stored) {
        String current = req.currentValue() != null && !req.currentValue().isBlank()
                ? req.currentValue() : currentValueOf(script, field);
        if (stub()) {
            String value = stubField(script, field);
            return new AiFieldResult(field.key(), value,
                    "离线示例（未接 LLM）：请配置 WEAVEORA_LLM_* 后重新生成", !value.equals(current), "stub",
                    List.of());
        }
        // 分段续写：单次输出被 max_tokens 截断（实测 422），按用户设定的目标字数分段拼满
        int target = ScriptPrompts.clampTarget(req.targetChars() == null ? 0 : req.targetChars());
        ScriptPrompts.PassPlan plan = ScriptPrompts.planFor(target);
        logTrim("字段「" + field.label() + "」", script, episodes, field.key(), false);
        // 【B】结构先行：先要一份与段数 1:1 的提纲；已存且在段数一致时直接复用
        ScriptPrompts.Outline outline;
        if (ScriptPrompts.canReuseOutline(stored, plan, req.refresh())) {
            outline = stored;
            log.info("字段「{}」复用已存提纲（{} 段）", field.label(), stored.segments().size());
        } else {
            outline = outlineFor(
                    ScriptPrompts.outlineSystem(),
                    ScriptPrompts.outlineUserForField(script, episodes, field, req.hint(), target,
                            req.fromContent(), current),
                    script.title(), "字段「" + field.label() + "」提纲");
        }
        StringBuilder acc = new StringBuilder();
        String system = ScriptPrompts.fieldSystem();
        String note = "";
        // 允许比计划多写 1 段补齐（模型每段可能少写一点）
        int maxPass = Math.min(ScriptPrompts.MAX_PASSES, plan.passes() + 1);
        for (int pass = 1; pass <= maxPass; pass++) {
            String user = ScriptPrompts.fieldUser(script, episodes, field, req.hint(), current,
                    req.fromContent(), pass, acc.toString(), target, outline);
            JsonNode node;
            LlmJson res;
            try {
                res = callJson(system, user, script.title(),
                        "字段「" + field.label() + "」第 " + pass + " 段");
            } catch (RuntimeException e) {
                // 韧性：已有内容就保留，不要让第 2/3 段失败把前文一起丢掉
                if (acc.length() > 0) {
                    log.warn("字段「{}」第 {} 段失败，保留已生成 {} 字：{}",
                            field.label(), pass, acc.length(), e.getMessage());
                    break;
                }
                throw e;
            }
            node = res.node();
            String part = pickText(res, "value", "字段「" + field.label() + "」第 " + pass + " 段");
            if (part.isEmpty()) {
                LlmJson again = retryBlank(system, user, script.title(),
                        "字段「" + field.label() + "」第 " + pass + " 段");
                if (again == null) {
                    break;
                }
                node = again.node();
                part = pickText(again, "value", "字段「" + field.label() + "」第 " + pass + " 段（重试）");
                if (part.isEmpty()) {
                    break;
                }
            }
            if (pass == 1) {
                note = node.path("note").asText("");
            }
            if (acc.length() > 0) acc.append("\n\n");
            acc.append(part);
            if (acc.length() >= target) {
                break;
            }
        }
        String value = acc.toString().trim();
        if (value.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 未返回有效内容，请重试");
        }
        if (value.length() < target) {
            // 不静默：长度不够就如实告诉用户（可重试或手写补写）
            note = (note == null || note.isBlank() ? "" : note + "；")
                    + "AI 本次产出 " + value.length() + " 字（目标 " + target
                    + " 字），可重试或手写补全";
        } else if (value.length() > target * 1.5) {
            // 写超 → 自动压缩一次（保留内容与结尾）
            String origin = String.valueOf(value.length());
            value = compressToTarget(system, script, "字段「" + field.label() + "」", value, target,
                    "字段「" + field.label() + "」");
            note = (note == null || note.isBlank() ? "" : note + "；")
                    + "AI 写超了（原 " + origin + " 字 > 目标 " + target + " 字），已自动压缩到 "
                    + value.length() + " 字";
        }
        boolean changed = !value.equals(current.trim());
        return new AiFieldResult(field.key(), value, note, changed, llm.source(),
                outline.isEmpty() ? List.of() : outline.segments());
    }

    // ------------------------------------------------------------ 下一集

    public AiNextEpisodeResult nextEpisode(Script script, List<ScriptEpisode> episodes,
                                           AiNextEpisodeRequest req) {
        int nextNo = req.episodeNo() != null && req.episodeNo() > 0
                ? req.episodeNo()
                : episodes.stream().mapToInt(ScriptEpisode::episodeNo).max().orElse(0) + 1;
        if (!req.polishedOrDefault()) {
            // 「不需要 AI 润色」→ 返回空壳，前端给空编辑器（用户原话）
            return new AiNextEpisodeResult(nextNo, "第 " + nextNo + " 集", "", "", "manual", null, null);
        }
        String hint = req.titleHint();
        if (stub()) {
            String title = (hint == null || hint.isBlank()) ? "第 " + nextNo + " 集" : hint.trim();
            return new AiNextEpisodeResult(nextNo, title,
                    "离线示例：未接 LLM。配置 WEAVEORA_LLM_* 后将依据「精简的故事」自动生成第 " + nextNo + " 集。",
                    "（离线示例正文 · 未接 LLM）\n\n【场景】…\n\n【人物】…\n\n【本集冲突】…\n\n【正文】"
                            + "请在配置 LLM 后重新生成，或直接在此手写第 " + nextNo + " 集。",
                    "stub", null, null);
        }
        String system = ScriptPrompts.episodeSystem();
        // 分段续写：一集拆成多段（起 / 承转 / 合与钩子），目标字数由用户设定，避开 max_tokens 截断
        int epTarget = ScriptPrompts.clampTarget(req.targetChars() == null ? 0 : req.targetChars());
        ScriptPrompts.PassPlan epPlan = ScriptPrompts.planFor(epTarget);
        int epMaxPass = Math.min(ScriptPrompts.MAX_PASSES, epPlan.passes() + 1);
        logTrim("第 " + nextNo + " 集", script, episodes, null, true);
        // 【B】先要本集的**节拍提纲**（起/承转/合），每段照一条写
        ScriptPrompts.Outline outline = outlineFor(
                ScriptPrompts.outlineSystem(),
                ScriptPrompts.outlineUserForEpisode(script, episodes, nextNo, hint, req.instruction(), epTarget),
                script.title(), "第 " + nextNo + " 集提纲");
        String title = "";
        String summary = "";
        String note = "";
        StringBuilder acc = new StringBuilder();
        for (int pass = 1; pass <= epMaxPass; pass++) {
            String user = ScriptPrompts.episodeUser(script, episodes, nextNo, hint, req.instruction(),
                    pass, acc.toString(), outline, epTarget);
            JsonNode node;
            LlmJson res;
            try {
                res = callJson(system, user, script.title(), "第 " + nextNo + " 集第 " + pass + " 段");
            } catch (RuntimeException e) {
                // 韧性：第 1 段必须成功（否则没有本集）；后续段失败则保留已写部分
                if (acc.length() > 0) {
                    note = "第 " + pass + " 段生成失败，已保留前 " + acc.length() + " 字（可重试补全）";
                    log.warn("第 {} 集第 {} 段失败，保留 {} 字：{}", nextNo, pass, acc.length(), e.getMessage());
                    break;
                }
                throw e;
            }
            node = res.node();
            String part = pickText(res, "content", "第 " + nextNo + " 集第 " + pass + " 段");
            if (part.isEmpty()) {
                // 实测：模型偶尔会把某一段留空（或只给大纲）—— 不静默丢弃，明确要求「必须给正文」再试一次
                LlmJson again = retryBlank(system, user, script.title(),
                        "第 " + nextNo + " 集第 " + pass + " 段");
                if (again == null) {
                    break;
                }
                node = again.node();
                part = pickText(again, "content", "第 " + nextNo + " 集第 " + pass + " 段（重试）");
                if (part.isEmpty()) {
                    break;
                }
            }
            if (pass == 1) {
                title = node.path("title").asText("").trim();
            }
            if (summary.isBlank()) {
                // 模型有时只在某一段给了摘要（或写在子对象里）→ 任一段有就用，不限定第 1 段
                summary = node.path("summary").asText("").trim();
            }
            if (acc.length() > 0) acc.append("\n\n");
            acc.append(part);
            if (acc.length() >= ScriptPrompts.EPISODE_MIN_CHARS) {
                break;
            }
        }
        String content = acc.toString().trim();
        if (content.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 未返回有效正文，请重试");
        }
        if (title.isBlank()) {
            title = (hint == null || hint.isBlank()) ? "第 " + nextNo + " 集" : hint.trim();
        }
        if (summary.isBlank()) {
            // 兜底：没有摘要就用正文开头（「精简的故事」需要每集有据可依）
            summary = ScriptPrompts.clip(content, 120);
        }
        if (content.length() < epTarget) {
            String warn = "AI 本次产出 " + content.length() + " 字（目标 " + epTarget
                    + " 字），可重试或手写补全";
            note = note.isBlank() ? warn : note + "；" + warn;
        } else if (content.length() > epTarget * 1.5) {
            String origin = String.valueOf(content.length());
            content = compressToTarget(system, script, "第 " + nextNo + " 集正文", content, epTarget,
                    "第 " + nextNo + " 集");
            String warn = "AI 写超了（原 " + origin + " 字 > 目标 " + epTarget + " 字），已自动压缩到 "
                    + content.length() + " 字";
            note = note.isBlank() ? warn : note + "；" + warn;
        }
        return new AiNextEpisodeResult(nextNo, title, summary, content, llm.source(), note, outline.isEmpty() ? null : outline.segments());
    }

    // ------------------------------------------------------------ 精简故事 + 一致性检查

    /**
     * 刷新「精简的故事」并给出历史章节改动**提议**。
     *
     * <p>保存路径调用它：AI 失败不得阻断保存 → 内部兜底（保留旧故事、无冲突、source=error）。
     */
    public AiCondensedResult refreshCondensed(Script script, List<ScriptEpisode> episodes) {
        if (episodes.isEmpty()) {
            return new AiCondensedResult(script.condensedStory(), List.of(), List.of(), "empty");
        }
        if (stub()) {
            return new AiCondensedResult(script.condensedStory(), List.of(), List.of(), "stub");
        }
        try {
            String user = ScriptPrompts.condenseUser(script, episodes);
            JsonNode node = callJson(ScriptPrompts.condenseSystem(), user, script.title(), "精简故事").node();
            String story = node.path("condensedStory").asText("");
            List<String> beats = new ArrayList<>();
            for (JsonNode b : node.path("completedBeats")) {
                String v = b.asText("").trim();
                if (!v.isEmpty()) beats.add(v);
            }
            List<ScriptConflict> conflicts = parseConflicts(node.path("conflicts"));
            return new AiCondensedResult(story.isBlank() ? script.condensedStory() : story.trim(),
                    conflicts, beats, llm.source());
        } catch (RuntimeException e) {
            log.warn("精简故事刷新失败（不阻断保存）: {}", e.getMessage());
            return new AiCondensedResult(script.condensedStory(), List.of(), List.of(), "error");
        }
    }

    // ------------------------------------------------------------ AI 引导

    public AiGuideResult guide(Script script, List<ScriptEpisode> episodes) {
        if (stub()) {
            return new AiGuideResult("开端",
                    List.of("发展", "转折", "高潮", "结局"),
                    List.of("离线示例：配置 LLM 后，这里会给出针对本剧的具体推进建议。",
                            "当前已有 " + episodes.size() + " 集，可继续「开始下一集」。"),
                    4, "stub");
        }
        JsonNode node = callJson(ScriptPrompts.guideSystem(),
                ScriptPrompts.guideUser(script, episodes), script.title(), "AI 引导").node();
        List<String> missing = new ArrayList<>();
        for (JsonNode n : node.path("missingBeats")) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) missing.add(v);
        }
        List<String> suggestions = new ArrayList<>();
        for (JsonNode n : node.path("suggestions")) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) suggestions.add(v);
        }
        return new AiGuideResult(node.path("stage").asText(""), missing, suggestions,
                node.path("estimatedRemainingEpisodes").asInt(0), llm.source());
    }

    // ------------------------------------------------------------ 应用一致性改动（Q4 确认后）

    /** 一次重写多集 → episodeNo → 草稿。 */
    public Map<Integer, EpisodeDraft> rewriteEpisodes(Script script, List<ScriptEpisode> episodes,
                                                      List<ScriptConflict> items) {
        Map<Integer, EpisodeDraft> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        if (stub()) {
            return out;
        }
        JsonNode node = callJson(null, ScriptPrompts.rewriteEpisodesUser(script, items, episodes),
                script.title(), "一致性改写").node();
        for (JsonNode n : node.path("episodes")) {
            int no = n.path("episodeNo").asInt(0);
            if (no <= 0) continue;
            out.put(no, new EpisodeDraft(no, n.path("title").asText(""), n.path("summary").asText(""),
                    n.path("content").asText("")));
        }
        return out;
    }

    // ------------------------------------------------------------ 转成项目：整集 → brief

    public String condenseBrief(Script script, ScriptEpisode episode) {
        if (stub()) {
            return ScriptPrompts.clip(script.condensedStory().isBlank()
                    ? episode.content() : script.condensedStory(), ScriptPrompts.BRIEF_MAX_CHARS);
        }
        try {
            JsonNode node = callJson(null, ScriptPrompts.briefUser(script, episode),
                    script.title(), "brief 精简").node();
            String brief = node.path("brief").asText("").trim();
            return brief.isBlank() ? ScriptPrompts.clip(episode.content(), ScriptPrompts.BRIEF_MAX_CHARS) : brief;
        } catch (RuntimeException e) {
            log.warn("brief 精简失败，回退原文截断: {}", e.getMessage());
            return ScriptPrompts.clip(episode.content(), ScriptPrompts.BRIEF_MAX_CHARS);
        }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 调 LLM 并解析 JSON。
     *
     * @param system null = 用 {@link ScriptPrompts#episodeSystem()} 之外的默认（这里传 null 时用通用收尾指令）
     */
    /** 一次 LLM 调用的结果：原文 + 解析后的对象（原文用于字段名异常时打日志）。 */
    private record LlmJson(String raw, JsonNode node) {
    }

    private LlmJson callJson(String system, String user, String title, String what) {
        String sys = system != null ? system : GENERIC_SYSTEM;
        try {
            String raw = llm.generateJson(new LlmRequest(sys, user, title, "", "video", "16:9", null, null));
            return new LlmJson(raw, parse(raw, what));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("剧本 AI（{}）调用失败: {}", what, e.getMessage());
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED,
                    "AI 服务暂时不可用（" + what + "），请稍后重试或先手写保存");
        }
    }

    /**
     * 取正文字段：主键名不同就回退到常见别名；都找不到就**打日志带原文开头**（不静默丢内容）。
     *
     * <p>2026-09-17 实测：模型有时会把正文放在 {@code text}/{@code body}/{@code value} 下，
     * 或把标题/摘要/正文包成子对象；早期实现直接取 {@code content} 得到空串 → 抛 BizException 且**无任何日志**，
     * 排障时只能靠猜（违反「不要静默丢东西」纪律）。
     */
    private String pickText(LlmJson res, String primary, String what) {
        JsonNode node = res.node();
        String v = node.path(primary).asText("");
        if (!v.isBlank()) {
            return v.trim();
        }
        for (String alt : List.of("content", "value", "text", "body", "paragraph", "正文", "内容")) {
            if (alt.equals(primary)) continue;
            String a = node.path(alt).asText("");
            if (!a.isBlank()) {
                log.warn("剧本 AI（{}）未给出字段 {}，回退使用 {}", what, primary, alt);
                return a.trim();
            }
        }
        // 可能是嵌套（如 data.content）或数组包装
        for (JsonNode child : node) {
            if (child.isObject()) {
                String a = pickText(new LlmJson(res.raw(), child), primary, what + "/嵌套");
                if (!a.isBlank()) {
                    return a;
                }
            }
        }
        log.warn("剧本 AI（{}）里找不到正文字段（期望 {}）。顶层字段={} 原文开头={}",
                what, primary, keysOf(node), head(res.raw(), 320));
        return "";
    }

    /**
     * 段落为空时的补救：在指令尾部把「必须给正文」写成硬要求再试一次。
     *
     * <p>实测（2026-09-17）：分集生成时模型偶尔会把某一段留空或只给大纲，
     * 早期实现直接 {@code break} 放弃 → 最终只有 1574 字（远低于 4000 的最低要求）。
     */
    private LlmJson retryBlank(String system, String user, String title, String what) {
        try {
            String forced = user + "\n【必须】本段必须给出正文，不得留空、不得只给说明或大纲；"
                    + "请按上面的字数要求接着写下去。";
            return callJson(system, forced, title, what + "（空正文重试）");
        } catch (RuntimeException e) {
            log.warn("{} 空正文重试仍失败：{}", what, e.getMessage());
            return null;
        }
    }

    /**
     * 【B】取分段写作提纲。
     *
     * <p>**失败不影响生成**：拿不到提纲就退化成「按段数自行推进」（不阻断主流程），只记一条日志。
     */
    private ScriptPrompts.Outline outlineFor(String system, String user, String title, String what) {
        try {
            LlmJson res = callJson(system, user, title, what);
            ScriptPrompts.Outline outline = ScriptPrompts.parseOutline(res.node());
            if (outline.isEmpty()) {
                log.warn("{} 未解析出提纲，按段数自行推进。原文开头={}", what, head(res.raw(), 200));
            }
            return outline;
        } catch (RuntimeException e) {
            log.warn("{} 生成失败（不阻断正文生成）：{}", what, e.getMessage());
            return ScriptPrompts.Outline.empty();
        }
    }

    /** 【A】把上下文压缩/省略情况记入日志（不静默丢东西）。 */
    private void logTrim(String what, Script script, List<ScriptEpisode> episodes,
                         String targetKey, boolean withBodies) {
        if (log.isDebugEnabled()) {
            return;   // 生产（INFO）只在真的裁剪时记一条，避免每次刷两条日志
        }
        try {
            ScriptPrompts.Block b = ScriptPrompts.context(script, episodes, targetKey, withBodies);
            if (b.trimmed()) {
                log.info("{} 上下文已按预算裁剪（{} 字上限）：{}",
                        what, ScriptPrompts.CONTEXT_BUDGET_CHARS, String.join("；", b.notes()));
            }
        } catch (RuntimeException ignore) {
            // 纯日志用途，绝不影响主流程
        }
    }

    /**
     * 写超时的**压缩重写**（一次调用）：保留人物/冲突/关键事件/结尾钩子，把长文压回目标以内。
     *
     * <p>为什么不直接裁字：叙事文本硬截会把结尾的钩子剪掉（反而更糟）。
     * 实测模型对「不超过 N 字」的遵循度有限（目标 1500 字会写到 2800+），所以只在这时多花一次调用。
     * **压缩失败就保留原稿**（宁可长，不丢内容），并把实情写进 note。
     */
    private String compressToTarget(String system, Script script, String task, String draft,
                                    int target, String what) {
        String user = "下面这段【" + task + "】约 " + draft.length() + " 字，超出了用户设定的 " + target
                + " 字目标。请**在不丢关键内容与结尾的前提下压缩**到 " + target + " 字以内（约 "
                + Math.max(1, (int) (target * 0.95)) + " 字）：合并重复、删去修饰与次要描写，"
                + "但必须保留全部人物、核心冲突、关键事件与结尾钩子。\n"
                + "只输出 JSON：{\"value\":\"<压缩后的完整内容>\"}\n\n【待压缩内容】\n" + draft;
        try {
            LlmJson res = callJson(system, user, script.title(), what + "压缩");
            String out = pickText(res, "value", what + "压缩");
            if (!out.isBlank() && out.length() < draft.length()) {
                log.info("{} 写超（{} 字 > 目标 {} 字），已压缩到 {} 字", what, draft.length(), target, out.length());
                return out;
            }
        } catch (RuntimeException e) {
            log.warn("{} 压缩失败，保留原稿 {} 字：{}", what, draft.length(), e.getMessage());
        }
        return draft;
    }

    private static String keysOf(JsonNode node) {
        List<String> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys.toString();
    }

    private static final String GENERIC_SYSTEM = """
            你是资深影视编剧与剧本医生。请严格按用户要求，只输出纯 JSON（不要解释、不要 Markdown 围栏）。
            """;

    private JsonNode parse(String raw) {
        return parse(raw, "");
    }

    /**
     * 容错解析：剥围栏、提首尾花括号、容忍数组包装（模型偶发返回 {@code [{...}]}）。
     *
     * <p>失败时**必须打日志带原文开头** —— 2026-09-17 实测：解析失败静默抛 BizException 时，
     * 日志里什么都看不到，只能靠猜（与「不要静默丢东西」纪律相悖）。
     */
    private JsonNode parse(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 返回为空");
        }
        String s = raw.trim();
        JsonNode node = tryRead(s);
        if (node == null) {
            int b = s.indexOf('{');
            int e = s.lastIndexOf('}');
            if (b >= 0 && e > b) {
                node = tryRead(s.substring(b, e + 1));
            }
        }
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                if (n.isObject()) {
                    node = n;
                    break;
                }
            }
        }
        if (node == null || !node.isObject()) {
            log.warn("剧本 AI（{}）返回不是 JSON 对象，原文开头: {}", what, head(raw, 240));
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED,
                    "AI 返回格式异常" + (what.isBlank() ? "" : "（" + what + "）") + "，请重试");
        }
        return node;
    }

    private JsonNode tryRead(String s) {
        try {
            JsonNode n = mapper.readTree(s);
            return n == null || n.isNull() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    private static String head(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static List<ScriptConflict> parseConflicts(JsonNode arr) {
        List<ScriptConflict> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            int no = n.path("episodeNo").asInt(0);
            String issue = n.path("issue").asText("").trim();
            String fix = n.path("fix").asText("").trim();
            if (no <= 0 || (issue.isEmpty() && fix.isEmpty())) continue;
            out.add(new ScriptConflict(no, n.path("title").asText("").trim(), issue, fix));
        }
        return out;
    }

    private static String currentValueOf(Script s, ScriptField field) {
        return switch (field) {
            case CHARACTERS -> s.characters();
            case STORY -> s.story();
            case CONFLICT -> s.conflict();
            case PLOT_STRUCTURE -> s.plotStructure();
            case LANGUAGE -> s.language();
            case STAGE_DIRECTIONS -> s.stageDirections();
        };
    }

    // ------------------------------------------------------------ 离线示例（stub）

    private String stubField(Script script, ScriptField field) {
        return """
                （离线示例 · 未接 LLM —— 依据《%s》/ %s 生成的占位内容，请在配置 WEAVEORA_LLM_* 后点「AI 生成」或「AI 更新」获得正式内容。）

                【%s】
                1. 核心定位：围绕《%s》这一类型（%s）展开，确保与后续集数连贯。
                2. 关键要点：…
                3. 与其它要素的接口：…
                4. 可执行清单：…

                （如需正式内容：在服务器环境变量配置 WEAVEORA_LLM_BASE_URL / WEAVEORA_LLM_API_KEY / WEAVEORA_LLM_MODEL。）
                """.formatted(script.title(), script.genre(), field.label(), script.title(), script.genre());
    }
}
