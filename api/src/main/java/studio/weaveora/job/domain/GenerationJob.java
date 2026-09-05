package studio.weaveora.job.domain;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** GPU 任务（§14 generation_jobs；§20.2 状态机）。 */
@Entity
@Table(name = "generation_jobs",
        indexes = {@Index(name = "idx_jobs_state", columnList = "state, created_at"),
                @Index(name = "idx_jobs_ws", columnList = "workspace_id, created_at DESC")})
public class GenerationJob {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "revision_id")
    private UUID revisionId;

    @Column(name = "shot_id")
    private UUID shotId;

    @Column(name = "model_preset_id")
    private UUID modelPresetId;

    @Column(nullable = false)
    private String kind;              // still | clip

    @Column(nullable = false)
    private String state;             // queued | running | succeeded | failed | cancelled

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    @Column(nullable = false)
    private int progress = 0;

    @Column
    private String stage;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested = false;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "credits_reserved", nullable = false, precision = 12, scale = 4)
    private BigDecimal creditsReserved = BigDecimal.ZERO;

    @Column(name = "credits_settled", nullable = false, precision = 12, scale = 4)
    private BigDecimal creditsSettled = BigDecimal.ZERO;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected GenerationJob() {
    }

    public static GenerationJob create(UUID workspaceId, UUID projectId, UUID revisionId, UUID shotId,
                                       UUID modelPresetId, String kind, String idempotencyKey,
                                       JsonNode payload, UUID createdBy) {
        GenerationJob j = new GenerationJob();
        j.workspaceId = workspaceId;
        j.projectId = projectId;
        j.revisionId = revisionId;
        j.shotId = shotId;
        j.modelPresetId = modelPresetId;
        j.kind = kind;
        j.state = "queued";
        j.idempotencyKey = idempotencyKey;
        j.payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        j.createdBy = createdBy;
        return j;
    }

    public void markRunning(String workerId) {
        this.state = "running";
        this.workerId = workerId;
        this.startedAt = OffsetDateTime.now();
    }

    public void progress(int value, String stage) {
        this.progress = Math.max(0, Math.min(100, value));
        this.stage = stage;
    }

    public void cancel() {
        this.state = "cancelled";
        this.finishedAt = OffsetDateTime.now();
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void succeed() {
        this.state = "succeeded";
        this.progress = 100;
        this.finishedAt = OffsetDateTime.now();
    }

    public void fail(String code, String message) {
        this.state = "failed";
        this.errorCode = code;
        this.errorMessage = message == null ? null : limit(message, 1000);
        this.finishedAt = OffsetDateTime.now();
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID id() { return id; }
    public UUID projectId() { return projectId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID revisionId() { return revisionId; }
    public UUID shotId() { return shotId; }
    public UUID modelPresetId() { return modelPresetId; }
    public String kind() { return kind; }
    public String state() { return state; }
    public String idempotencyKey() { return idempotencyKey; }
    public JsonNode payload() { return payload; }
    public int progress() { return progress; }
    public String stage() { return stage; }
    public boolean cancelRequested() { return cancelRequested; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public String workerId() { return workerId; }
    public UUID createdBy() { return createdBy; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime startedAt() { return startedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }
}
