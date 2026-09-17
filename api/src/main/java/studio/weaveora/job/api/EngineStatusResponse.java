package studio.weaveora.job.api;

/**
 * 引擎在线状态（只读）—— 前端据此给「GPU 不在线」的**提示**，而不是让生成直接报错。
 *
 * <p>2026-09-17 用户口径：GPU 不在线**不要报错**，给提示就行，用户还要继续处理分镜动作、提示词等；
 * 出图/出片任务留在队列里，节点恢复后自动开始。
 *
 * @param gpuOnline  自托管（ComfyUI）车道是否有在线节点
 * @param cloudOnline 云 API 车道是否有在线节点
 * @param notice     可直接展示的中文提示；空串 = 都在线，不需要提示
 */
public record EngineStatusResponse(
        boolean gpuOnline,
        boolean cloudOnline,
        String notice
) {
}
