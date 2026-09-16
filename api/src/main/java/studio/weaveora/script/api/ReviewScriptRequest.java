package studio.weaveora.script.api;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** 管理审批：批量通过 / 驳回（对齐 ReviewProjectRequest）。 */
public record ReviewScriptRequest(@NotEmpty List<UUID> scriptIds, Boolean approved) {
}
