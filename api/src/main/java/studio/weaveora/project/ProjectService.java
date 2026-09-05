package studio.weaveora.project;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.project.api.CreateProjectRequest;
import studio.weaveora.project.api.ProjectMapper;
import studio.weaveora.project.api.ProjectResponse;
import studio.weaveora.project.domain.Project;
import studio.weaveora.project.domain.ProjectRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.UUID;

/** 项目 CRUD（§17.2 / §28.1-3），全部带 workspace_id + membership 校验。 */
@Service
public class ProjectService {

    private static final List<String> ALLOWED_MODES = List.of("image", "video", "mixed");
    private static final List<String> ALLOWED_RATIOS = List.of("1:1", "3:2", "2:3", "16:9", "9:16");

    private final ProjectRepository projects;
    private final WorkspaceGuard guard;

    public ProjectService(ProjectRepository projects, WorkspaceGuard guard) {
        this.projects = projects;
        this.guard = guard;
    }

    @Transactional
    public ProjectResponse create(UUID userId, UUID workspaceId, CreateProjectRequest req) {
        guard.requireMember(userId, workspaceId);
        String mode = (req.mode() == null || req.mode().isBlank()) ? "image" : req.mode();
        String ratio = (req.aspectRatio() == null || req.aspectRatio().isBlank()) ? "16:9" : req.aspectRatio();
        if (!ALLOWED_MODES.contains(mode)) {
            throw new BizException(ErrorCode.VALIDATION, "mode 必须为 image|video|mixed");
        }
        if (!ALLOWED_RATIOS.contains(ratio)) {
            throw new BizException(ErrorCode.VALIDATION, "aspectRatio 不合法");
        }
        Project p = Project.create(workspaceId, userId, req.title().trim(), mode, ratio, req.durationSec());
        return ProjectMapper.toResponse(projects.save(p));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId, UUID workspaceId) {
        guard.requireMember(userId, workspaceId);
        return projects.findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId).stream()
                .map(ProjectMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        return ProjectMapper.toResponse(findInWorkspace(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse rename(UUID userId, UUID workspaceId, UUID projectId, String title) {
        guard.requireMember(userId, workspaceId);
        Project p = findInWorkspace(workspaceId, projectId);
        p.rename(title.trim());
        return ProjectMapper.toResponse(projects.save(p));
    }

    private Project findInWorkspace(UUID workspaceId, UUID projectId) {
        return projects.findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "项目不存在或不在本工作区"));
    }
}
