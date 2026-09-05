package studio.weaveora.asset.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 批量资产删除请求（资产库勾选产物；上限防误触）。 */
public record BatchAssetRequest(
        @NotEmpty @Size(max = 100) List<UUID> assetIds
) {
}
