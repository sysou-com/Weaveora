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

    @Transactional(readOnly = true)
    public int revisionNo(UUID revisionId) {
        return revisions.findById(revisionId)
                .map(PromptRevision::revisionNo)
                .orElse(0);
    }

    /** 同一项目、同一镜号在所有 revision 下的 shot 行 id（motion 取关键帧时回溯历史版本用）。 */
    @Transactional(readOnly = true)
    public List<UUID> shotIdsByProjectAndShotNo(UUID projectId, UUID workspaceId, int shotNo) {
        List<UUID> out = new java.util.ArrayList<>();
        for (PromptRevision r : revisions.findByProjectIdAndWorkspaceIdOrderByRevisionNoDesc(projectId, workspaceId)) {
            for (ShotDraft d : shots.findByRevisionIdOrderByShotNo(r.id())) {
                if (d.shotNo() == shotNo) out.add(d.id());
            }
        }
        return out;
    }

    /** 某个 shot 行所属版本的版本号（0=未知）。 */
    @Transactional(readOnly = true)
    public int revisionNoOfShot(UUID shotId) {
        try {
            return shots.findById(shotId)
                    .flatMap(d -> revisions.findById(d.revisionId()))
                    .map(PromptRevision::revisionNo)
                    .orElse(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Transactional(readOnly = true)
    public UUID revisionBriefId(UUID revisionId) {
        return revisions.findById(revisionId)
                .map(PromptRevision::briefId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "方案不存在"));
    }
}
