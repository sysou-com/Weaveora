package studio.weaveora.script.api;

import java.util.List;

/**
 * 「精简的故事」刷新 + 一致性检查结果。
 *
 * <p>{@code conflicts} 是**提议**：Q4 要求前端弹确认后再调 apply-sync 才真正改历史章节。
 */
public record AiCondensedResult(
        String condensedStory,
        List<ScriptConflict> conflicts,
        List<String> completedBeats,
        String source
) {
}
