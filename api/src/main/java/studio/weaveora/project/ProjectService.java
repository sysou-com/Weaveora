package studio.weaveora.project;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.project.api.BriefResponse;
import studio.weaveora.project.api.CreateBriefRequest;
import studio.weaveora.project.api.CreateProjectRequest;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectCard;
import studio.weaveora.project.api.ProjectMapper;
import studio.weaveora.project.api.ProjectPage;
import studio.weaveora.project.api.ProjectResponse;
import studio.weaveora.project.domain.Brief;
import studio.weaveora.project.domain.BriefRepository;
import studio.weaveora.project.domain.MarketMark;
import studio.weaveora.project.domain.MarketMarkRepository;
import studio.weaveora.project.domain.Project;
import studio.weaveora.project.domain.ProjectRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.identity.domain.User;
import studio.weaveora.identity.domain.UserRepository;
import studio.weaveora.infra.storage.StoragePort;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final int maxVideoSec; // W8 视频目标时长上限(默认300s)
    private final AssetRepository assets;
    private final UserRepository users;
    private final StoragePort storage;
    private final MarketMarkRepository marks;
    private final String adminEmail;
    private final boolean restrictCreate;
    private final String creatorSuffix;

    public ProjectService(ProjectRepository projects, BriefRepository briefs, WorkspaceGuard guard,
                          AssetRepository assets, UserRepository users, StoragePort storage,
                          MarketMarkRepository marks,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${weaveora.video.max-duration-sec:300}") int maxVideoSec,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${weaveora.access.admin-email:sysou.com@outlook.com}") String adminEmail,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${weaveora.access.restrict-create:false}") boolean restrictCreate,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${weaveora.access.creator-suffix:}") String creatorSuffix) {
        this.projects = projects;
        this.briefs = briefs;
        this.guard = guard;
        this.assets = assets;
        this.users = users;
        this.storage = storage;
        this.marks = marks;
        this.maxVideoSec = maxVideoSec;
        this.adminEmail = adminEmail == null ? "" : adminEmail;
        this.restrictCreate = restrictCreate;
        this.creatorSuffix = creatorSuffix == null ? "" : creatorSuffix.trim().toLowerCase();
    }

    @Transactional
    public ProjectResponse create(UUID userId, UUID workspaceId, CreateProjectRequest req) {
        guard.requireMember(userId, workspaceId);
        if (restrictCreate && !isAdmin(userId) && !isCreatorSuffix(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "内测阶段暂未开放新建项目，请联系管理员 sysou.com@outlook.com");
        }
        String mode = (req.mode() == null || req.mode().isBlank()) ? "image" : req.mode();
        String ratio = (req.aspectRatio() == null || req.aspectRatio().isBlank()) ? "16:9" : req.aspectRatio();
        if (!ALLOWED_MODES.contains(mode)) {
            throw new BizException(ErrorCode.VALIDATION, "mode 必须为 image|video|mixed");
        }
        if (!ALLOWED_RATIOS.contains(ratio)) {
            throw new BizException(ErrorCode.VALIDATION, "aspectRatio 不合法");
        }
        if ("video".equals(mode) && req.durationSec() != null
                && req.durationSec().intValue() > maxVideoSec) {
            throw new BizException(ErrorCode.VALIDATION,
                    "视频目标时长不能超过 " + maxVideoSec + " 秒（W8 长片编排上限）");
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

    // ---------- 项目集市 / 管理（内测） ----------

    /** 管理态：批量删除（仅本人创建的项目；管理员可删任何） */
    @Transactional
    public int deleteBatch(UUID userId, UUID workspaceId, List<UUID> projectIds) {
        guard.requireMember(userId, workspaceId);
        boolean admin = isAdmin(userId);
        int removed = 0;
        for (UUID id : projectIds) {
            Project p = projects.findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, id)
                    .orElse(null);
            // 管理员：跨工作区兜底（集市待审/上架项目也可删）
            if (p == null && admin) {
                p = projects.findById(id).filter(x -> x.deletedAt() == null).orElse(null);
            }
            if (p == null) continue;
            if (!admin && !p.createdBy().equals(userId)) continue;
            p.markDeleted();
            projects.save(p);
            removed++;
        }
        return removed;
    }

    /** 提交分享（客户 → 集市待审） */
    @Transactional
    public ProjectCard share(UUID userId, UUID workspaceId, UUID projectId) {
        guard.requireMember(userId, workspaceId);
        Project p = findInWorkspace(workspaceId, projectId);
        if (!p.createdBy().equals(userId) && !isAdmin(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅项目创建者可分享");
        }
        p.submitShare();
        projects.save(p);
        return toCard(p, ownerName(p.createdBy()));
    }

    /** 我的项目（分页，更新时间倒序） */
    @Transactional(readOnly = true)
    public ProjectPage ownPage(UUID userId, UUID workspaceId, int page, int size) {
        guard.requireMember(userId, workspaceId);
        List<Project> all = new ArrayList<>(
                projects.findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId));
        all.sort(Comparator.comparing(Project::updatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return page(all, page, size, userId, false);
    }

    /** 集市（已上架且非本人） */
    @Transactional(readOnly = true)
    public ProjectPage marketPage(UUID userId, int page, int size) {
        List<Project> all = new ArrayList<>(
                projects.findByShareStatusAndDeletedAtIsNull("approved"));
        all.removeIf(p -> p.createdBy().equals(userId));
        all.sort(Comparator.comparing(Project::updatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return page(all, page, size, userId, true);
    }

    /** 管理后台：待审列表（仅管理员） */
    @Transactional(readOnly = true)
    public ProjectPage pendingPage(UUID userId, int page, int size) {
        requireAdmin(userId);
        List<Project> all = new ArrayList<>(
                projects.findByShareStatusInAndDeletedAtIsNull(List.of("pending", "rejected")));
        all.sort(Comparator.comparing(Project::sharedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return page(all, page, size, userId, false);
    }

    /** 管理审批（批量） */
    @Transactional
    public int review(UUID userId, List<UUID> projectIds, boolean approved) {
        requireAdmin(userId);
        int n = 0;
        for (UUID id : projectIds) {
            var opt = projects.findById(id);
            if (opt.isEmpty()) continue;
            Project p = opt.get();
            if (p.deletedAt() == null && "pending".equals(p.shareStatus())) {
                p.reviewShare(approved);
                projects.save(p);
                n++;
            }
        }
        return n;
    }

    /** 集市只读详情（本人外已上架，含赞/藏计数与我的状态） */
    @Transactional(readOnly = true)
    public ProjectCard marketGet(UUID userId, UUID projectId) {
        Project p = projects.findById(projectId).filter(x -> x.deletedAt() == null
                && "approved".equals(x.shareStatus()) && !x.createdBy().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "集市项目不存在或不可见"));
        return toCardFull(p, ownerName(p.createdBy()),
                marks.countByProjectIdAndKind(projectId, "like"),
                marks.countByProjectIdAndKind(projectId, "fav"),
                marks.existsByProjectIdAndUserIdAndKind(projectId, userId, "like"),
                marks.existsByProjectIdAndUserIdAndKind(projectId, userId, "fav"));
    }

    /** 点赞/收藏切换（集市可见项目；一用户一票） */
    @Transactional
    public MarkToggle toggle(UUID userId, UUID projectId, String kind) {
        if (!List.of("like", "fav").contains(kind)) {
            throw new BizException(ErrorCode.VALIDATION, "kind 必须为 like|fav");
        }
        Project p = projects.findById(projectId).filter(x -> x.deletedAt() == null
                && "approved".equals(x.shareStatus()) && !x.createdBy().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "集市项目不存在或不可见"));
        boolean active;
        if (marks.existsByProjectIdAndUserIdAndKind(projectId, userId, kind)) {
            marks.deleteByProjectIdAndUserIdAndKind(projectId, userId, kind);
            active = false;
        } else {
            marks.save(MarketMark.create(projectId, userId, kind));
            active = true;
        }
        return new MarkToggle(kind, active, marks.countByProjectIdAndKind(projectId, kind));
    }

    public record MarkToggle(String kind, boolean active, long count) {
    }

    /** 集市项目最新图片资产预览：非本人已上架任意登录可见；管理员可见任意项目（待审/驳回也用） */
    @Transactional(readOnly = true)
    public java.util.Optional<Preview> preview(UUID userId, UUID projectId) {
        Project p = projects.findById(projectId).filter(x -> x.deletedAt() == null).orElse(null);
        if (p == null) return java.util.Optional.empty();
        boolean admin = isAdmin(userId);
        boolean visibleApproved = "approved".equals(p.shareStatus()) && !p.createdBy().equals(userId);
        if (!admin && !visibleApproved) {
            return java.util.Optional.empty();
        }
        Asset img = assets.findFirstByProjectIdAndKindInOrderByCreatedAtDesc(
                projectId, List.of("still", "reference"));
        if (img == null) {
            return java.util.Optional.empty();
        }
        StoragePort.StoredObject obj = storage.get(img.storageKey());
        if (obj == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Preview(img.storageKey(), obj.contentType(), obj.stream()));
    }

    public record Preview(String storageKey, String mime, InputStream stream) {
    }

    // ---------- 内部工具 ----------

    private ProjectPage page(List<Project> all, int page, int size, UUID viewer, boolean withMarks) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 50));
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        List<Project> slice = all.subList(from, to);
        Map<UUID, String> names = ownerNames(slice);
        Map<UUID, long[]> markStats = withMarks ? markStats(slice, viewer) : Map.of();
        List<ProjectCard> items = slice.stream()
                .map(x -> {
                    long[] m = withMarks ? markStats.getOrDefault(x.id(), new long[2]) : new long[2];
                    boolean liked = withMarks && marks.existsByProjectIdAndUserIdAndKind(x.id(), viewer, "like");
                    boolean faved = withMarks && marks.existsByProjectIdAndUserIdAndKind(x.id(), viewer, "fav");
                    return toCardFull(x, names.getOrDefault(x.createdBy(), ""),
                            m[0], m[1], liked, faved);
                })
                .collect(Collectors.toList());
        return new ProjectPage(items, p, s, all.size(), to < all.size());
    }

    private Map<UUID, long[]> markStats(List<Project> slice, UUID viewer) {
        Map<UUID, long[]> out = new HashMap<>();
        List<UUID> ids = slice.stream().map(Project::id).toList();
        if (ids.isEmpty()) return out;
        for (MarketMark mm : marks.findByProjectIdInAndKind(ids, "like")) {
            out.computeIfAbsent(mm.projectId(), k -> new long[2])[0]++;
        }
        for (MarketMark mm : marks.findByProjectIdInAndKind(ids, "fav")) {
            out.computeIfAbsent(mm.projectId(), k -> new long[2])[1]++;
        }
        return out;
    }

    private ProjectCard toCard(Project p, String ownerName) {
        return toCardFull(p, ownerName, 0, 0, false, false);
    }

    private ProjectCard toCardFull(Project p, String ownerName,
                                   long like, long fav, boolean liked, boolean faved) {
        return new ProjectCard(p.id(), p.title(), p.mode(), p.aspectRatio(), p.durationSec(),
                p.status(), p.shareStatus(), ownerName, p.createdAt(), p.updatedAt(),
                like, fav, liked, faved);
    }

    private Map<UUID, String> ownerNames(List<Project> slice) {
        Set<UUID> ids = slice.stream().map(Project::createdBy).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        for (User u : users.findAllById(ids)) {
            out.put(u.id(), u.displayName());
        }
        return out;
    }

    private String ownerName(UUID userId) {
        if (userId == null) return "";
        return users.findById(userId).map(User::displayName).orElse("");
    }

    private String emailOf(UUID userId) {
        return users.findById(userId).map(User::email).orElse("");
    }

    private boolean isAdmin(UUID userId) {
        String em = emailOf(userId);
        return !adminEmail.isBlank() && em.equalsIgnoreCase(adminEmail);
    }

    private boolean isCreatorSuffix(UUID userId) {
        if (creatorSuffix.isBlank()) return false;
        return emailOf(userId).toLowerCase().endsWith(creatorSuffix);
    }

    private void requireAdmin(UUID userId) {
        if (!isAdmin(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作项目集市审批");
        }
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
