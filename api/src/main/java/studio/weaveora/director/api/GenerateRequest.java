package studio.weaveora.director.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** POST /api/v1/projects/{id}/director/generate（§17.3）。 */
public record GenerateRequest(
        @NotNull UUID briefId,
        String mode,           // image | video；缺省沿用 brief→project
        /** 提示词语言：zh | en；缺省读 brief.constraints.promptLang，再缺省 en（系统词既定口径：prompt 字段用英文） */
        String promptLang
) {
}
