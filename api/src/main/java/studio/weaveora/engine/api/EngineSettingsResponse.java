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
        Integer gpuServerPort
) {
}
