package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShareAssetRepository extends JpaRepository<ShareAsset, ShareAsset.Key> {

    List<ShareAsset> findByProjectId(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
