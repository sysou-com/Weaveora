package studio.weaveora.director.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** revision 详情：完整方案 + 镜头表落库状态（shot 状态以 shot_drafts 为准）。 */
public record RevisionDetailResponse(
        UUID id,
        UUID briefId,
        int revisionNo,
        String source,
        boolean approved,
        JsonNode plan,
        List<ShotView> shots,
        OffsetDateTime createdAt
) {
}
