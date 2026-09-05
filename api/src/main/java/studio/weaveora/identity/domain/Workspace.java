package studio.weaveora.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 工作区（§14 workspaces 表）——计费与隔离边界（§7.1）。 */
@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(nullable = false)
    private String plan = "free";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Workspace() {
    }

    public static Workspace create(String name, UUID ownerUserId) {
        Workspace w = new Workspace();
        w.name = name;
        w.ownerUserId = ownerUserId;
        w.plan = "free";
        return w;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public UUID ownerUserId() { return ownerUserId; }
    public String plan() { return plan; }
}
