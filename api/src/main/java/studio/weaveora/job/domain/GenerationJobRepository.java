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

    /** 候选：running 且开始时间早于 cut（是否真卡死由 service 结合 worker 心跳判断）。 */
    @Query("select j from GenerationJob j where j.state='running' and (j.startedAt is null or j.startedAt < :cut)")
    List<GenerationJob> findRunningStartedBefore(@Param("cut") java.time.OffsetDateTime cut);

    /**
     * 回收单个 running 任务为 failed。
     *
     * <p>为什么改用「按 id 回收」而不是一条批量 update：批量只比 startedAt，
     * 会把**还在正常跑的长任务**（对口型一个镜头实测 25–30min）误杀 —— 2026-09-13 线上实例：
     * worker 心跳一直在（25s 一次）、ComfyUI 已跑到 5/8，却在第 15 分钟被判
     * {@code WORKER_STUCK 执行超时（worker 无心跳完成）}。
     */
    @Modifying
    @Query("update GenerationJob j set j.state='failed', j.errorCode='WORKER_STUCK', " +
            "j.errorMessage=:msg, j.finishedAt=:now where j.id=:id and j.state='running'")
    int failRunning(@Param("id") UUID id, @Param("msg") String msg,
                    @Param("now") java.time.OffsetDateTime now);

    /** 排队中但已请求取消（历史遗留/异步取消）→ 直接落 cancelled 终态，避免僵尸行。 */
    @Modifying
    @Query("update GenerationJob j set j.state='cancelled', j.finishedAt=:now " +
            "where j.state='queued' and j.cancelRequested=true")
    int markCancelledQueued(@Param("now") java.time.OffsetDateTime now);

    /** 排队超时（长时间无可用 worker，如 GPU 长时间离线）→ failed(STALE_QUEUED)，可重试。 */
    @Modifying
    @Query("update GenerationJob j set j.state='failed', j.errorCode='STALE_QUEUED', j.errorMessage=:msg, " +
            "j.finishedAt=:now where j.state='queued' and j.cancelRequested=false and j.createdAt < :cut")
    int markStaleQueued(@Param("cut") java.time.OffsetDateTime cut,
                        @Param("now") java.time.OffsetDateTime now,
                        @Param("msg") String msg);

    long countByState(String state);

    List<GenerationJob> findByStateInOrderByCreatedAtAsc(List<String> states);
}
