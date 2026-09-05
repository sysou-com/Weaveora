package studio.weaveora.infra.llm;

/** 导演层 LLM 网关（§10.1 统一抽象：completeJson + OpenAI Compatible）。
 * 实现：OpenAiDirectorLlm（配置 base-url/api-key/model 时）；StubDirectorLlm（离线兜底，产出可编辑示例）。 */
public interface DirectorLlm {

    /** 生成并返回 JSON 字符串（必须是纯 JSON；实现内负责格式规整/重试）。 */
    String generateJson(LlmRequest request);

    /** 来源标记：llm | stub（落 prompt_revisions.source）。 */
    String source();
}
