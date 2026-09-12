package studio.weaveora.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.domain.ShotLock;
import studio.weaveora.project.domain.ShotLockRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分镜封版（V10）。
 *
 * <p>语义：某镜的资源（关键帧 / motion / 配音）已达到要求 → 打上「封版」。
 * 之后**批量**生成默认跳过它（单个镜头的显式生成不受限制，见 {@code JobService.resolveVideoShots}），
 * 避免重跑时把满意的镜头再生成一遍（既费钱又会把好素材顶掉）。
 */
@Service
public class ShotLockService {

    private static final Logger log = LoggerFactory.getLogger(ShotLockService.class);
    /** 单次最多操作多少镜（防误传超大数组） */
    private static final int MAX_BATCH = 200;

    private final ShotLockRepository locks;
    private final ProjectContextPort projects;

    public ShotLockService(ShotLockRepository locks, ProjectContextPort projects) {
        this.locks = locks;
        this.projects = projects;
    }

    /** 已封版镜号（升序）。 */
    @Transactional(readOnly = true)
    public List<Integer> lockedShotNos(UUID userId, UUID workspaceId, UUID projectId) {
        projects.require(userId, workspaceId, projectId);
        return locks.findByProjectIdAndWorkspaceIdOrderByShotNoAsc(projectId, workspaceId).stream()
                .map(ShotLock::shotNo)
                .toList();
    }

    /** 内部用（任务创建时判断是否需要跳过）：不做权限校验，调用方已校验。 */
    @Transactional(readOnly = true)
    public Set<Integer> lockedShotNosRaw(UUID workspaceId, UUID projectId) {
        return new LinkedHashSet<>(locks.findByProjectIdAndWorkspaceIdOrderByShotNoAsc(projectId, workspaceId)
                .stream().map(ShotLock::shotNo).toList());
    }

    /**
     * 批量设置/取消封版。
     *
     * @param shotNos 目标镜号（空 = 不改动，返回当前列表）
     * @param locked  true=封版，false=取消封版
     * @return 操作后的完整封版镜号列表
     */
    @Transactional
    public List<Integer> setLocked(UUID userId, UUID workspaceId, UUID projectId,
                                  Collection<Integer> shotNos, boolean locked, String note) {
        projects.require(userId, workspaceId, projectId);
        if (shotNos == null || shotNos.isEmpty()) {
            return lockedShotNos(userId, workspaceId, projectId);
        }
        if (shotNos.size() > MAX_BATCH) {
            throw new BizException(ErrorCode.VALIDATION, "一次最多操作 " + MAX_BATCH + " 个分镜");
        }
        Set<Integer> targets = new LinkedHashSet<>();
        for (Integer no : shotNos) {
            if (no == null || no < 1) {
                throw new BizException(ErrorCode.VALIDATION, "分镜号非法：" + no);
            }
            targets.add(no);
        }
        List<ShotLock> existing = locks.findByProjectIdAndWorkspaceIdAndShotNoIn(projectId, workspaceId, targets);
        Set<Integer> already = new LinkedHashSet<>(existing.stream().map(ShotLock::shotNo).toList());
        if (locked) {
            for (Integer no : targets) {
                if (already.contains(no)) {
                    continue;
                }
                locks.save(ShotLock.create(workspaceId, projectId, no, userId, note));
            }
        } else if (!existing.isEmpty()) {
            locks.deleteAll(existing);
        }
        log.info("shot-locks project={} locked={} targets={} -> {}", projectId, locked, targets,
                lockedShotNos(userId, workspaceId, projectId));
        return lockedShotNos(userId, workspaceId, projectId);
    }
}
