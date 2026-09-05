package studio.weaveora.project.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

/** 粗需求快照（§14 briefs 表）。每次录入不改写，revision 引用它。 */
@Entity
@Table(name = "briefs", indexes = @Index(name = "idx_briefs_project", columnList = "project_id"))
public class Brief {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @Column(nullable = false)
    private String mode; // image | video | auto（§7.2）

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode constraints;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Brief() {
    }

    public static Brief create(UUID workspaceId, UUID projectId, String rawText, String mode, JsonNode constraints) {
        Brief b = new Brief();
        b.workspaceId = workspaceId;
        b.projectId = projectId;
        b.rawText = rawText;
        b.mode = mode;
        b.constraints = (constraints == null || constraints.isNull()) ? JsonNodeFactory.instance.objectNode() : constraints;
        return b;
    }

    public UUID id() { return id; }
    public UUID projectId() { return projectId; }
    public UUID workspaceId() { return workspaceId; }
    public String rawText() { return rawText; }
    public String mode() { return mode; }
    public JsonNode constraints() { return constraints; }
    public OffsetDateTime createdAt() { return createdAt; }
}
