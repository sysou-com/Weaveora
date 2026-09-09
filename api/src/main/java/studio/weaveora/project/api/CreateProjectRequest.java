package studio.weaveora.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String title,
        String mode,           // image | video | mixed（默认 image）
        String aspectRatio,    // 1:1 3:2 2:3 16:9 9:16（默认 16:9）
        BigDecimal durationSec, // 视频时长
        UUID styleTemplateId,  // 风格模板（可空；空=默认跟随描述）
        java.math.BigDecimal shotDurationSec // 视频：每镜时长偏好（可空=导演自动；按 总时长/每镜 分镜）
) {
}
