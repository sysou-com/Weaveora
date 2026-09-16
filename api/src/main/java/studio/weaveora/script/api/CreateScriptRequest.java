package studio.weaveora.script.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/scripts（标题 + 类型必填；6 个可 AI 生成要素可空，上限 8000 字/字段）。 */
public record CreateScriptRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 50) String genre,
        @Size(max = 8000) String characters,
        @Size(max = 8000) String story,
        @Size(max = 8000) String conflict,
        @Size(max = 8000) String plotStructure,
        @Size(max = 8000) String language,
        @Size(max = 8000) String stageDirections
) {
}
