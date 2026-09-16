package studio.weaveora.script.api;

/** 下一集草稿（尚未落库，用户在抽屉里改完再保存）。 */
public record AiNextEpisodeResult(
        int episodeNo,
        String title,
        String summary,
        String content,
        String source
) {
}
