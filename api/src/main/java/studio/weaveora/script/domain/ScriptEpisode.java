package studio.weaveora.script.domain;

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

/** 剧本的一集 / 一章（V17 script_episodes 表）；{@code episodeNo} 在剧本内唯一。 */
@Entity
@Table(name = "script_episodes",
        indexes = @Index(name = "idx_script_episodes_script", columnList = "script_id, episode_no"))
public class ScriptEpisode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "episode_no", nullable = false)
    private int episodeNo;

    @Column(nullable = false)
    private String title = "";

    @Column(nullable = false)
    private String content = "";

    /** 本集摘要（供「精简的故事」与后续集生成使用；AI 生成或用户手改）。 */
    @Column(nullable = false)
    private String summary = "";

    @Column(name = "ai_polished", nullable = false)
    private boolean aiPolished = false;

    /** 本集的节拍提纲（V18）：该集照着哪份提纲写的，重开可见。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode outline = JsonNodeFactory.instance.arrayNode();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ScriptEpisode() {
    }

    public static ScriptEpisode create(UUID scriptId, UUID workspaceId, int episodeNo, String title,
                                       String content, String summary, boolean aiPolished) {
        ScriptEpisode e = new ScriptEpisode();
        e.scriptId = scriptId;
        e.workspaceId = workspaceId;
        e.episodeNo = episodeNo;
        e.title = title == null ? "" : title;
        e.content = content == null ? "" : content;
        e.summary = summary == null ? "" : summary;
        e.aiPolished = aiPolished;
        return e;
    }

    /** 局部更新：null = 不改。 */
    public void patch(String title, String content, String summary, Boolean aiPolished) {
        if (title != null) this.title = title;
        if (content != null) this.content = content;
        if (summary != null) this.summary = summary;
        if (aiPolished != null) this.aiPolished = aiPolished;
        this.updatedAt = OffsetDateTime.now();
    }

    public void renumber(int episodeNo) {
        this.episodeNo = episodeNo;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 写入本集提纲（空数组=不改）。 */
    public void setOutline(JsonNode outline) {
        if (outline != null && outline.isArray() && !outline.isEmpty()) {
            this.outline = outline;
            this.updatedAt = OffsetDateTime.now();
        }
    }

    public UUID id() { return id; }
    public UUID scriptId() { return scriptId; }
    public UUID workspaceId() { return workspaceId; }
    public int episodeNo() { return episodeNo; }
    public String title() { return title; }
    public String content() { return content; }
    public String summary() { return summary; }
    public boolean aiPolished() { return aiPolished; }
    public JsonNode outline() { return outline; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
}
