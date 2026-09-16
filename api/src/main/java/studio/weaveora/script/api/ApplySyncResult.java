package studio.weaveora.script.api;

import java.util.List;

/** 应用一致性改动的结果：被改写的集 + 最新精简故事 + 变更记录。 */
public record ApplySyncResult(
        List<ScriptEpisodeResponse> episodes,
        String condensedStory,
        List<ScriptChangeResponse> changes,
        String source
) {
}
