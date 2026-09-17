package studio.weaveora.script.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 一集（GET /api/v1/scripts/{id}/episodes）。 */
public record ScriptEpisodeResponse(
        UUID id,
        int episodeNo,
        String title,
        String content,
        String summary,
        boolean aiPolished,
        /** 【B】本集的节拍提纲（该集照着哪份提纲写的）；无则空数组 */
        java.util.List<String> outline,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** 这一集已经转出的项目（null = 还没转过）——前端据此显示「查看项目」 */
        UUID projectId,
        String projectTitle,
        /** 该项目当前的版本号（V{n}） */
        Integer projectRevisionNo
) {
}
