package studio.weaveora.director;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import studio.weaveora.director.plan.StubDirectorLlm;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmProperties;
import studio.weaveora.infra.llm.OpenAiDirectorLlm;

/**
 * 导演层 LLM 网关装配（§10.1）。weaveora.llm.*（base-url/api-key/model）齐备 → 真实 OpenAI-Compatible；
 * 否则 StubDirectorLlm 离线兜底（source=stub，UI 标注“示例方案”）。
 * 注：除配置绑定外，另直读同名环境变量作兜底（生产经 systemd EnvironmentFile 注入，避免占位/绑定差异）。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public DirectorLlm directorLlm(LlmProperties props) {
        String base = firstNonBlank(props.getBaseUrl(), env("WEAVEORA_LLM_BASE_URL"));
        String key = firstNonBlank(props.getApiKey(), env("WEAVEORA_LLM_API_KEY"));
        String model = firstNonBlank(props.getModel(), env("WEAVEORA_LLM_MODEL"));
        if (base != null && key != null && model != null) {
            props.setBaseUrl(base);
            props.setApiKey(key);
            props.setModel(model);
            return new OpenAiDirectorLlm(props);
        }
        return new StubDirectorLlm();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : ((b != null && !b.isBlank()) ? b : null);
    }

    private static String env(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException e) {
            return null;
        }
    }
}
