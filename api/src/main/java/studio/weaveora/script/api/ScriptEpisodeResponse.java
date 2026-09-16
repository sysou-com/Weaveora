package studio.weaveora.script.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 一集（GET /api/v1/scripts/{id}/episodes）。 */
public record ScriptEpisodeResponse(
        UUID id,
        int episodeNo,
        String title,
        String content,
        String summary,
        boolean aiPolished,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
