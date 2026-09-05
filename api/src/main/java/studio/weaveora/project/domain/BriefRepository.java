package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 粗需求（§14 briefs）。 */
public interface BriefRepository extends JpaRepository<Brief, UUID> {

    Optional<Brief> findByIdAndProjectIdAndWorkspaceId(UUID id, UUID projectId, UUID workspaceId);

    List<Brief> findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(UUID projectId, UUID workspaceId);
}
