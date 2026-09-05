package studio.weaveora.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String title,
        String mode,           // image | video | mixed（默认 image）
        String aspectRatio,    // 1:1 3:2 2:3 16:9 9:16（默认 16:9）
        BigDecimal durationSec // 视频时长
) {
}
