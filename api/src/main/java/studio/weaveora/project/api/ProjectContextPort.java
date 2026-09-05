package studio.weaveora.project.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 供 director / job 等模块读取与推进项目状态的端口（模块边界：只经 *.api 类型交互，§16.2/§16.3）。 */
public interface ProjectContextPort {

    /** 项目只读快照（含 membership 校验；不存在/越权 → NOT_FOUND）。 */
    ProjectSnapshot require(UUID userId, UUID workspaceId, UUID projectId);

    /** Brief 快照（director 读取用；不存在/不属于该项目 → NOT_FOUND）。 */
    BriefSnapshot requireBrief(UUID userId, UUID workspaceId, UUID projectId, UUID briefId);

    /** LLM 产出新 revision：draft|approved|reviewing → directing，并清空 approved_revision_id。 */
    void markDirecting(UUID workspaceId, UUID projectId);

    /** 确认 revision：→ approved，钉住 approved_revision_id。 */
    void markApproved(UUID workspaceId, UUID projectId, UUID revisionId);

    /** 项目摘要（供 director 落库 brief 之外的元数据）。 */
    record ProjectSnapshot(
            UUID id,
            String mode,          // image | video | mixed
            String aspectRatio,   // 1:1 | 3:2 | 2:3 | 16:9 | 9:16
            BigDecimal durationSec,
            String status,        // §20.1
            UUID approvedRevisionId
    ) {
    }

    record BriefSnapshot(UUID id, String rawText, String mode, com.fasterxml.jackson.databind.JsonNode constraints) {
    }
}
