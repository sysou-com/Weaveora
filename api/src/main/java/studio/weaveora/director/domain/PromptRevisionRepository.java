package studio.weaveora.director.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromptRevisionRepository extends JpaRepository<PromptRevision, UUID> {

    List<PromptRevision> findByProjectIdAndWorkspaceIdOrderByRevisionNoDesc(UUID projectId, UUID workspaceId);

    Optional<PromptRevision> findByIdAndProjectIdAndWorkspaceId(UUID id, UUID projectId, UUID workspaceId);

    Optional<PromptRevision> findTopByProjectIdOrderByRevisionNoDesc(UUID projectId);
}
