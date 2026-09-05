package studio.weaveora.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByIdAndDeletedAtIsNull(UUID id);

    List<Workspace> findByOwnerUserIdAndDeletedAtIsNull(UUID ownerUserId);
}
