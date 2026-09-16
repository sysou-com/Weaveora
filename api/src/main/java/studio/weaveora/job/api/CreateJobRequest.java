package studio.weaveora.job.api;

import java.util.UUID;

/** POST /api/v1/projects/{id}/jobs（§17.5）。 */
public record CreateJobRequest(
        UUID revisionId,      // 必须为项目当前 approved_revision
        UUID shotId,          // 可选：视频单镜
        String kind,          // still | clip | voice | bgm | portrait（P13 定妆图）
        Integer count,        // 图片张数 1/2/4（默认 1）
        Integer frames,       // motion(clip) 帧数：系统范围 32–96
        Boolean preview,      // true=试听任务（配音/配乐试听），不参与正式成片
        Integer lineIndex,    // P8：voice 任务只重生成该镜的第 N 段语音（null=全部段落）
        java.util.List<Integer> shotNos,   // P12：只生成这些镜（null=全部；显式列出时可包含已封版镜）
        Boolean includeLocked,             // P12：true=连已封版镜一起生成（默认 false）
        String subject,                    // P13：kind=portrait 时指定剧情主体名
        java.util.List<String> refAssetIds, // P13：kind=portrait 时直接指定参考图资产（用界面上当前点选的图，无需先保存/确认）
        String positivePrompt,             // P13b：kind=portrait 时用户在弹框里确认过的正向提示词（空=用默认模板）
        String negativePrompt              // P13b：kind=portrait 时的负向提示词（空=用默认负词）
) {
}
