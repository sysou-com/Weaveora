package studio.weaveora.script.api;

import java.util.List;

/** 下一集草稿（尚未落库，用户在抽屉里改完再保存）。 */
public record AiNextEpisodeResult(
        int episodeNo,
        String title,
        String summary,
        String content,
        String source,
        /** 需要告知用户的提示（如某段生成失败/未达目标长度）；无则 null */
        String note,
        /** 【B】本集的节拍提纲（每段一行）；无则 null */
        List<String> outline
) {
}
