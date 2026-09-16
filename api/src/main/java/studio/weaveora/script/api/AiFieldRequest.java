package studio.weaveora.script.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/scripts/{id}/ai/field —— 单字段 AI 生成 / 更新。
 *
 * @param mode {@code from_title}（仅凭标题+类型生成）| {@code from_content}（读全部要素+已写集数后更新）
 */
public record AiFieldRequest(
        @NotBlank String field,
        @NotBlank String mode,
        @Size(max = 2000) String hint,
        @Size(max = 20000) String currentValue
) {
    public boolean fromContent() {
        return "from_content".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}
