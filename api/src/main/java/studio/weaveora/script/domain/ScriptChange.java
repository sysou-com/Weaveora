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

/**
 * 剧本变更记录（V17 script_changes 表）——「记录之前的章节哪些地方有改动」的落地。
 *
 * <p>{@code changedEpisodes} 形如 {@code [{"episodeNo":2,"title":"…","what":"衔接改写"}]}，
 * 由 AI 一致性检查产出、经用户确认后写入（Q4：先确认再改）。
 */
@Entity
@Table(name = "script_changes",
        indexes = @Index(name = "idx_script_changes_script", columnList = "script_id, created_at DESC"))
public class ScriptChange {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "episode_id")
    private UUID episodeId;

    @Column(name = "episode_no")
    private Integer episodeNo;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_episodes", nullable = false)
    private JsonNode changedEpisodes;

    @Column(nullable = false)
    private String note = "";

    @Column(nullable = false)
    private String actor = "user";      // user | ai

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ScriptChange() {
    }

    public static ScriptChange create(UUID scriptId, UUID workspaceId, UUID episodeId, Integer episodeNo,
                                      String kind, JsonNode changedEpisodes, String note, String actor) {
        ScriptChange c = new ScriptChange();
        c.scriptId = scriptId;
        c.workspaceId = workspaceId;
        c.episodeId = episodeId;
        c.episodeNo = episodeNo;
        c.kind = kind;
        c.changedEpisodes = changedEpisodes == null ? JsonNodeFactory.instance.arrayNode() : changedEpisodes;
        c.note = note == null ? "" : note;
        c.actor = actor == null ? "user" : actor;
        return c;
    }

    public UUID id() { return id; }
    public UUID scriptId() { return scriptId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID episodeId() { return episodeId; }
    public Integer episodeNo() { return episodeNo; }
    public String kind() { return kind; }
    public JsonNode changedEpisodes() { return changedEpisodes; }
    public String note() { return note; }
    public String actor() { return actor; }
    public OffsetDateTime createdAt() { return createdAt; }
}
