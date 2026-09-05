package studio.weaveora.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** weaveora.llm.*（§10.1 / §23.1）：OpenAI Compatible baseUrl + apiKey + model。 */
@ConfigurationProperties(prefix = "weaveora.llm")
public class LlmProperties {

    /** 为空 → 不启用真实 LLM，走 Stub（离线可演示全链路）。 */
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    /** 单次同步等待上限（§0-16：director 可同步等 ≤60s）。 */
    private int timeoutSec = 60;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(int timeoutSec) { this.timeoutSec = timeoutSec; }

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}
