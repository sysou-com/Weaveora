package studio.weaveora.project;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.project.api.BriefResponse;
import studio.weaveora.project.api.CreateBriefRequest;
import studio.weaveora.project.api.CreateProjectRequest;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectMapper;
import studio.weaveora.project.api.ProjectResponse;
import studio.weaveora.project.domain.Brief;
import studio.weaveora.project.domain.BriefRepository;
import studio.weaveora.project.domain.Project;
import studio.weaveora.project.domain.ProjectRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 项目与 Brief（§17.2 / §28.1-3），全部带 workspace_id + membership 校验。
 *  同时实现 ProjectContextPort，供 director/job 模块经 *.api 端口推进状态机（§16.2/§16.3）。 */
@Service
public class ProjectService implements ProjectContextPort {

    private static final List<String> ALLOWED_MODES = List.of("image", "video", "mixed");
    private static final List<String> ALLOWED_RATIOS = List.of("1:1", "3:2", "2:3", "16:9", "9:16");
    private static final List<String> BRIEF_MODES = List.of("image", "video", "auto");

    private final ProjectRepository projects;
    private final BriefRepository briefs;
    private final WorkspaceGuard guard;

    public ProjectService(ProjectRepository projects, BriefRepository briefs, WorkspaceGuard guard) {
        this.projects = projects;
        this.briefs = briefs;
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

    @Transactional
    public BriefResponse createBrief(UUID userId, UUID workspaceId, UUID projectId, CreateBriefRequest req) {
        guard.requireMember(userId, workspaceId);
        Project project = findInWorkspace(workspaceId, projectId); // 项目须在本工作区
        String raw = req.rawText().trim();
        if (raw.length() < 10) {
            throw new BizException(ErrorCode.BRIEF_TOO_SHORT, "Brief 至少 10 字（§7.2）");
        }
        String mode = (req.mode() == null || req.mode().isBlank()) ? project.mode() : req.mode();
        if (!BRIEF_MODES.contains(mode)) {
            throw new BizException(ErrorCode.VALIDATION, "brief.mode 必须为 image|video|auto");
        }
        com.fasterxml.jackson.databind.JsonNode constraints = mergeConstraints(req.constraints(), req.referenceAssetIds());
        Brief b = Brief.create(workspaceId, projectId, raw, mode, constraints);
        return toBriefResponse(briefs.save(b));
    }

    /** constraints（用户 JSON）+ referenceAssetIds（≤4，去重）合并进 "referenceAssetIds"。 */
    private static com.fasterxml.jackson.databind.JsonNode mergeConstraints(
            com.fasterxml.jackson.databind.JsonNode user, java.util.List<UUID> refIds) {
        com.fasterxml.jackson.databind.node.ObjectNode out = user != null && user.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) user.deepCopy()
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (refIds != null && !refIds.isEmpty()) {
            java.util.List<UUID> distinct = refIds.stream().distinct().toList();
            if (distinct.size() > 4) {
                throw new BizException(ErrorCode.VALIDATION, "参考图最多 4 张（§7.2）");
            }
            var arr = out.putArray("referenceAssetIds");
            distinct.forEach(u -> arr.add(u.toString()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<BriefResponse> listBriefs(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        findInWorkspace(workspaceId, projectId);
        return briefs.findByProjectIdAndWorkspaceIdOrderByCreatedAtDesc(projectId, workspaceId).stream()
                .map(this::toBriefResponse)
                .toList();
    }

    // ---------- ProjectContextPort（director 等模块使用） ----------

    @Override
    @Transactional(readOnly = true)
    public ProjectSnapshot require(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        Project p = findInWorkspace(workspaceId, projectId);
        return new ProjectSnapshot(p.id(), p.mode(), p.aspectRatio(), p.durationSec(),
                p.status(), p.approvedRevisionId());
    }

    @Override
    @Transactional(readOnly = true)
    public BriefSnapshot requireBrief(UUID userId, UUID workspaceId, UUID projectId, UUID briefId) {
        guard.requireMember(userId, workspaceId);
        // 项目须在本工作区（防跨项目撞 brief id）
        findInWorkspace(workspaceId, projectId);
        Brief b = briefs.findByIdAndProjectIdAndWorkspaceId(briefId, projectId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Brief 不存在或不属于该项目"));
        return new BriefSnapshot(b.id(), b.rawText(), b.mode(), b.constraints());
    }

    @Override
    @Transactional
    public void markDirecting(UUID workspaceId, UUID projectId) {
        Project p = findInWorkspace(workspaceId, projectId);
        p.startDirecting();
        projects.save(p);
    }

    @Override
    @Transactional
    public void markApproved(UUID workspaceId, UUID projectId, UUID revisionId) {
        Project p = findInWorkspace(workspaceId, projectId);
        p.approve(revisionId);
        projects.save(p);
    }

    private Brief findInProject(UUID workspaceId, UUID projectId, UUID briefId) {
        return briefs.findByIdAndProjectIdAndWorkspaceId(briefId, projectId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Brief 不存在或不属于该项目"));
    }

    private Project findInWorkspace(UUID workspaceId, UUID projectId) {
        return projects.findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "项目不存在或不在本工作区"));
    }

    private BriefResponse toBriefResponse(Brief b) {
        return new BriefResponse(b.id(), b.projectId(), b.rawText(), b.mode(), b.constraints(), b.createdAt());
    }
}
