package studio.weaveora.script.api;

import jakarta.validation.constraints.Size;

/** PATCH /api/v1/scripts/{id} —— 全部字段可空（null = 不改；空串 = 显式清空）。 */
public record UpdateScriptRequest(
        @Size(max = 100) String title,
        @Size(max = 50) String genre,
        @Size(max = 8000) String characters,
        @Size(max = 8000) String story,
        @Size(max = 8000) String conflict,
        @Size(max = 8000) String plotStructure,
        @Size(max = 8000) String language,
        @Size(max = 8000) String stageDirections,
        @Size(max = 8000) String condensedStory
) {
}
