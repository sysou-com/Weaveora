package studio.weaveora.export.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** POST /projects/{id}/render —— 已确认视频 revision → ffmpeg 合成 master mp4 */
public record RenderRequest(
        @NotNull UUID revisionId,
        String transition          // cut|fade（默认 cut；fade=每段 0.25s 淡入淡出，轻精修）
) {
}
