package studio.weaveora.director.domain;

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

/** 某一版 AI/用户方案（§14 prompt_revisions）。schema_json 存 §10.2 完整导演方案，另冗余常用字段供列表。 */
@Entity
@Table(name = "prompt_revisions",
        indexes = @Index(name = "idx_revisions_project", columnList = "project_id, revision_no"))
public class PromptRevision {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Column(nullable = false)
    private String source;          // llm | stub | user

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false)
    private JsonNode schemaJson;

    @Column
    private String title;

    @Column
    private String logline;

    @Column(name = "positive_prompt")
    private String positivePrompt;

    @Column(name = "negative_prompt")
    private String negativePrompt;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PromptRevision() {
    }

    public static PromptRevision create(UUID workspaceId, UUID projectId, UUID briefId, int revisionNo,
                                        String source, JsonNode schemaJson, UUID createdBy) {
        PromptRevision r = new PromptRevision();
        r.workspaceId = workspaceId;
        r.projectId = projectId;
        r.briefId = briefId;
        r.revisionNo = revisionNo;
        r.source = source;
        r.schemaJson = schemaJson == null ? JsonNodeFactory.instance.objectNode() : schemaJson;
        r.title = text(schemaJson, "title");
        r.logline = text(schemaJson, "logline");
        r.positivePrompt = text(schemaJson, "positive_prompt");
        r.negativePrompt = text(schemaJson, "negative_prompt");
        r.createdBy = createdBy;
        return r;
    }

    /** 用户编辑后回写（仅未确认版本；§7.4 另存为新 revision 由 generate 承担）。 */
    public void replacePlan(JsonNode schemaJson) {
        this.schemaJson = schemaJson;
        this.title = text(schemaJson, "title");
        this.logline = text(schemaJson, "logline");
        this.positivePrompt = text(schemaJson, "positive_prompt");
        this.negativePrompt = text(schemaJson, "negative_prompt");
    }

    /** 用户编辑回写后标记来源 user（§7.4 手改版）。 */
    public void setSource(String source) {
        this.source = source;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    public UUID id() { return id; }
    public UUID projectId() { return projectId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID briefId() { return briefId; }
    public int revisionNo() { return revisionNo; }
    public String source() { return source; }
    public JsonNode schemaJson() { return schemaJson; }
    public String title() { return title; }
    public String logline() { return logline; }
    public String positivePrompt() { return positivePrompt; }
    public String negativePrompt() { return negativePrompt; }
    public UUID createdBy() { return createdBy; }
    public OffsetDateTime createdAt() { return createdAt; }
}
