package studio.weaveora.script.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptChange;
import studio.weaveora.script.domain.ScriptEpisode;

import java.util.ArrayList;
import java.util.List;

/** 剧本领域对象 → API DTO。 */
public final class ScriptMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptMapper() {
    }

    public static ScriptResponse toResponse(Script s, long episodeCount, long charCount) {
        return new ScriptResponse(
                s.id(), s.workspaceId(), s.title(), s.genre(),
                s.characters(), s.story(), s.conflict(), s.plotStructure(),
                s.language(), s.stageDirections(), s.condensedStory(),
                s.status(), s.shareStatus(), episodeCount, charCount,
                outlineMap(s.outlines()),
                s.createdAt(), s.updatedAt());
    }

    public static ScriptEpisodeResponse toEpisode(ScriptEpisode e) {
        return toEpisode(e, null, null, null);
    }

    /** 带「集 → 项目」链接的一集（projectId 为 null = 这集还没转过项目）。 */
    public static ScriptEpisodeResponse toEpisode(ScriptEpisode e, java.util.UUID projectId,
                                                 String projectTitle, Integer projectRevisionNo) {
        return new ScriptEpisodeResponse(
                e.id(), e.episodeNo(), e.title(), e.content(), e.summary(),
                e.aiPolished(), outlineList(e.outline()), e.createdAt(), e.updatedAt(),
                projectId, projectTitle, projectRevisionNo);
    }

    /** JsonNode 数组 → List<String>（空/非数组 → 空列表）。 */
    public static List<String> outlineList(com.fasterxml.jackson.databind.JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (var item : n) {
            String v = item.asText("");
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    /** {key: [段…]} → Map<String,List<String>>（只保留非空数组）。 */
    public static java.util.Map<String, List<String>> outlineMap(com.fasterxml.jackson.databind.JsonNode n) {
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        if (n == null || !n.isObject()) return out;
        var it = n.fields();
        while (it.hasNext()) {
            var e = it.next();
            List<String> segs = outlineList(e.getValue());
            if (!segs.isEmpty()) out.put(e.getKey(), segs);
        }
        return out;
    }

    public static ScriptChangeResponse toChange(ScriptChange c) {
        return new ScriptChangeResponse(c.id(), c.kind(), c.episodeNo(),
                parseChanged(c), c.note(), c.actor(), c.createdAt());
    }

    private static List<ScriptChangeResponse.ChangedEpisode> parseChanged(ScriptChange c) {
        List<ScriptChangeResponse.ChangedEpisode> out = new ArrayList<>();
        if (c.changedEpisodes() == null || !c.changedEpisodes().isArray()) {
            return out;
        }
        for (var n : c.changedEpisodes()) {
            out.add(new ScriptChangeResponse.ChangedEpisode(
                    n.path("episodeNo").isNumber() ? n.path("episodeNo").asInt() : null,
                    n.path("title").asText(""),
                    n.path("what").asText("")));
        }
        return out;
    }

    /** 剧本正文总量（6 要素 + 全部集），用于卡片「约 N 字」。 */
    public static long charCount(Script s, List<ScriptEpisode> episodes) {
        long n = s.characters().length() + s.story().length() + s.conflict().length()
                + s.plotStructure().length() + s.language().length() + s.stageDirections().length();
        for (ScriptEpisode e : episodes) {
            n += e.content() == null ? 0 : e.content().length();
        }
        return n;
    }

    /** 文本卡摘要：优先「精简的故事」，其次「剧本故事」。 */
    public static String excerpt(Script s, int max) {
        String src = s.condensedStory() == null || s.condensedStory().isBlank()
                ? s.story() : s.condensedStory();
        if (src == null || src.isBlank()) return "";
        String t = src.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public static com.fasterxml.jackson.databind.JsonNode toJson(Object value) {
        return MAPPER.valueToTree(value);
    }
}
