package studio.weaveora.project.api;

import java.util.List;

/** 分页卡片列表（每页默认 8）。 */
public record ProjectPage(
        List<ProjectCard> items,
        int page,
        int size,
        long total,
        boolean hasMore
) {
}
