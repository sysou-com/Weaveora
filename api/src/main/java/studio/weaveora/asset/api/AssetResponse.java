package studio.weaveora.asset.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        UUID projectId,
        UUID jobId,
        UUID shotId,
        Integer shotNo,
        String kind,
        String mime,
        Integer width,
        Integer height,
        /**
         * P10：产物真实时长（毫秒）。配音/配乐靠它做字幕对齐与超长判定；
         * 以前没暴露，前端无法知道“这条配音实际多长”。
         */
        Integer durationMs,
        /** P10：配音在镜内的段号（来自 prompt_snapshot.line_index）；非配音产物为空 */
        Integer lineIndex,
        OffsetDateTime createdAt
) {
}
