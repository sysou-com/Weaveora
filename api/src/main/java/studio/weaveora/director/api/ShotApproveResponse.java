package studio.weaveora.director.api;

import java.util.UUID;

/** 单镜确认响应（§9.2 只确认第 N 镜）。 */
public record ShotApproveResponse(
        UUID revisionId,
        UUID shotId,
        int shotNo,
        String status
) {
}
