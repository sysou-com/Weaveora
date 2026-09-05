package studio.weaveora.director;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.director.domain.PromptRevision;
import studio.weaveora.director.domain.PromptRevisionRepository;
import studio.weaveora.director.domain.ShotDraft;
import studio.weaveora.director.domain.ShotDraftRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.UUID;

/** director 产物只读桥（job/export 模块生成时读取 revision 方案与镜头，避免跨模块抓 Repo）。 */
@Component
public class PlanReader {

    private final PromptRevisionRepository revisions;
    private final ShotDraftRepository shots;

    public PlanReader(PromptRevisionRepository revisions, ShotDraftRepository shots) {
        this.revisions = revisions;
        this.shots = shots;
    }

    @Transactional(readOnly = true)
    public JsonNode revisionPlan(UUID revisionId) {
        return revisions.findById(revisionId)
                .map(PromptRevision::schemaJson)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "方案不存在"));
    }

    @Transactional(readOnly = true)
    public List<UUID> shotIds(UUID revisionId) {
        return shots.findByRevisionIdOrderByShotNo(revisionId).stream().map(ShotDraft::id).toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> approvedShotIds(UUID revisionId) {
        return shots.findByRevisionIdOrderByShotNo(revisionId).stream()
                .filter(s -> "approved".equals(s.status()))
                .map(ShotDraft::id)
                .toList();
    }

    @Transactional(readOnly = true)
    public int shotNoOf(UUID shotId) {
        return shots.findById(shotId)
                .map(ShotDraft::shotNo)
                .orElse(0);
    }
}
