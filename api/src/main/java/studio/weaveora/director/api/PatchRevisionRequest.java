package studio.weaveora.director.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/projects/{id}/revisions/{rid} —— 提交整份可编辑方案（§7.4：仅未确认版本可改）。 */
public record PatchRevisionRequest(
        @NotNull JsonNode plan
) {
}
