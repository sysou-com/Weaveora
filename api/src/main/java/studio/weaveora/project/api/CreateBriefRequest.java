package studio.weaveora.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/projects/{id}/briefs（§7.2：raw_text 必填 10–2000 字；referenceAssetIds 走 constraints 透传）。 */
public record CreateBriefRequest(
        @NotBlank @Size(min = 10, max = 2000) String rawText,
        String mode,          // image | video | auto，默认沿用项目
        com.fasterxml.jackson.databind.JsonNode constraints
) {
}
