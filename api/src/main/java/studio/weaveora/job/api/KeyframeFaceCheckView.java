package studio.weaveora.job.api;

import java.util.List;

/**
 * P15：一镜「关键图人脸数量 &gt; 2」的预检结果（每镜一条）。只包含**需要确认**的镜。
 *
 * @param shotNo            镜号
 * @param shotId            镜 id（前端按它定位到那一镜的卡片）
 * @param subjectCount      该镜解析出的剧情主体个数（参考图主体数）
 * @param subjects          主体名列表（用户在弹框里挑「要显示脸的主体」）
 * @param shotSize          方案里现有的景别字段值（可能为空）
 * @param suggestedShotSizes 建议景别（词典值）：over_the_shoulder / medium_close / close_up
 * @param message           给用户看的一句话（跟随该镜正词语言）
 * @param constraintPreview 用户确认后**将要追加到正词**的那段构图约束（同一份确认不填主体时为空）
 */
public record KeyframeFaceCheckView(
        int shotNo,
        String shotId,
        int subjectCount,
        List<String> subjects,
        String shotSize,
        List<String> suggestedShotSizes,
        String message,
        String constraintPreview
) {
}
