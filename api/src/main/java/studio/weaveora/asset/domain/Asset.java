package studio.weaveora.asset.domain;

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

/** 资产（§14 assets 表）：参考图 / Job 产物。kind: reference | still | clip 。 */
@Entity
@Table(name = "assets", indexes = @Index(name = "idx_assets_project", columnList = "project_id, created_at DESC"))
public class Asset {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "shot_id")
    private UUID shotId;

    /** P6 冗余镜号：shot_drafts 重建后仍可按 (project, shot_no, kind) 找到素材 */
    @Column(name = "shot_no")
    private Integer shotNo;

    @Column(nullable = false)
    private String kind;              // reference | still | clip

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "thumb_key")
    private String thumbKey;

    @Column(nullable = false)
    private String mime;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column
    private Long seed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_snapshot")
    private com.fasterxml.jackson.databind.JsonNode promptSnapshot;

    @Column(nullable = false)
    private boolean nsfw = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Asset() {
    }

    public static Asset reference(UUID workspaceId, UUID projectId, String storageKey, String mime,
                                  Integer width, Integer height) {
        Asset a = new Asset();
        a.workspaceId = workspaceId;
        a.projectId = projectId;
        a.kind = "reference";
        a.storageKey = storageKey;
        a.mime = mime;
        a.width = width;
        a.height = height;
        return a;
    }

    public static Asset output(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, Integer shotNo,
                               String kind, String storageKey, String mime, Integer width, Integer height,
                               Long seed, Integer durationMs) {
        return output(workspaceId, projectId, jobId, shotId, shotNo, kind, storageKey, mime,
                width, height, seed, durationMs, null);
    }

    /**
     * 与上一个重载相同，但额外落 {@code prompt_snapshot}（产生这个资产的 job payload）。
     *
     * <p>P8 用它承载「这段配音在镜内的位置」：{@code at_sec} / {@code line_index} / {@code subject} / {@code kind}。
     * 资产完成顺序不确定（多任务并发），不能靠 createdAt 推断顺序，必须读这个快照。
     */
    public static Asset output(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, Integer shotNo,
                               String kind, String storageKey, String mime, Integer width, Integer height,
                               Long seed, Integer durationMs, com.fasterxml.jackson.databind.JsonNode promptSnapshot) {
        Asset a = new Asset();
        a.workspaceId = workspaceId;
        a.projectId = projectId;
        a.jobId = jobId;
        a.shotId = shotId;
        a.shotNo = shotNo;
        a.kind = kind;
        a.storageKey = storageKey;
        a.mime = mime;
        a.width = width;
        a.height = height;
        a.seed = seed;
        a.durationMs = durationMs;
        a.promptSnapshot = promptSnapshot;
        return a;
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public UUID projectId() { return projectId; }
    public UUID jobId() { return jobId; }
    public UUID shotId() { return shotId; }
    public Integer shotNo() { return shotNo; }
    public String kind() { return kind; }
    public String storageKey() { return storageKey; }
    public String thumbKey() { return thumbKey; }
    public String mime() { return mime; }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public Integer durationMs() { return durationMs; }
    public Long seed() { return seed; }
    /** 产生该资产的 job payload 快照（P8 配音靠它取 at_sec / line_index）；老数据可能为 null。 */
    public com.fasterxml.jackson.databind.JsonNode promptSnapshot() { return promptSnapshot; }
    public boolean nsfw() { return nsfw; }
    public OffsetDateTime createdAt() { return createdAt; }
}
