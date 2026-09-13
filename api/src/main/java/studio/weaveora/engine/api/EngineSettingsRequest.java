package studio.weaveora.engine.api;

import java.util.UUID;

/** PUT /api/v1/me/engine-settings 请求体。apiKey/password 为空 = 保留原值。 */
public record EngineSettingsRequest(
        String imageEngine,
        String videoEngine,
        String imageCloudBaseUrl,
        String imageCloudAuthType,
        String imageCloudApiKey,
        String imageCloudUsername,
        String imageCloudPassword,
        String imageCloudModel,
        String videoCloudApiKey,
        String videoCloudModel,
        String gpuServerUrl,
        Integer gpuServerPort,
        /** P12：全局参数（画质等），键须在模型 schema 里存在；null=不改 */
        com.fasterxml.jackson.databind.JsonNode imageParams,
        com.fasterxml.jackson.databind.JsonNode videoParams,
        /** 网关通道：本模型单次最多参考图张数（按官方文档填，如方舟 doubao-seedream-5 = 14） */
        Integer gatewayRefsMax,
        /** 网关通道：示例请求（curl 或 JSON body），用于解析参数格式 */
        String gatewaySample,
        /** 服务地址（配音/配乐、对口型、转写、人脸）；null=不改 */
        com.fasterxml.jackson.databind.JsonNode services
) {
}
