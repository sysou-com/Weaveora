package studio.weaveora.script.api;

import java.util.List;

/** 剧本分页卡片列表（每页默认 8）。 */
public record ScriptPage(
        List<ScriptCard> items,
        int page,
        int size,
        long total,
        boolean hasMore
) {
}
