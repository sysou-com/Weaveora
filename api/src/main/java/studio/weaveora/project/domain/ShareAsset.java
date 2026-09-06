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

/** 分享时选中的素材（V5）：集市只展示这些资产。 */
@Entity
@Table(name = "project_share_assets")
@IdClass(ShareAsset.Key.class)
public class ShareAsset {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Id
    @Column(name = "asset_id")
    private UUID assetId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ShareAsset() {
    }

    public static ShareAsset create(UUID projectId, UUID assetId) {
        ShareAsset s = new ShareAsset();
        s.projectId = projectId;
        s.assetId = assetId;
        return s;
    }

    public UUID projectId() { return projectId; }
    public UUID assetId() { return assetId; }

    public static class Key implements Serializable {
        public UUID projectId;
        public UUID assetId;

        public Key() {
        }

        public Key(UUID projectId, UUID assetId) {
            this.projectId = projectId;
            this.assetId = assetId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(projectId, k.projectId) && Objects.equals(assetId, k.assetId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, assetId);
        }
    }
}
