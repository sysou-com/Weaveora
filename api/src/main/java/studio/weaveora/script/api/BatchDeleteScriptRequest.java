package studio.weaveora.script.api;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** 管理态批量软删（对齐 POST /api/v1/projects/delete）。 */
public record BatchDeleteScriptRequest(@NotEmpty List<UUID> scriptIds) {
}
