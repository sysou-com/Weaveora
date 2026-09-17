package studio.weaveora.script.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/scripts/{id}/ai/polish —— **润色作者自己写的正文**（用户 2026-09-17 追加）。
 *
 * <p>与 {@link AiNextEpisodeRequest} 的区别：那个是「按精简的故事另写一集」，这个是
 * 「**在作者已有正文的基础上**改文笔，不重编剧情」—— 所以必须显式带上 {@code content}。
 *
 * @param content     待润色的正文（用户在「我自己写」里写的 / 编辑器里的当前稿）
 * @param instruction 额外要求（可选，≤2000 字）
 * @param targetChars 目标字数（≤0/不传 = **保持原长度**，只改文笔；上限 8000）
 */
public record AiPolishRequest(
        @Size(max = 40000) String content,
        @Size(max = 2000) String instruction,
        @Min(0) @Max(8000) Integer targetChars
) {
}
