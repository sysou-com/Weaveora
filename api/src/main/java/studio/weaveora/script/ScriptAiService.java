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

    public AiFieldResult generateField(Script script, List<ScriptEpisode> episodes, ScriptField field,
                                       AiFieldRequest req) {
        String current = req.currentValue() != null && !req.currentValue().isBlank()
                ? req.currentValue() : currentValueOf(script, field);
        if (stub()) {
            String value = stubField(script, field);
            return new AiFieldResult(field.key(), value,
                    "离线示例（未接 LLM）：请配置 WEAVEORA_LLM_* 后重新生成", !value.equals(current), "stub");
        }
        String system = ScriptPrompts.fieldSystem();
        String user = ScriptPrompts.fieldUser(script, episodes, field, req.hint(), current, req.fromContent());
        JsonNode node = callJson(system, user, script.title(), "字段「" + field.label() + "」");
        String value = node.path("value").asText("");
        if (value.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 未返回有效内容，请重试");
        }
        String note = node.path("note").asText("");
        boolean changed = !value.trim().equals(current.trim());
        return new AiFieldResult(field.key(), value.trim(), note, changed, llm.source());
    }

    // ------------------------------------------------------------ 下一集

    public AiNextEpisodeResult nextEpisode(Script script, List<ScriptEpisode> episodes,
                                           AiNextEpisodeRequest req) {
        int nextNo = req.episodeNo() != null && req.episodeNo() > 0
                ? req.episodeNo()
                : episodes.stream().mapToInt(ScriptEpisode::episodeNo).max().orElse(0) + 1;
        if (!req.polishedOrDefault()) {
            // 「不需要 AI 润色」→ 返回空壳，前端给空编辑器（用户原话）
            return new AiNextEpisodeResult(nextNo, "第 " + nextNo + " 集", "", "", "manual");
        }
        String hint = req.titleHint();
        if (stub()) {
            String title = (hint == null || hint.isBlank()) ? "第 " + nextNo + " 集" : hint.trim();
            return new AiNextEpisodeResult(nextNo, title,
                    "离线示例：未接 LLM。配置 WEAVEORA_LLM_* 后将依据「精简的故事」自动生成第 " + nextNo + " 集。",
                    "（离线示例正文 · 未接 LLM）\n\n【场景】…\n\n【人物】…\n\n【本集冲突】…\n\n【正文】"
                            + "请在配置 LLM 后重新生成，或直接在此手写第 " + nextNo + " 集。",
                    "stub");
        }
        String system = ScriptPrompts.episodeSystem();
        String user = ScriptPrompts.episodeUser(script, episodes, nextNo, hint, req.instruction());
        JsonNode node = callJson(system, user, script.title(), "第 " + nextNo + " 集");
        String content = node.path("content").asText("");
        if (content.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 未返回有效正文，请重试");
        }
        String title = node.path("title").asText("");
        if (title.isBlank()) {
            title = (hint == null || hint.isBlank()) ? "第 " + nextNo + " 集" : hint.trim();
        }
        return new AiNextEpisodeResult(nextNo, title.trim(),
                node.path("summary").asText("").trim(), content.trim(), llm.source());
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
            JsonNode node = callJson(ScriptPrompts.condenseSystem(), user, script.title(), "精简故事");
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
                ScriptPrompts.guideUser(script, episodes), script.title(), "AI 引导");
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
                script.title(), "一致性改写");
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
                    script.title(), "brief 精简");
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
    private JsonNode callJson(String system, String user, String title, String what) {
        String sys = system != null ? system : GENERIC_SYSTEM;
        try {
            String raw = llm.generateJson(new LlmRequest(sys, user, title, "", "video", "16:9", null, null));
            return parse(raw);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("剧本 AI（{}）调用失败: {}", what, e.getMessage());
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED,
                    "AI 服务暂时不可用（" + what + "），请稍后重试或先手写保存");
        }
    }

    private static final String GENERIC_SYSTEM = """
            你是资深影视编剧与剧本医生。请严格按用户要求，只输出纯 JSON（不要解释、不要 Markdown 围栏）。
            """;

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 返回为空");
        }
        String s = raw.trim();
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 返回不是 JSON 对象");
        }
        try {
            return mapper.readTree(s.substring(b, e + 1));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED, "AI 返回 JSON 解析失败");
        }
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
