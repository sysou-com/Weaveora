package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MarketMarkRepository extends JpaRepository<MarketMark, MarketMark.Key> {

    List<MarketMark> findByProjectIdInAndKind(Collection<UUID> projectIds, String kind);

    boolean existsByProjectIdAndUserIdAndKind(UUID projectId, UUID userId, String kind);

    long countByProjectIdAndKind(UUID projectId, String kind);

    void deleteByProjectIdAndUserIdAndKind(UUID projectId, UUID userId, String kind);
}
