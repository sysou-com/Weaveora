package studio.weaveora.script.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 新增/更新一集的请求体。 */
public record SaveEpisodeRequest(
        @Size(max = 200) String title,
        @Size(max = 40000) String content,
        @Size(max = 2000) String summary,
        Boolean aiPolished,
        /** true（默认）= 保存后刷新「精简的故事」并做一致性检查（Q4：只提议，用户确认后才改历史章节）。 */
        Boolean syncPrevious
) {
    public boolean sync() {
        return syncPrevious == null || syncPrevious;
    }
}
