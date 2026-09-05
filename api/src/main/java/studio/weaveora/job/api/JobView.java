package studio.weaveora.job.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Job 视图（对外）。 */
public record JobView(
        UUID id,
        UUID projectId,
        UUID revisionId,
        UUID shotId,
        String kind,
        String state,
        int progress,
        String stage,
        boolean cancelRequested,
        String errorCode,
        String errorMessage,
        UUID modelPresetId,
        JsonNode payload,
        OffsetDateTime createdAt
) {
}
