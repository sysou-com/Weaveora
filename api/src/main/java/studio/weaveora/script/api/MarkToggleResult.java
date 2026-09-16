package studio.weaveora.script.api;

/** 点赞/收藏切换结果（kind / 是否激活 / 最新计数）。 */
public record MarkToggleResult(String kind, boolean active, long count) {
}
