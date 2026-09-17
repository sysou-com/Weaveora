package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 粗需求（§14 briefs）。 */
public interface BriefRepository extends JpaRepository<Brief, UUID> {

    Optional<Brief> findByIdAndProjectIdAndWorkspaceId(UUID id, UUID projectId, UUID workspaceId);

    List<Brief> findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(UUID projectId, UUID workspaceId);

    /**
     * 按「剧本集」反查 brief —— 「剧本 → 转成项目」时会把 {@code scriptEpisodeId} 写进
     * {@code constraints}，这张表就是**集与项目的唯一关联**。
     *
     * <p>用途：① 前端在分集行上显示「查看项目 V2」（这集已经出过项目）；
     * ② 再次点「转成项目」时沿用**同一个项目**生成新版本（V+1），而不是又建一个新项目。
     */
    @Query(value = "select * from briefs where workspace_id = :workspaceId "
            + "and constraints->>'scriptEpisodeId' in (:episodeIds) order by created_at desc",
            nativeQuery = true)
    List<Brief> findByScriptEpisodeIds(@Param("workspaceId") UUID workspaceId,
                                       @Param("episodeIds") Collection<String> episodeIds);
}
