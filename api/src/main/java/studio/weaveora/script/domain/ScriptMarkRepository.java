package studio.weaveora.script.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ScriptMarkRepository extends JpaRepository<ScriptMark, ScriptMark.Key> {

    List<ScriptMark> findByScriptIdInAndKind(Collection<UUID> scriptIds, String kind);

    boolean existsByScriptIdAndUserIdAndKind(UUID scriptId, UUID userId, String kind);

    long countByScriptIdAndKind(UUID scriptId, String kind);

    void deleteByScriptIdAndUserIdAndKind(UUID scriptId, UUID userId, String kind);

    void deleteByScriptId(UUID scriptId);
}
