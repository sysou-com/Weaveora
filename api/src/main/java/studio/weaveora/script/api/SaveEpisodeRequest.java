package studio.weaveora.script.api;

import jakarta.validation.constraints.Size;

import java.util.List;

/** 新增/更新一集的请求体。 */
public record SaveEpisodeRequest(
        @Size(max = 200) String title,
        @Size(max = 40000) String content,
        @Size(max = 2000) String summary,
        Boolean aiPolished,
        /** true（默认）= 保存后刷新「精简的故事」并做一致性检查（Q4：只提议，用户确认后才改历史章节）。 */
        Boolean syncPrevious,
        /** 【B】本集的节拍提纲（由 ai/next-episode 回传后原样带回，便于重开复盘与续写）；可空。 */
        @Size(max = 40) List<@Size(max = 300) String> outline
) {
    public boolean sync() {
        return syncPrevious == null || syncPrevious;
    }
}
