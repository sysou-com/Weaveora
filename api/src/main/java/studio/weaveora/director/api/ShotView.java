package studio.weaveora.director.api;

import java.math.BigDecimal;
import java.util.UUID;

/** 镜头行（落库状态来源 shot_drafts）。 */
public record ShotView(
        UUID id,
        int shotNo,
        BigDecimal durationSec,
        String shotSize,
        String cameraMove,
        String action,
        String positivePrompt,
        String negativePrompt,
        boolean seedLock,
        Integer refShotNo,
        String status
) {
}
