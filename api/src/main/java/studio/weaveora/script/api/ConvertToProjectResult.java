package studio.weaveora.script.api;

import java.util.UUID;

/** 转成项目的结果：可直接跳 `/app/projects/{projectId}` 查看分镜动作与提示词。 */
public record ConvertToProjectResult(
        UUID projectId,
        UUID briefId,
        UUID revisionId,
        int shotCount,
        String projectTitle,
        String note,
        /** 本次生成的是项目里的第几版（V{n}） */
        Integer revisionNo,
        /** true = 复用了已有项目出**新版本**（不是新建项目） */
        boolean appended
) {
}
