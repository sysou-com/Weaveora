package studio.weaveora.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Compatible chat/completions 实现（§10.1 completeJson；DeepSeek 等国内云同构）。
 * 同步等待（director 允许 ≤60s）；HTTP/解析失败重试 1 次；纯 JSON 输出（response_format）。
 */
public class OpenAiDirectorLlm implements DirectorLlm {

    private static final Logger log = LoggerFactory.getLogger(OpenAiDirectorLlm.class);

    private final RestClient client;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiDirectorLlm(LlmProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(props.getTimeoutSec() * 1000);
        this.model = props.getModel();
        this.apiKey = props.getApiKey();
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl().replaceAll("/$", ""))
                .requestFactory(f)
                .build();
    }

    @Override
    public String generateJson(LlmRequest request) {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String content = callOnce(request);
                String cleaned = stripFences(content);
                // 校验确实是 JSON（早失败早重试）
                mapper.readTree(cleaned);
                return cleaned;
            } catch (Exception e) {
                last = e;
                log.warn("director LLM 第 {} 次调用失败: {}", attempt, e.getMessage());
            }
        }
        throw new IllegalStateException("director LLM 调用失败（2 次尝试）", last);
    }

    private String callOnce(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.7);
        // W8 分段导演：段方案内容较长，6000 偶发被截断（finish_reason=length）→ 提到 DeepSeek 上限附近
        body.put("max_tokens", 8000);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));

        JsonNode resp = client.post()
                .uri("/chat/completions")
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setBearerAuth(apiKey);
                })
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM 响应缺少 choices[0].message.content");
        }
        String finish = resp.path("choices").path(0).path("finish_reason").asText("");
        if ("length".equals(finish)) {
            // 输出被 max_tokens 截断 → 属于可修复错误，立即重试（上层会压缩指令重出）
            throw new IllegalStateException("LLM 输出达到 max_tokens 被截断（finish_reason=length）");
        }
        return content.asText();
    }

    /** 容忍模型偶尔输出 ```json 围栏。 */
    static String stripFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.trim();
        }
        return s;
    }

    @Override
    public String source() {
        return "llm";
    }
}
