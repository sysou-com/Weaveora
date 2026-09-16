package studio.weaveora.script.api;

/** 单字段 AI 结果（Q3：前端拿它与现有值做 diff，用户确认后再写入）。 */
public record AiFieldResult(
        String field,
        String value,
        String note,
        boolean changed,
        String source
) {
}
