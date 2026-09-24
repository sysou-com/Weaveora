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

    /** 引擎路由：gpu（自有 GPU/Comfy 池）| cloud（用户云 API）。claim 按节点能力匹配。 */
    @Column(name = "engine_route", nullable = false)
    private String engineRoute = "gpu";

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

    /**
     * 请求取消（2026-09-22）：**只置标志**，worker 靠它中断 ComfyUI 里正在跑的 prompt。
     *
     * <p>为什么必需：取消如果只改 state，worker 完全不知情，会继续等 ComfyUI 跑完（lipsync 一段 1–2 分钟），
     * 而 ComfyUI 是串行的 ⇒ 僵尸 prompt 把后面的任务全堵死（表现为「取消了但 GPU 还在跑」「新任务一直 queued」）。
     * 仓储里本来就有 `requestCancel` 的 @Modifying 查询，但**从未被调用** ⇒ 从 2026-09-16 引入到今天，
     * 取消/标失败实际上从来没有真正停下来过。改在实体上置位，与同事务的 save 一起落库。
     */
    public void requestCancel() {
        this.cancelRequested = true;
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

    /**
     * ★ 2026-09-24（用户要求「双击任务看给模型的完整提示词」）：把 worker 报回的
     * “**真正下发给模型**的提示词”与引擎参数追加进 {@code payload}。
     *
     * <p>为什么追加在 payload 而不是新列：{@code payload} 已是 jsonb，前端 JobRecord 也已下发它 ——
     * 不用改表（也就不会碰 Flyway/实体双写纪律），字段为可选、旧任务缺字段时前端自然回退显示
     * API 侧那份 {@code positive_prompt}。
     *
     * <p>为什么必须由 worker 报：API 侧只知道自己拼的那份文本；在它之后 worker 还会做
     * 改写（原 Qwen 口径 → 模型口径）、加“怎么用参考图”前缀、把负词折成正向句
     * （见 {@code comfy_client._image_edit_prompt}）——只看 API 那份会误判“到底喂了什么给模型”。
     */
    public void applyPromptReport(String finalPrompt, String finalNegative, JsonNode engineParams) {
        boolean hasPrompt = finalPrompt != null && !finalPrompt.isBlank();
        boolean hasNeg = finalNegative != null && !finalNegative.isBlank();
        if (!hasPrompt && !hasNeg && engineParams == null) {
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode o = (payload != null && payload.isObject())
                ? ((com.fasterxml.jackson.databind.node.ObjectNode) payload).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        if (hasPrompt) {
            o.put("finalPrompt", finalPrompt);
        }
        if (hasNeg) {
            o.put("finalNegative", finalNegative);
        }
        if (engineParams != null) {
            o.set("engineParams", engineParams);
        }
        this.payload = o;
    }
    public int progress() { return progress; }
    public String stage() { return stage; }
    public boolean cancelRequested() { return cancelRequested; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public String workerId() { return workerId; }
    public UUID createdBy() { return createdBy; }

    public String engineRoute() {
        return engineRoute == null ? "gpu" : engineRoute;
    }

    public void setEngineRoute(String route) {
        this.engineRoute = "cloud".equals(route) ? "cloud" : "gpu";
    }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime startedAt() { return startedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }
}
