package studio.weaveora.job.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductionStageApprovalRepository extends JpaRepository<ProductionStageApproval, UUID> {

    List<ProductionStageApproval> findByProjectIdAndRevisionIdAndWorkspaceId(
            UUID projectId, UUID revisionId, UUID workspaceId);

    Optional<ProductionStageApproval> findByProjectIdAndRevisionIdAndStageAndWorkspaceId(
            UUID projectId, UUID revisionId, String stage, UUID workspaceId);
}
