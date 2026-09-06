package studio.weaveora.project.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 集市审批（批量 通过/驳回） */
public record ReviewProjectRequest(
        @NotEmpty @Size(max = 100) List<UUID> projectIds,
        @NotNull Boolean approved
) {
}
