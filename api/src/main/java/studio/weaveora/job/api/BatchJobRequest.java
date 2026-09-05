package studio.weaveora.job.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 批量任务操作请求（重试/删除所选 Job；上限防误触）。 */
public record BatchJobRequest(
        @NotEmpty @Size(max = 100) List<UUID> jobIds
) {
}
