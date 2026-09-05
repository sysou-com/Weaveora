package studio.weaveora.project.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String title,
        String mode,
        String aspectRatio,
        BigDecimal durationSec,
        String status,
        OffsetDateTime createdAt
) {
}
