package studio.weaveora.script.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/scripts/{id}/ai/next-episode —— 生成下一集。
 *
 * @param polished    true（默认）= AI 读「精简的故事」+ 要素后生成；false = 返回空壳（前端给空编辑器，Q4 原话）
 * @param targetChars 本集目标字数（弹窗里设；≤0/不传 = 默认 4000，**上限 8000**）——与要素同一套分段续写机制
 */
public record AiNextEpisodeRequest(
        Boolean polished,
        @Size(max = 200) String titleHint,
        @Size(max = 2000) String instruction,
        Integer episodeNo,
        @Min(0) @Max(8000) Integer targetChars
) {
    public boolean polishedOrDefault() {
        return polished == null || polished;
    }
}
