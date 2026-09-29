package studio.weaveora.job.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 制作流程「阶段确认」（V20 / P0-P1，2026-09-29）。
 *
 * <p>只记录**用户的确认动作**（谁 / 何时 / 哪一版方案 / 哪个阶段），用于满足 §0-3 的确认闸门；
 * 阶段「跑完没有」由 {@code generation_jobs} + {@code assets} 派生，不在这里存进度。
 */
@Entity
@Table(name = "production_stage_approvals",
        uniqueConstraints = @UniqueConstraint(name = "uq_stage_appr",
                columnNames = {"project_id", "revision_id", "stage"}))
public class ProductionStageApproval {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(nullable = false)
    private String stage;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @CreationTimestamp
    @Column(name = "approved_at", nullable = false, updatable = false)
    private OffsetDateTime approvedAt;

    @Column
    private String note;

    protected ProductionStageApproval() {
    }

    public static ProductionStageApproval create(UUID workspaceId, UUID projectId, UUID revisionId,
                                                 String stage, UUID approvedBy, String note) {
        ProductionStageApproval a = new ProductionStageApproval();
        a.workspaceId = workspaceId;
        a.projectId = projectId;
        a.revisionId = revisionId;
        a.stage = stage;
        a.approvedBy = approvedBy;
        a.note = note;
        return a;
    }

    public void touch(UUID approvedBy, String note) {
        this.approvedBy = approvedBy;
        this.note = note;
        this.approvedAt = OffsetDateTime.now();
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID revisionId() {
        return revisionId;
    }

    public String stage() {
        return stage;
    }

    public UUID approvedBy() {
        return approvedBy;
    }

    public OffsetDateTime approvedAt() {
        return approvedAt;
    }

    public String note() {
        return note;
    }
}
