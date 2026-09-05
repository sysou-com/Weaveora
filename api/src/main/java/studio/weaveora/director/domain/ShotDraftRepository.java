package studio.weaveora.director.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotDraftRepository extends JpaRepository<ShotDraft, UUID> {

    List<ShotDraft> findByRevisionIdOrderByShotNo(UUID revisionId);

    Optional<ShotDraft> findByIdAndRevisionId(UUID id, UUID revisionId);

    /** 目标镜号最大参考（单镜重写/衔接用）。 */
    Optional<ShotDraft> findTopByRevisionIdOrderByShotNoDesc(UUID revisionId);
}
