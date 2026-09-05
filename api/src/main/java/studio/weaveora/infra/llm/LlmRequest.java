package studio.weaveora.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** 导演层一次 LLM JSON 生成请求（结构化字段供 stub 离线兜底，真实 LLM 只看 system/user）。 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        String projectTitle,
        String briefText,
        String mode,          // image | video
        String aspectRatio,
        BigDecimal durationSec,
        JsonNode constraints
) {
}
