package studio.weaveora.script.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 剧本列表卡片（我的剧本 / 剧本精选 / 待审共用）。 */
public record ScriptCard(
        UUID id,
        String title,
        String genre,
        String status,
        String shareStatus,
        String ownerName,
        long episodeCount,
        long charCount,
        String excerpt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long likeCount,
        long favoriteCount,
        boolean liked,
        boolean favorited
) {
}
