package studio.weaveora.export.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 导出详情（含 edit_list 供预览/调试） */
public record ExportDetail(UUID id, UUID projectId, UUID revisionId, JsonNode editList,
                           OffsetDateTime createdAt) {
}
