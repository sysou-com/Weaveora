package studio.weaveora.project.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 批量删除项目（管理态勾选） */
public record BatchDeleteProjectRequest(
        @NotEmpty @Size(max = 100) List<UUID> projectIds
) {
}
