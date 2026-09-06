package studio.weaveora.project.domain;

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

/** 集市标记（V4）：like | fav，一用户×项目×kind 一行。 */
@Entity
@Table(name = "project_marks")
@IdClass(MarketMark.Key.class)
public class MarketMark {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    private String kind;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MarketMark() {
    }

    public static MarketMark create(UUID projectId, UUID userId, String kind) {
        MarketMark m = new MarketMark();
        m.projectId = projectId;
        m.userId = userId;
        m.kind = kind;
        return m;
    }

    public UUID projectId() { return projectId; }
    public UUID userId() { return userId; }
    public String kind() { return kind; }

    /** 复合主键 */
    public static class Key implements Serializable {
        public UUID projectId;
        public UUID userId;
        public String kind;

        public Key() {
        }

        public Key(UUID projectId, UUID userId, String kind) {
            this.projectId = projectId;
            this.userId = userId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(projectId, key.projectId)
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(kind, key.kind);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, userId, kind);
        }
    }
}
