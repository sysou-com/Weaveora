package studio.weaveora.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 项目（§14 projects 表）——一次创作任务的容器。 */
@Entity
@Table(name = "projects",
        indexes = @Index(name = "idx_projects_ws", columnList = "workspace_id, created_at DESC"))
public class Project {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String mode = "image";        // image | video | mixed

    @Column(name = "aspect_ratio", nullable = false)
    private String aspectRatio = "16:9";

    @Column(name = "duration_sec", precision = 6, scale = 2)
    private BigDecimal durationSec;

    @Column(name = "style_template_id")
    private UUID styleTemplateId;

    @Column(nullable = false)
    private String status = "draft";       // §20.1 状态机

    @Column(name = "approved_revision_id")
    private UUID approvedRevisionId;

    /** 项目集市：null=未分享 | pending=待审 | approved=已上架 | rejected=驳回 */
    @Column(name = "share_status")
    private String shareStatus;

    @Column(name = "shared_at")
    private OffsetDateTime sharedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Project() {
    }

    public static Project create(UUID workspaceId, UUID createdBy, String title,
                                 String mode, String aspectRatio, BigDecimal durationSec) {
        Project p = new Project();
        p.workspaceId = workspaceId;
        p.createdBy = createdBy;
        p.title = title;
        p.mode = mode;
        p.aspectRatio = aspectRatio;
        p.durationSec = durationSec;
        p.status = "draft";
        return p;
    }

    public void rename(String title) {
        this.title = title;
        this.updatedAt = OffsetDateTime.now();
    }

    /** §20.1：产生新 revision → directing（draft/approved/reviewing 可进），并解除旧确认。 */
    public void startDirecting() {
        this.status = "directing";
        this.approvedRevisionId = null;
        this.updatedAt = OffsetDateTime.now();
    }

    /** §20.1：确认某版 → approved，钉住 approved_revision_id。 */
    public void approve(UUID revisionId) {
        this.status = "approved";
        this.approvedRevisionId = revisionId;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 提交分享（集市待审） */
    public void submitShare() {
        this.shareStatus = "pending";
        this.sharedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    /** 软删除 */
    public void markDeleted() {
        this.deletedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean deleted() {
        return this.deletedAt != null;
    }

    /** 管理审批：approved | rejected */
    public void reviewShare(boolean approved) {
        this.shareStatus = approved ? "approved" : "rejected";
        this.updatedAt = OffsetDateTime.now();
    }

    /** 已确认版本是否可编辑判定：directing/draft 且未钉版本。 */
    public boolean editable() {
        return this.approvedRevisionId == null;
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public UUID createdBy() { return createdBy; }
    public String title() { return title; }
    public String mode() { return mode; }
    public String aspectRatio() { return aspectRatio; }
    public BigDecimal durationSec() { return durationSec; }
    public UUID styleTemplateId() { return styleTemplateId; }
    public String status() { return status; }
    public UUID approvedRevisionId() { return approvedRevisionId; }
    public String shareStatus() { return shareStatus; }
    public OffsetDateTime sharedAt() { return sharedAt; }
    public OffsetDateTime deletedAt() { return deletedAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
}
