package studio.weaveora.project.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** POST /api/v1/projects/{id}/briefs（§7.2：raw_text 必填 10–2000 字；referenceAssetIds 最多 4 张）。 */
public record CreateBriefRequest(
        @NotBlank @Size(min = 10, max = 2000) String rawText,
        String mode,          // image | video | auto，默认沿用项目
        JsonNode constraints,
        List<UUID> referenceAssetIds   // W2C 参考图
) {
}
