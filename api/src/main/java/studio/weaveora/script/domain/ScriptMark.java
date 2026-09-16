package studio.weaveora.script.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 剧本集市点赞/收藏（V17 script_marks）：一用户×剧本×kind 一行。 */
@Entity
@Table(name = "script_marks")
@IdClass(ScriptMark.Key.class)
public class ScriptMark {

    @Id
    @Column(name = "script_id")
    private UUID scriptId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    private String kind;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ScriptMark() {
    }

    public static ScriptMark create(UUID scriptId, UUID userId, String kind) {
        ScriptMark m = new ScriptMark();
        m.scriptId = scriptId;
        m.userId = userId;
        m.kind = kind;
        return m;
    }

    public UUID scriptId() { return scriptId; }
    public UUID userId() { return userId; }
    public String kind() { return kind; }

    /** 复合主键 */
    public static class Key implements Serializable {
        public UUID scriptId;
        public UUID userId;
        public String kind;

        public Key() {
        }

        public Key(UUID scriptId, UUID userId, String kind) {
            this.scriptId = scriptId;
            this.userId = userId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(scriptId, key.scriptId)
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(kind, key.kind);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scriptId, userId, kind);
        }
    }
}
