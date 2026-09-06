package studio.weaveora.job.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Optional<GenerationJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<GenerationJob> findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(UUID projectId, UUID workspaceId);

    Optional<GenerationJob> findByProjectIdAndShotIdAndKindAndStateNotIn(
            UUID projectId, UUID shotId, String kind, List<String> terminalStates);

    /** 幂等重放：同 key 已存在则返回既有任务。 */
    Optional<GenerationJob> findByIdempotencyKey(String idempotencyKey);

    /** 单机安全认领：CAS（state=queued → running），返回受影响行数；1 表示认领成功。 */
    @Modifying
    @Query("update GenerationJob j set j.state='running', j.workerId=:workerId, j.startedAt=:now " +
            "where j.id=:id and j.state='queued' and j.cancelRequested=false")
    int claim(@Param("id") UUID id, @Param("workerId") String workerId, @Param("now") OffsetDateTime now);

    /** 列表查询（按创建先后，配合认领 CAS 单实例即可无重复分发）。 */
    @Query("select j from GenerationJob j where j.state='queued' and j.cancelRequested=false " +
            "order by j.createdAt asc")
    List<GenerationJob> findQueuedForClaim();

    @Modifying
    @Query("update GenerationJob j set j.cancelRequested=true where j.id=:id and j.state in ('queued','running')")
    int requestCancel(@Param("id") UUID id);

    /** 回收卡死任务：running 超过阈值未完成 → failed(WORKER_STUCK)，可由用户重试 */
    @Modifying
    @Query("update GenerationJob j set j.state='failed', j.errorCode='WORKER_STUCK', " +
            "j.errorMessage='执行超时（worker 无心跳完成）', j.finishedAt=:now " +
            "where j.state='running' and (j.startedAt is null or j.startedAt < :cut)")
    int markStaleRunning(@Param("cut") java.time.OffsetDateTime cut,
                         @Param("now") java.time.OffsetDateTime now);

    long countByState(String state);
}
