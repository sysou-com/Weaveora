package studio.weaveora.script.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 剧本详情（GET/PATCH /api/v1/scripts/{id}）。 */
public record ScriptResponse(
        UUID id,
        UUID workspaceId,
        String title,
        String genre,
        String characters,
        String story,
        String conflict,
        String plotStructure,
        String language,
        String stageDirections,
        String condensedStory,
        String status,
        String shareStatus,
        long episodeCount,
        long charCount,
        /** 【B】各要素已持久化的分段提纲（key=ScriptField.key）；「AI 更新」可复用 */
        java.util.Map<String, java.util.List<String>> outlines,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
