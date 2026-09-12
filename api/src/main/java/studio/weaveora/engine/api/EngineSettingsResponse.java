package studio.weaveora.engine.api;

import java.util.UUID;

/** GET /api/v1/me/engine-settings 响应。密钥只回打码值（mask），不改密显示原明文。 */
public record EngineSettingsResponse(
        String imageEngine,
        String videoEngine,
        String imageCloudBaseUrl,
        String imageCloudAuthType,
        String imageCloudModel,
        String imageCloudApiKeyMask,
        String imageCloudUsername,
        boolean imageCloudPasswordSet,
        String videoCloudModel,
        String videoCloudApiKeyMask,
        String gpuServerUrl,
        Integer gpuServerPort,
        /** P12：模型调用参数说明（拉取缓存）：{provider,model,version,params[],mapping,notes[]} */
        com.fasterxml.jackson.databind.JsonNode imageModelSchema,
        com.fasterxml.jackson.databind.JsonNode videoModelSchema,
        /** 用户设置的全局参数（画质等） */
        com.fasterxml.jackson.databind.JsonNode imageParams,
        com.fasterxml.jackson.databind.JsonNode videoParams,
        /** 拉取参数说明的失败原因（有值时前端应提示刷新，而不是显示可能过期的说明） */
        String imageModelSchemaError,
        String videoModelSchemaError,
        /** 网关通道：本模型单次最多参考图张数（0/空=未知） */
        Integer gatewayRefsMax
) {
}
