package studio.weaveora.script.api;

import java.util.UUID;

/** 转成项目的结果：可直接跳 `/app/projects/{projectId}` 查看分镜动作与提示词。 */
public record ConvertToProjectResult(
        UUID projectId,
        UUID briefId,
        UUID revisionId,
        int shotCount,
        String projectTitle,
        String note
) {
}
