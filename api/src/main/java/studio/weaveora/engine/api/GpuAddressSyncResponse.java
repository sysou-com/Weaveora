package studio.weaveora.engine.api;

import java.util.List;

/**
 * 一键同步 GPU 地址的结果：替换清单 + 「改漏」检查 + （可选）worker env 同步结果 + 同步后的完整配置。
 *
 * <p>设计要点：**必须把改了什么原样回给前端**。这套配置里同一个地址存在多处副本
 * （gpuServerUrl 一处 + services 里最多 7 处显式 URL + worker env 里 5 个键），
 * 用户看不到清单就无法判断有没有改漏，而"改漏"正是 2026-09-21 那次事故的成因。
 *
 * @param oldHost   被替换掉的旧主机
 * @param newHost   新主机
 * @param newPort   新端口
 * @param changes   实际发生替换的字段（{@code field} = {@code gpuServerUrl} / {@code services.image.comfyUrl} …）
 * @param leftovers 仍是「IP 字面量」、既不是新主机也不是本机回环的 URL（= **可能改漏的地方**，前端要显式告警）
 * @param workerEnv 勾选「同时同步 worker env」时的结果；未勾选为 null
 * @param settings  同步后的完整配置（前端直接用它刷新表单，不需要再 GET 一次）
 */
public record GpuAddressSyncResponse(
        String oldHost,
        String newHost,
        Integer newPort,
        List<Change> changes,
        List<String> leftovers,
        WorkerEnv workerEnv,
        EngineSettingsResponse settings) {

    /** 单个字段的替换记录。 */
    public record Change(String field, String before, String after) {
    }

    /**
     * worker env（{@code weaveora-gpu-worker.env}）的同步结果。
     *
     * @param available   本机 env 是否可用（文件存在且可写）；false = 只改了数据库
     * @param requested   调用方是否勾选了同步 env
     * @param applied     是否真的改写了文件（false 的两种情况：已是新值 / 被护栏拦住）
     * @param changes     env 里被改写的键（KEY: before → after）
     * @param backupPath  改前备份路径（同目录 {@code .bak.<ts>}）
     * @param restarted   是否执行了 {@code systemctl restart}
     * @param serviceState 重启后的服务状态（active / inactive / failed / unknown）
     * @param message     人话说明：为什么没改 / 被什么挡住 / 结果如何
     */
    public record WorkerEnv(
            boolean available,
            boolean requested,
            boolean applied,
            List<Change> changes,
            String backupPath,
            Boolean restarted,
            String serviceState,
            String message) {
    }
}
