package studio.weaveora.job.api;

import java.util.UUID;

/** POST /api/v1/projects/{id}/jobs（§17.5）。 */
public record CreateJobRequest(
        UUID revisionId,      // 必须为项目当前 approved_revision
        UUID shotId,          // 可选：视频单镜
        String kind,          // still | clip
        Integer count,        // 图片张数 1/2/4（默认 1）
        Integer frames        // motion(clip) 帧数：系统范围 32–96
) {
}
