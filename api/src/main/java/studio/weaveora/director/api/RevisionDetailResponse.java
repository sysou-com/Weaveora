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
        OffsetDateTime createdAt,
        /** 「只提示不拦」的中文提示（场景切换过密 / 一镜内换场景）；空列表 = 无需提示 */
        List<String> notices
) {
}
