package studio.weaveora.project.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BriefResponse(
        UUID id,
        UUID projectId,
        String rawText,
        String mode,
        JsonNode constraints,
        OffsetDateTime createdAt
) {
}
