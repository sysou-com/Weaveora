package studio.weaveora.script.api;

import java.util.List;

/** AI 引导：剧本走到哪一步、还缺什么、下一步写什么。 */
public record AiGuideResult(
        String stage,
        List<String> missingBeats,
        List<String> suggestions,
        int estimatedRemainingEpisodes,
        String source
) {
}
