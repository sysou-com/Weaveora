package studio.weaveora.director.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/** 视频镜头行（§14 shot_drafts），随 revision 创建/替换。 */
@Entity
@Table(name = "shot_drafts",
        indexes = @Index(name = "idx_shots_revision", columnList = "revision_id, shot_no"))
public class ShotDraft {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "shot_no", nullable = false)
    private int shotNo;

    @Column(name = "duration_sec", nullable = false, precision = 6, scale = 2)
    private BigDecimal durationSec;

    @Column(name = "shot_size")
    private String shotSize;

    @Column(name = "camera_move")
    private String cameraMove;

    @Column
    private String action;

    @Column(name = "positive_prompt", nullable = false)
    private String positivePrompt;

    @Column(name = "negative_prompt", nullable = false)
    private String negativePrompt;

    @Column(name = "seed_lock", nullable = false)
    private boolean seedLock = true;

    @Column(name = "ref_shot_no")
    private Integer refShotNo;

    @Column(nullable = false)
    private String status = "draft";    // draft | approved | generating | done | failed

    protected ShotDraft() {
    }

    public static ShotDraft create(UUID revisionId, int shotNo, BigDecimal durationSec, String shotSize,
                                   String cameraMove, String action, String positivePrompt,
                                   String negativePrompt, boolean seedLock, Integer refShotNo) {
        ShotDraft s = new ShotDraft();
        s.revisionId = revisionId;
        s.shotNo = shotNo;
        s.durationSec = durationSec;
        s.shotSize = shotSize;
        s.cameraMove = cameraMove;
        s.action = action;
        s.positivePrompt = positivePrompt;
        s.negativePrompt = negativePrompt;
        s.seedLock = seedLock;
        s.refShotNo = refShotNo;
        s.status = "draft";
        return s;
    }

    public void approve() {
        this.status = "approved";
    }

    public void rejectToDraft() {
        this.status = "draft";
    }

    public UUID id() { return id; }
    public UUID revisionId() { return revisionId; }
    public int shotNo() { return shotNo; }
    public BigDecimal durationSec() { return durationSec; }
    public String shotSize() { return shotSize; }
    public String cameraMove() { return cameraMove; }
    public String action() { return action; }
    public String positivePrompt() { return positivePrompt; }
    public String negativePrompt() { return negativePrompt; }
    public boolean seedLock() { return seedLock; }
    public Integer refShotNo() { return refShotNo; }
    public String status() { return status; }
}
