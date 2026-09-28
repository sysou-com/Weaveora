package studio.weaveora.job.api;

import java.util.UUID;

/**
 * P15：关键帧「人脸数量 &gt; 2」预检请求（只读，不建任务）。
 *
 * <p>前端在点「生成关键帧」之后、真正 POST /jobs 之前调它：
 * 返回**需要用户确认的镜**（剧情主体 &gt; 2 个）以及建议景别与将要追加的构图约束文本。
 * 用户确认后把 {@link KeyframeConfirm} 一并传给 POST /jobs。
 *
 * @param revisionId 方案版本（同 POST /jobs）
 * @param shotId     只查这一镜（可空）
 * @param kind       任务类型（可空，默认 still）
 * @param shotNos    只查这些镜（可空 = 该版本全部可生成的镜）
 */
public record KeyframeFaceCheckRequest(
        UUID revisionId,
        UUID shotId,
        String kind,
        java.util.List<Integer> shotNos
) {
}
