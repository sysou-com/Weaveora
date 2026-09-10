package studio.weaveora.asset.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        UUID projectId,
        UUID jobId,
        UUID shotId,
        Integer shotNo,
        String kind,
        String mime,
        Integer width,
        Integer height,
        OffsetDateTime createdAt
) {
}
