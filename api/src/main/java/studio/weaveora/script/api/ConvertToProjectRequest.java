package studio.weaveora.script.api;

import java.math.BigDecimal;
import java.util.UUID;

/** POST /api/v1/scripts/{id}/episodes/{eid}/to-project —— 把一集转化成项目并出分镜。 */
public record ConvertToProjectRequest(
        /** image | video（默认 video —— 要出分镜动作与提示词）。 */
        String mode,
        String aspectRatio,
        BigDecimal durationSec,
        BigDecimal shotDurationSec,
        UUID styleTemplateId,
        /** true（默认）= 先把整集 AI 精简成 ≤1800 字 brief；false = 原文带入（内部 brief 通道，上限 20000）。 */
        Boolean condenseBrief,
        /** true（默认）= 立即调导演生成分镜动作 + 正/负提示词。 */
        Boolean runDirector,
        /**
         * 这集已经转过项目时：false（默认）= 在**同一个项目**里出**新版本（V+1）**；
         * true = 不管历史，另建一个新项目（接口保留，界面不暴露）。
         */
        Boolean newProject
) {
    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? "video" : mode;
    }

    public boolean condense() {
        return condenseBrief == null || condenseBrief;
    }

    public boolean director() {
        return runDirector == null || runDirector;
    }

    public boolean newProjectOrFalse() {
        return newProject != null && newProject;
    }
}
