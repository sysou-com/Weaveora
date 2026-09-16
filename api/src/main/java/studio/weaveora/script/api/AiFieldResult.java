package studio.weaveora.script.api;

import java.util.List;

/**
 * 单字段 AI 结果（Q3：前端拿它与现有值做 diff，用户确认后再写入）。
 *
 * @param outline 【B】本次的分段写作提纲（每段一行「第K段：标题 —— 要点」）；空数组=未生成提纲
 */
public record AiFieldResult(
        String field,
        String value,
        String note,
        boolean changed,
        String source,
        List<String> outline
) {
}
