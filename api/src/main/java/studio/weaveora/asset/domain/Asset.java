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

    public static Asset output(UUID workspaceId, UUID projectId, UUID jobId, UUID shotId, String kind,
                               String storageKey, String mime, Integer width, Integer height,
                               Long seed, Integer durationMs) {
        Asset a = new Asset();
        a.workspaceId = workspaceId;
        a.projectId = projectId;
        a.jobId = jobId;
        a.shotId = shotId;
        a.kind = kind;
        a.storageKey = storageKey;
        a.mime = mime;
        a.width = width;
        a.height = height;
        a.seed = seed;
        a.durationMs = durationMs;
        return a;
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public UUID projectId() { return projectId; }
    public UUID jobId() { return jobId; }
    public UUID shotId() { return shotId; }
    public String kind() { return kind; }
    public String storageKey() { return storageKey; }
    public String thumbKey() { return thumbKey; }
    public String mime() { return mime; }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public Integer durationMs() { return durationMs; }
    public Long seed() { return seed; }
    public boolean nsfw() { return nsfw; }
    public OffsetDateTime createdAt() { return createdAt; }
}
