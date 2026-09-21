package studio.weaveora.engine.api;

import java.util.Map;

/**
 * GET /api/v1/me/engine-settings/worker-env —— worker 机器 env 的**当前回退值**。
 *
 * <p>用途：页面把它显示成一行「worker 回退地址 = …」，与页面上的地址对比一眼就能看出两边是否一致
 * （不一致就说明还有第二个真源没对齐 —— 这正是 2026-09-21 那次"改了页面还是 Connection refused"的隐患）。
 *
 * @param available    本机 env 是否可用（不存在/不可写 = 该能力不可用，页面显示"仅数据库生效"）
 * @param file         env 文件路径
 * @param service      systemd 服务名
 * @param values       白名单键的当前值（KEY → URL；只含文件里存在的键）
 * @param serviceState 服务状态（active / inactive / failed / unknown）
 */
public record WorkerEnvStatusResponse(
        boolean available,
        String file,
        String service,
        Map<String, String> values,
        String serviceState) {
}
