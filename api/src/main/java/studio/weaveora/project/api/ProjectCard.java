package studio.weaveora.project.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 项目列表卡片（自建列表 / 集市 / 管理待审共用）。 */
public record ProjectCard(
        UUID id,
        String title,
        String mode,
        String aspectRatio,
        BigDecimal durationSec,
        String status,
        String shareStatus,
        String ownerName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
