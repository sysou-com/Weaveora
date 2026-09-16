package studio.weaveora.script.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * POST /api/v1/scripts/ai/preview-field —— **无剧本 ID** 的单字段生成（新建剧本页用）。
 *
 * <p>为什么需要：新建页在保存前就要「按标题生成各要素」，此时剧本还没落库；
 * 用无状态入口避免为了预览而先创建空草稿（否则列表里会出现幽灵草稿）。
 *
 * @param mode     from_title | from_content
 * @param elements from_content 时可选：页面上已填的其它要素（key 与 {@code ScriptField.key} 一致），供 AI 参考
 */
public record AiFieldPreviewRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 50) String genre,
        @NotBlank String field,
        @NotBlank String mode,
        @Size(max = 2000) String hint,
        @Size(max = 20000) String currentValue,
        Map<String, String> elements
) {
    public boolean fromContent() {
        return "from_content".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public AiFieldRequest toFieldRequest() {
        return new AiFieldRequest(field, mode, hint, currentValue);
    }
}
