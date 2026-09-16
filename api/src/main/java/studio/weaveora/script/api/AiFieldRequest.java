package studio.weaveora.script.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/scripts/{id}/ai/field —— 单字段 AI 生成 / 更新。
 *
 * @param mode        {@code from_title}（仅凭标题+类型生成）| {@code from_content}（读全部要素+已写集数后更新）
 * @param targetChars 用户设定的目标字数（弹窗里设；≤0/不传 = 默认 4000，**上限 8000**）
 */
public record AiFieldRequest(
        @NotBlank String field,
        @NotBlank String mode,
        @Size(max = 2000) String hint,
        @Size(max = 20000) String currentValue,
        @Min(0) @Max(8000) Integer targetChars
) {
    public boolean fromContent() {
        return "from_content".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}
