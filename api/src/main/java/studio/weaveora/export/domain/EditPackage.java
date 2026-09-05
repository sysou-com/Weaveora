package studio.weaveora.export.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 导出包（§14 edit_packages）：zip 由 StoragePort 存，edit_list 冗余便于审计。 */
@Entity
@Table(name = "edit_packages",
        indexes = @Index(name = "idx_editpkg_project", columnList = "project_id, created_at DESC"))
public class EditPackage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edit_list", nullable = false)
    private JsonNode editList;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EditPackage() {
    }

    public static EditPackage create(UUID workspaceId, UUID projectId, UUID revisionId,
                                     String storageKey, JsonNode editList, UUID createdBy) {
        EditPackage p = new EditPackage();
        p.workspaceId = workspaceId;
        p.projectId = projectId;
        p.revisionId = revisionId;
        p.storageKey = storageKey;
        p.editList = editList;
        p.createdBy = createdBy;
        return p;
    }

    public UUID id() { return id; }
    public UUID projectId() { return projectId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID revisionId() { return revisionId; }
    public String storageKey() { return storageKey; }
    public JsonNode editList() { return editList; }
    public UUID createdBy() { return createdBy; }
    public OffsetDateTime createdAt() { return createdAt; }
}
