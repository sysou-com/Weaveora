package studio.weaveora.script.api;

/**
 * 润色结果（**不落库**：前端拿到后填进编辑器，用户确认再保存）。
 *
 * @param content       润色后的正文
 * @param note          需要告知用户的提示（分段润色/压缩/某段失败等），无则空串
 * @param source        LLM 来源（deepseek / stub …）
 * @param originalChars 润色前字数（便于前端展示「N → M 字」）
 */
public record AiPolishResult(
        String content,
        String note,
        String source,
        int originalChars
) {
}
