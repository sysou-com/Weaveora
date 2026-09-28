package studio.weaveora.job.api;

import java.util.List;

/**
 * P15（2026-09-25 用户裁定）：**多主体关键帧的用户确认**。
 *
 * <p>为什么需要它：一镜剧情主体 &gt; 2 个时，关键图里会有 &gt; 2 张人脸。实测（见
 * {@code docs/第4镜-脸型错乱-排查-2026-09-25.md}）这个组合下每张脸只占画面高度的 10–13%，
 * 18 次出图统计「脸宽 ↔ 身份 cos」Pearson r=0.722 ⇒ 脸不够大时身份必然绑不住。
 * 所以出关键帧前先把这件事摆给用户：**换成过肩/中近景/近景，并指定哪 ≤2 个主体需要露出清晰人脸**。
 *
 * <p>用户确认后由 {@code JobService.applyKeyframeConfirm} 把「景别 + 可见主体」写进本次出图的正词
 * （并替换掉原正词里可能存在的冲突景别词），再继续建 still 任务 —— 即用户口径
 * 「确定以后更新提示词并继续进行关键帧出图」。
 *
 * @param shotNo          哪一镜（按 shot_no 匹配；必填）
 * @param keyframeIndex   运镜镜头的第几帧（null / &lt;0 = 该镜全部帧共用这一份确认）
 * @param shotSize        {@code over_the_shoulder} | {@code medium_close} | {@code close_up}
 * @param visibleSubjects 这一帧要让观众**看清脸**的剧情主体（建议 ≤2；空 = 不限制，
 *                        仅写景别约束）
 * @param sceneNote       可选的场景/取景补充（用户手填，原样追加进正词）
 */
public record KeyframeConfirm(
        Integer shotNo,
        Integer keyframeIndex,
        String shotSize,
        List<String> visibleSubjects,
        String sceneNote
) {

    /** 这份确认是否覆盖第 idx 帧（运镜镜头逐帧建任务）。 */
    public boolean coversKeyframe(int idx) {
        return keyframeIndex == null || keyframeIndex < 0 || keyframeIndex == idx;
    }

    public boolean isEmpty() {
        return (shotSize == null || shotSize.isBlank())
                && (visibleSubjects == null || visibleSubjects.isEmpty())
                && (sceneNote == null || sceneNote.isBlank());
    }
}
