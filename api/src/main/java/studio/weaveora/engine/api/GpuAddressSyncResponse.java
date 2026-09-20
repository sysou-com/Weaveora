package studio.weaveora.engine.api;

import java.util.List;

/**
 * 一键同步 GPU 地址的结果：替换清单 + 「改漏」检查 + 同步后的完整配置。
 *
 * <p>设计要点：**必须把改了什么原样回给前端**。原因是这套配置里同一个地址存在多处副本
 * （gpuServerUrl 一处 + services 里最多 7 处显式 URL），用户看不到清单就无法判断有没有改漏，
 * 而"改漏"正是 2026-09-21 那次事故的成因。
 *
 * @param oldHost   被替换掉的旧主机
 * @param newHost   新主机
 * @param newPort   新端口
 * @param changes   实际发生替换的字段（{@code field} = {@code gpuServerUrl} / {@code services.image.comfyUrl} …）
 * @param leftovers 仍是「IP 字面量」、既不是新主机也不是本机回环的 URL（= **可能改漏的地方**，前端要显式告警）
 * @param settings  同步后的完整配置（前端直接用它刷新表单，不需要再 GET 一次）
 */
public record GpuAddressSyncResponse(
        String oldHost,
        String newHost,
        Integer newPort,
        List<Change> changes,
        List<String> leftovers,
        EngineSettingsResponse settings) {

    /** 单个字段的替换记录。 */
    public record Change(String field, String before, String after) {
    }
}
