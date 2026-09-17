package studio.weaveora.director.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromptRevisionRepository extends JpaRepository<PromptRevision, UUID> {

    List<PromptRevision> findByProjectIdAndWorkspaceIdOrderByRevisionNoDesc(UUID projectId, UUID workspaceId);

    Optional<PromptRevision> findByIdAndProjectIdAndWorkspaceId(UUID id, UUID projectId, UUID workspaceId);

    Optional<PromptRevision> findTopByProjectIdOrderByRevisionNoDesc(UUID projectId);

    /** 批量取多个项目的所有版本（版本号倒序）——「查看项目 V2」这类展示用，避免逐项目查库。 */
    List<PromptRevision> findByProjectIdInOrderByRevisionNoDesc(Collection<UUID> projectIds);
}
