package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);

    Optional<Project> findByWorkspaceIdAndIdAndDeletedAtIsNull(UUID workspaceId, UUID id);
}
