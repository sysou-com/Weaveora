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
                s.createdAt(), s.updatedAt());
    }

    public static ScriptEpisodeResponse toEpisode(ScriptEpisode e) {
        return new ScriptEpisodeResponse(
                e.id(), e.episodeNo(), e.title(), e.content(), e.summary(),
                e.aiPolished(), e.createdAt(), e.updatedAt());
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
