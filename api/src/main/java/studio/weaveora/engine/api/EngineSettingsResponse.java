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
        /** GPU 服务器「最大支持分辨率」：480p/720p/1080p/auto —— motion 出片上限（机器能力，换卡就改这里） */
        String gpuMaxResolution,
        /** **图片分辨率**（出图长边像素）：1280（默认，16:9→1280×704）/1920/2560 —— 全局生效于关键帧/定妆照/参考图 */
        Integer imageMaxResolution,
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
        Integer gatewayRefsMax,
        /** 网关通道：已保存的示例请求 */
        String gatewaySample,
        /** P12 模型库：已配置的模型条目（baseUrl/model/params/schema…） */
        com.fasterxml.jackson.databind.JsonNode imageModelPresets,
        com.fasterxml.jackson.databind.JsonNode videoModelPresets,
        /**
         * 服务地址（配音/配乐、对口型、转写、人脸），已填默认值。
         *
         * <p>换 GPU 服务器时改这里即可（worker 按任务下发），不必再改 worker 脚本/环境变量。
         */
        com.fasterxml.jackson.databind.JsonNode services
) {
}
