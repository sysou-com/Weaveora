package studio.weaveora.director;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import studio.weaveora.director.plan.StubDirectorLlm;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmProperties;
import studio.weaveora.infra.llm.OpenAiDirectorLlm;

/**
 * 导演层 LLM 网关装配（§10.1）：weaveora.llm.* 三者齐备（configured）走真实 OpenAI-Compatible；
 * 否则 StubDirectorLlm 离线兜底（source=stub，UI 标注“示例方案”）。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public DirectorLlm directorLlm(LlmProperties props) {
        if (props.configured()) {
            return new OpenAiDirectorLlm(props);
        }
        return new StubDirectorLlm();
    }
}
