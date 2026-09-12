package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotLockRepository extends JpaRepository<ShotLock, UUID> {

    List<ShotLock> findByProjectIdAndWorkspaceIdOrderByShotNoAsc(UUID projectId, UUID workspaceId);

    Optional<ShotLock> findByProjectIdAndWorkspaceIdAndShotNo(UUID projectId, UUID workspaceId, Integer shotNo);

    List<ShotLock> findByProjectIdAndWorkspaceIdAndShotNoIn(UUID projectId, UUID workspaceId, Collection<Integer> shotNos);
}
