package studio.weaveora.export.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 导出结果 */
public record ExportInfo(UUID id, UUID projectId, UUID revisionId, String downloadUrl,
                         OffsetDateTime createdAt) {
}
