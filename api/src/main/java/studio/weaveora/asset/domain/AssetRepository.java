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

    /** P6：按项目 + 镜号（跨版本稳定）取产物 */
    List<Asset> findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
            UUID projectId, UUID workspaceId, Integer shotNo, String kind);

    /** 项目最新参考图（用户上传即作为参考，无需再手动关联 brief） */
    List<Asset> findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(UUID projectId, UUID workspaceId, String kind);

    /** 项目最新图片资产（still/参考图均可，供列表缩略/集市预览） */
    Asset findFirstByProjectIdAndKindInOrderByCreatedAtDesc(UUID projectId, List<String> kinds);
}
