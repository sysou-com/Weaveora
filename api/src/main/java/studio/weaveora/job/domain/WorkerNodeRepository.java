package studio.weaveora.job.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerNodeRepository extends JpaRepository<WorkerNode, UUID> {

    Optional<WorkerNode> findByName(String name);

    List<WorkerNode> findByLastSeenAtAfter(OffsetDateTime since);
}
