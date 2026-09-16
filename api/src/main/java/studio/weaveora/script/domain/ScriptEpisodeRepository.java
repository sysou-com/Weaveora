package studio.weaveora.script.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptEpisodeRepository extends JpaRepository<ScriptEpisode, UUID> {

    List<ScriptEpisode> findByScriptIdOrderByEpisodeNoAsc(UUID scriptId);

    List<ScriptEpisode> findByScriptIdInOrderByScriptIdAscEpisodeNoAsc(java.util.Collection<UUID> scriptIds);

    Optional<ScriptEpisode> findByIdAndScriptId(UUID id, UUID scriptId);

    Optional<ScriptEpisode> findTopByScriptIdOrderByEpisodeNoDesc(UUID scriptId);

    long countByScriptId(UUID scriptId);

    void deleteByScriptId(UUID scriptId);
}
