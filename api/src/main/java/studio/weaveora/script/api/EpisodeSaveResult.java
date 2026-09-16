package studio.weaveora.script.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 保存一集的结果：本集 + 最新精简故事 + 待确认的历史章节改动（Q4）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EpisodeSaveResult(
        ScriptEpisodeResponse episode,
        String condensedStory,
        List<ScriptConflict> conflicts,
        List<ScriptChangeResponse> changes,
        List<String> completedBeats,
        String source
) {
}
