package studio.weaveora.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 工作区成员关系（§14 memberships 表）。 */
@Entity
@Table(name = "memberships")
public class Membership {

    public enum Role { OWNER, EDITOR, REVIEWER, VIEWER }

    @EmbeddedId
    private MembershipId membershipId;

    @Column(nullable = false)
    private String role = Role.VIEWER.name();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Membership() {
    }

    public static Membership create(UUID workspaceId, UUID userId, Role role) {
        Membership m = new Membership();
        m.membershipId = new MembershipId(workspaceId, userId);
        m.role = role.name();
        return m;
    }

    public UUID workspaceId() { return membershipId.workspaceId; }
    public UUID userId() { return membershipId.userId; }
    public Role role() { return Role.valueOf(role); }

    @Embeddable
    public static class MembershipId implements Serializable {
        @Column(name = "workspace_id")
        UUID workspaceId;
        @Column(name = "user_id")
        UUID userId;

        protected MembershipId() {
        }

        MembershipId(UUID workspaceId, UUID userId) {
            this.workspaceId = workspaceId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MembershipId that)) return false;
            return Objects.equals(workspaceId, that.workspaceId) && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workspaceId, userId);
        }
    }
}
