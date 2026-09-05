package studio.weaveora.director.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** POST /api/v1/projects/{id}/director/generate（§17.3）。 */
public record GenerateRequest(
        @NotNull UUID briefId,
        String mode            // image | video；缺省沿用 brief→project
) {
}
