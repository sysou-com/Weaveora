package studio.weaveora.asset.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(UUID projectId, UUID workspaceId);

    Optional<Asset> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<Asset> findByJobIdAndWorkspaceId(UUID jobId, UUID workspaceId);

    List<Asset> findByIdInAndWorkspaceId(List<UUID> ids, UUID workspaceId);

    List<Asset> findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(UUID shotId, UUID workspaceId, String kind);

    /** 项目最新图片资产（still/参考图均可，供列表缩略/集市预览） */
    Asset findFirstByProjectIdAndKindInOrderByCreatedAtDesc(UUID projectId, List<String> kinds);
}
