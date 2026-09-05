package studio.weaveora.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 用户（§14 users 表；禁止拷贝 MirrorTalk User 实体的配额字段——硬性指令 15）。 */
@Entity
@Table(name = "users", indexes = @Index(name = "idx_users_email", columnList = "email"))
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(unique = true, columnDefinition = "citext")
    private String email;

    @Column(unique = true, columnDefinition = "citext")
    private String phone;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected User() {
    }

    public static User register(String email, String phone, String passwordHash, String displayName) {
        User u = new User();
        u.email = email;
        u.phone = phone;
        u.passwordHash = passwordHash;
        u.displayName = displayName;
        u.status = "active";
        return u;
    }

    public UUID id() { return id; }
    public String email() { return email; }
    public String phone() { return phone; }
    public String passwordHash() { return passwordHash; }
    public String displayName() { return displayName; }
    public String status() { return status; }
    public OffsetDateTime createdAt() { return createdAt; }

    public boolean isDisabled() {
        return !"active".equals(status);
    }
}
