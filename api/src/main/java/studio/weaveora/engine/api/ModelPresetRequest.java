package studio.weaveora.engine.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型库条目（POST /api/v1/me/engine-settings/models）。
 *
 * <p>kind=image|video；按 (kind, baseUrl, model) 唯一，重复提交即更新该条目（并刷新参数说明）。
 */
public record ModelPresetRequest(
        String kind,
        String baseUrl,
        String model,
        /** 调过的参数（画质等）；null = 不改动已存的 */
        JsonNode params,
        /** 网关通道：参考图张数上限 */
        Integer gatewayRefsMax,
        /** 网关通道：示例请求（curl / JSON body） */
        String gatewaySample,
        /** true = 同时把它设为当前生效的模型（写回 image/video 云端配置） */
        Boolean apply
) {
}
