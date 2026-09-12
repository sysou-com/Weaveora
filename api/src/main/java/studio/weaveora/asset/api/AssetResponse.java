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
        /**
         * P13：该产物对应的剧情主体（来自 prompt_snapshot.subject）—— 定妆图靠它按主体归类。
         * 父与子以前没暴露快照，前端**根本看不到哪张是定妆图**，于是「选图」永远灰着（实测踩过）。
         */
        String subject,
        /** P13：定妆图版本号（来自 prompt_snapshot.portrait_version）；非定妆图为空 */
        Integer portraitVersion,
        /** P13：快照里的产物类别（portrait = 定妆图），兼容历史数据 kind 被写成 still 的情况 */
        String snapshotKind,
        OffsetDateTime createdAt
) {
}
