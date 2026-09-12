package studio.weaveora.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 分镜封版（V10）：某镜「资源已满足要求」，后续批量生成默认跳过它。
 *
 * <p>按 {@code (project_id, shot_no)} 唯一 —— {@code shot_drafts} 每次 patch/approve 都会
 * delete+recreate（行 id 漂移），只有 {@code shot_no} 跨版本稳定，所以封版必须挂在镜号上。
 */
@Entity
@Table(name = "shot_locks")
public class ShotLock {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_no", nullable = false)
    private Integer shotNo;

    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ShotLock() {
    }

    public static ShotLock create(UUID workspaceId, UUID projectId, Integer shotNo, UUID createdBy, String note) {
        ShotLock l = new ShotLock();
        l.workspaceId = workspaceId;
        l.projectId = projectId;
        l.shotNo = shotNo;
        l.createdBy = createdBy;
        l.note = note;
        return l;
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public UUID projectId() { return projectId; }
    public Integer shotNo() { return shotNo; }
    public String note() { return note; }
    public UUID createdBy() { return createdBy; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }

    public void setNote(String note) { this.note = note; }
}
