package studio.weaveora.project.api;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 分享提交：assetIds 可选——仅将选中的素材上架到集市。 */
public record ShareProjectRequest(
        @Size(max = 100) List<UUID> assetIds
) {
}
