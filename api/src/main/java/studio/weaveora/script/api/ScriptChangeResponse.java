package studio.weaveora.script.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 变更记录（GET /api/v1/scripts/{id}/changes）。 */
public record ScriptChangeResponse(
        UUID id,
        String kind,
        Integer episodeNo,
        List<ChangedEpisode> changedEpisodes,
        String note,
        String actor,
        OffsetDateTime createdAt
) {
    /** 受影响的既有章节：{@code what} 说明改了什么。 */
    public record ChangedEpisode(Integer episodeNo, String title, String what) {
    }
}
