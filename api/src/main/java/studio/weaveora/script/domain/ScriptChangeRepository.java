package studio.weaveora.script.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScriptChangeRepository extends JpaRepository<ScriptChange, UUID> {

    List<ScriptChange> findByScriptIdOrderByCreatedAtDesc(UUID scriptId);

    void deleteByScriptId(UUID scriptId);
}
