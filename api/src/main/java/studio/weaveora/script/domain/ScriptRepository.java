package studio.weaveora.script.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptRepository extends JpaRepository<Script, UUID> {

    List<Script> findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);

    List<Script> findByShareStatusAndDeletedAtIsNull(String shareStatus);

    List<Script> findByShareStatusInAndDeletedAtIsNull(List<String> shareStatuses);

    Optional<Script> findByWorkspaceIdAndIdAndDeletedAtIsNull(UUID workspaceId, UUID id);
}
