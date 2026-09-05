package studio.weaveora.export.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EditPackageRepository extends JpaRepository<EditPackage, UUID> {

    List<EditPackage> findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(UUID projectId, UUID workspaceId);

    Optional<EditPackage> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
