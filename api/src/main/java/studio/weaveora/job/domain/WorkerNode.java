package studio.weaveora.job.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Worker 节点（§19 双轨注册；workspace_id NULL = 我方节点池，可服务多工作区）。 */
@Entity
@Table(name = "worker_nodes")
public class WorkerNode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;         // NULL = 节点池（轨1）；非空 = BYO（轨2 只接本工作区）

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode capabilities = JsonNodeFactory.instance.objectNode();

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WorkerNode() {
    }

    public static WorkerNode register(String name, UUID workspaceId, JsonNode capabilities) {
        WorkerNode n = new WorkerNode();
        n.name = name;
        n.workspaceId = workspaceId;
        n.capabilities = capabilities == null ? JsonNodeFactory.instance.objectNode() : capabilities;
        return n;
    }

    public void heartbeat() {
        this.lastSeenAt = OffsetDateTime.now();
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public String name() { return name; }
    public JsonNode capabilities() { return capabilities; }
    public OffsetDateTime lastSeenAt() { return lastSeenAt; }
    public OffsetDateTime createdAt() { return createdAt; }
}
