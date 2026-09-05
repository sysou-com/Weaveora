package studio.weaveora.director.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/** generate 成功响应（完整 DirectorPlan + 版本标识）。 */
public record GenerateResponse(
        UUID revisionId,
        int revisionNo,
        String source,          // llm | stub
        String projectStatus,   // §20.1：directing
        JsonNode plan
) {
}
