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
        Integer gpuServerPort
) {
}
