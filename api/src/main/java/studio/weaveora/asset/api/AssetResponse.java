package studio.weaveora.asset.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        UUID projectId,
        String kind,
        String mime,
        Integer width,
        Integer height,
        OffsetDateTime createdAt
) {
}
