package studio.weaveora.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import studio.weaveora.director.DirectorService;
import studio.weaveora.director.api.GenerateRequest;
import studio.weaveora.director.api.GenerateResponse;
import studio.weaveora.director.domain.PromptRevision;
import studio.weaveora.director.domain.PromptRevisionRepository;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.identity.domain.User;
import studio.weaveora.identity.domain.UserRepository;
import studio.weaveora.project.ProjectService;
import studio.weaveora.project.api.BriefResponse;
import studio.weaveora.project.api.CreateProjectRequest;
import studio.weaveora.project.api.ProjectResponse;
import studio.weaveora.project.domain.Brief;
import studio.weaveora.project.domain.BriefRepository;
import studio.weaveora.script.api.*;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptChange;
import studio.weaveora.script.domain.ScriptChangeRepository;
import studio.weaveora.script.domain.ScriptEpisode;
import studio.weaveora.script.domain.ScriptEpisodeRepository;
import studio.weaveora.script.domain.ScriptMark;
import studio.weaveora.script.domain.ScriptMarkRepository;
import studio.weaveora.script.domain.ScriptRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「我的剧本」业务层：剧本 CRUD、集管理、变更记录、剧本集市（分享/审批/点赞收藏）、转成项目。
 *
 * <p>与项目模块的关系：剧本是**创作上游**（要素 + 集），
 * 「转成项目」把一集交给 {@link ProjectService}（建项目 + 内部 brief 通道）与
 * {@link DirectorService#generate}（出分镜动作 + 正负提示词），不复制项目侧逻辑。
 */
@Service
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);
    private static final String WORKSPACE_HINT = "缺少 X-Workspace-Id 请求头";
    private static final int MAX_EPISODE_TITLE = 200;

    private final ScriptRepository scripts;
    private final ScriptEpisodeRepository episodes;
    private final ScriptChangeRepository changes;
    private final ScriptMarkRepository marks;
    private final UserRepository users;
    private final WorkspaceGuard guard;
    private final ScriptAiService ai;
    private final ProjectService projects;
    private final DirectorService director;
    private final BriefRepository briefs;
    private final PromptRevisionRepository revisions;
    /** 写库用**短事务**：AI 调用必须在事务外（长 LLM 调用不得占着数据库连接，见交接纪律 §5.7）。 */
    private final TransactionTemplate tx;

    private final String adminEmail;
    private final boolean restrictCreate;
    private final String creatorSuffix;
    private final int maxVideoSec;

    public ScriptService(ScriptRepository scripts, ScriptEpisodeRepository episodes,
                         ScriptChangeRepository changes, ScriptMarkRepository marks,
                         UserRepository users, WorkspaceGuard guard, ScriptAiService ai,
                         ProjectService projects, DirectorService director,
                         BriefRepository briefs, PromptRevisionRepository revisions,
                         PlatformTransactionManager txManager,
                         @Value("${weaveora.access.admin-email:sysou.com@outlook.com}") String adminEmail,
                         @Value("${weaveora.access.restrict-create:false}") boolean restrictCreate,
                         @Value("${weaveora.access.creator-suffix:}") String creatorSuffix,
                         @Value("${weaveora.video.max-duration-sec:300}") int maxVideoSec) {
        this.scripts = scripts;
        this.episodes = episodes;
        this.changes = changes;
        this.marks = marks;
        this.users = users;
        this.guard = guard;
        this.ai = ai;
        this.projects = projects;
        this.director = director;
        this.briefs = briefs;
        this.revisions = revisions;
        this.tx = new TransactionTemplate(txManager);
        this.adminEmail = adminEmail == null ? "" : adminEmail;
        this.restrictCreate = restrictCreate;
        this.creatorSuffix = creatorSuffix == null ? "" : creatorSuffix.trim().toLowerCase();
        this.maxVideoSec = maxVideoSec;
    }

    // ------------------------------------------------------------ 剧本 CRUD

    @Transactional
    public ScriptResponse create(UUID userId, UUID workspaceId, CreateScriptRequest req) {
        guard.requireMember(userId, workspaceId);
        if (restrictCreate && !isAdmin(userId) && !isCreatorSuffix(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "内测阶段暂未开放新建剧本，请联系管理员 " + adminEmail);
        }
        Script s = Script.create(workspaceId, userId, req.title().trim(), req.genre().trim(),
                req.characters(), req.story(), req.conflict(),
                req.plotStructure(), req.language(), req.stageDirections());
        return toResponse(scripts.save(s));
    }

    @Transactional(readOnly = true)
    public List<ScriptResponse> list(UUID userId, UUID workspaceId) {
        guard.requireMember(userId, workspaceId);
        return scripts.findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScriptResponse get(UUID userId, UUID workspaceId, UUID scriptId) {
        guard.requireMember(userId, workspaceId);
        return toResponse(requireScript(workspaceId, scriptId));
    }

    @Transactional
    public ScriptResponse patch(UUID userId, UUID workspaceId, UUID scriptId, UpdateScriptRequest req) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        s.patch(req.title() == null ? null : req.title().trim(),
                req.genre() == null ? null : req.genre().trim(),
                req.characters(), req.story(), req.conflict(),
                req.plotStructure(), req.language(), req.stageDirections());
        if (req.condensedStory() != null) {
            s.setCondensedStory(req.condensedStory());
        }
        if ("draft".equals(s.status()) && hasAnyElement(s)) {
            s.setStatus("writing");
        }
        scripts.save(s);
        changes.save(ScriptChange.create(scriptId, workspaceId, null, null, "field_update",
                JsonNodeFactory.instance.arrayNode(), "更新剧本要素", "user"));
        return toResponse(s);
    }

    @Transactional
    public int deleteBatch(UUID userId, UUID workspaceId, List<UUID> scriptIds) {
        guard.requireMember(userId, workspaceId);
        boolean admin = isAdmin(userId);
        int removed = 0;
        for (UUID id : scriptIds) {
            Script s = scripts.findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, id).orElse(null);
            if (s == null && admin) {
                s = scripts.findById(id).filter(x -> x.deletedAt() == null).orElse(null);
            }
            if (s == null) continue;
            if (!admin && !s.createdBy().equals(userId)) continue;
            s.markDeleted();
            scripts.save(s);
            removed++;
        }
        return removed;
    }

    // ------------------------------------------------------------ 我的剧本 / 集市

    @Transactional(readOnly = true)
    public ScriptPage ownPage(UUID userId, UUID workspaceId, int page, int size) {
        guard.requireMember(userId, workspaceId);
        List<Script> all = new ArrayList<>(
                scripts.findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId));
        all.sort(updatedDesc());
        return page(all, page, size, userId, false);
    }

    @Transactional(readOnly = true)
    public ScriptPage marketPage(UUID userId, int page, int size) {
        List<Script> all = new ArrayList<>(scripts.findByShareStatusAndDeletedAtIsNull("approved"));
        all.removeIf(s -> userId != null && s.createdBy().equals(userId));
        all.sort(updatedDesc());
        return page(all, page, size, userId, userId != null);
    }

    @Transactional(readOnly = true)
    public ScriptPage pendingPage(UUID userId, int page, int size) {
        requireAdmin(userId);
        List<Script> all = new ArrayList<>(
                scripts.findByShareStatusInAndDeletedAtIsNull(List.of("pending", "rejected")));
        all.sort(Comparator.comparing(Script::sharedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return page(all, page, size, userId, false);
    }

    @Transactional
    public ScriptCard share(UUID userId, UUID workspaceId, UUID scriptId) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        if (!s.createdBy().equals(userId) && !isAdmin(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅剧本创建者可分享");
        }
        s.submitShare();
        scripts.save(s);
        return toCard(s, ownerName(s.createdBy()), 0, 0, false, false);
    }

    @Transactional
    public int review(UUID userId, List<UUID> scriptIds, boolean approved) {
        requireAdmin(userId);
        int n = 0;
        for (UUID id : scriptIds) {
            var opt = scripts.findById(id);
            if (opt.isEmpty()) continue;
            Script s = opt.get();
            if (s.deletedAt() == null && "pending".equals(s.shareStatus())) {
                s.reviewShare(approved);
                scripts.save(s);
                n++;
            }
        }
        return n;
    }

    @Transactional(readOnly = true)
    public ScriptCard marketGet(UUID userId, UUID scriptId) {
        Script s = scripts.findById(scriptId)
                .filter(x -> x.deletedAt() == null && "approved".equals(x.shareStatus())
                        && (userId == null || !x.createdBy().equals(userId)))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "剧本精选不存在或不可见"));
        return toCard(s, ownerName(s.createdBy()),
                marks.countByScriptIdAndKind(scriptId, "like"),
                marks.countByScriptIdAndKind(scriptId, "fav"),
                userId != null && marks.existsByScriptIdAndUserIdAndKind(scriptId, userId, "like"),
                userId != null && marks.existsByScriptIdAndUserIdAndKind(scriptId, userId, "fav"));
    }

    @Transactional
    public MarkToggleResult toggle(UUID userId, UUID scriptId, String kind) {
        if (!List.of("like", "fav").contains(kind)) {
            throw new BizException(ErrorCode.VALIDATION, "kind 必须为 like|fav");
        }
        scripts.findById(scriptId)
                .filter(x -> x.deletedAt() == null && "approved".equals(x.shareStatus())
                        && !x.createdBy().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "剧本精选不存在或不可见"));
        boolean active;
        if (marks.existsByScriptIdAndUserIdAndKind(scriptId, userId, kind)) {
            marks.deleteByScriptIdAndUserIdAndKind(scriptId, userId, kind);
            active = false;
        } else {
            marks.save(ScriptMark.create(scriptId, userId, kind));
            active = true;
        }
        return new MarkToggleResult(kind, active, marks.countByScriptIdAndKind(scriptId, kind));
    }

    // ------------------------------------------------------------ 集

    @Transactional(readOnly = true)
    public List<ScriptEpisodeResponse> listEpisodes(UUID userId, UUID workspaceId, UUID scriptId) {
        guard.requireMember(userId, workspaceId);
        requireScript(workspaceId, scriptId);
        return withProjectLinks(userId, workspaceId,
                episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId));
    }

    /** 一集 → 它转出的项目（含项目当前版本号）。 */
    public record EpisodeProjectLink(UUID projectId, String projectTitle, Integer revisionNo) {
    }

    /**
     * 给一批集补上「集 → 项目」链接（用户 2026-09-17：每集若有已转过的项目，要能在行上「查看项目」）。
     *
     * <p>关联存在 {@code briefs.constraints.scriptEpisodeId}（转项目时写入），一次批量查询即可。
     */
    private List<ScriptEpisodeResponse> withProjectLinks(UUID userId, UUID workspaceId, List<ScriptEpisode> all) {
        Map<UUID, EpisodeProjectLink> links = linksOf(userId, workspaceId, all);
        return all.stream().map(e -> {
            EpisodeProjectLink l = links.get(e.id());
            return l == null ? ScriptMapper.toEpisode(e)
                    : ScriptMapper.toEpisode(e, l.projectId(), l.projectTitle(), l.revisionNo());
        }).toList();
    }

    /** 单集 + 链接（保存后回传给前端用）。 */
    private ScriptEpisodeResponse episodeOf(UUID userId, UUID workspaceId, ScriptEpisode e) {
        EpisodeProjectLink l = linksOf(userId, workspaceId, List.of(e)).get(e.id());
        return l == null ? ScriptMapper.toEpisode(e)
                : ScriptMapper.toEpisode(e, l.projectId(), l.projectTitle(), l.revisionNo());
    }

    /** 批量取「集 → 项目」链接；同一集若历史上转过多次（老版本会各建项目），取**最近一次**那个。 */
    private Map<UUID, EpisodeProjectLink> linksOf(UUID userId, UUID workspaceId, List<ScriptEpisode> all) {
        if (all == null || all.isEmpty()) {
            return Map.of();
        }
        List<String> ids = all.stream().map(e -> e.id().toString()).toList();
        List<Brief> found = briefs.findByScriptEpisodeIds(workspaceId, ids);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> epToProject = new LinkedHashMap<>();
        for (Brief b : found) {     // 已按 created_at desc：每个集第一次见到的就是最近那个
            String raw = b.constraints() == null ? "" : b.constraints().path("scriptEpisodeId").asText("");
            UUID epId = parseUuid(raw);
            if (epId != null) {
                epToProject.putIfAbsent(epId, b.projectId());
            }
        }
        if (epToProject.isEmpty()) {
            return Map.of();
        }
        Set<UUID> projectIds = new HashSet<>(epToProject.values());
        Map<UUID, String> titles = new HashMap<>();
        for (UUID pid : projectIds) {
            try {
                titles.put(pid, projects.get(userId, workspaceId, pid).title());
            } catch (RuntimeException ex) {
                // 项目已被删/不可见 → 不展示链接（不是错误）
                log.debug("剧本集的项目链接失效：project={} : {}", pid, ex.getMessage());
            }
        }
        Map<UUID, Integer> revs = new HashMap<>();
        if (!titles.isEmpty()) {
            for (PromptRevision r : revisions.findByProjectIdInOrderByRevisionNoDesc(titles.keySet())) {
                revs.putIfAbsent(r.projectId(), r.revisionNo());
            }
        }
        Map<UUID, EpisodeProjectLink> out = new HashMap<>();
        epToProject.forEach((epId, pid) -> {
            String title = titles.get(pid);
            if (title != null) {
                out.put(epId, new EpisodeProjectLink(pid, title, revs.get(pid)));
            }
        });
        return out;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 剧本精选只读：已上架剧本的集列表（游客可读）。 */
    @Transactional(readOnly = true)
    public List<ScriptEpisodeResponse> marketEpisodes(UUID scriptId) {
        scripts.findById(scriptId)
                .filter(x -> x.deletedAt() == null && "approved".equals(x.shareStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "剧本精选不存在或不可见"));
        return episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId).stream()
                .map(ScriptMapper::toEpisode).toList();
    }

    /**
     * 新增 / 更新一集。
     *
     * <h3>性能：保存为什么慢（2026-09-17 用户报「剧情不多保存也慢」，实测定位）</h3>
     * 写库本体 ≈ **0.01s**；慢的全部是保存后那一次 AI 调用（刷新「精简的故事」+ 一致性检查），
     * 实测 **3.8–70s**（随 LLM 延迟与上下文大小波动；失败重试时更久）。所以：
     * <ul>
     *   <li>{@code sync=false}（前端「保存」走这条）→ 只写库、**立即返回**，前端拿到后再去后台刷新记忆；</li>
     *   <li>{@code sync=true}（接口兼容路径）→ 仍然同步刷新，但 AI 调用被挪到**事务外**
     *       （长 LLM 调用不得占着数据库连接，交接纪律 §5.7），结果另开短事务落库。</li>
     * </ul>
     */
    public EpisodeSaveResult saveEpisode(UUID userId, UUID workspaceId, UUID scriptId,
                                         UUID episodeId, SaveEpisodeRequest req) {
        guard.requireMember(userId, workspaceId);
        requireScript(workspaceId, scriptId);
        // ① 写库（短事务）
        Saved saved = tx.execute(st -> saveEpisodeTx(userId, workspaceId, scriptId, episodeId, req));
        if (!req.sync()) {
            return new EpisodeSaveResult(saved.episode(), saved.condensedStory(), List.of(),
                    recentChanges(scriptId, 5), List.of(), ai.source());
        }
        // ② AI 刷新（**事务外**）
        Script s = requireScript(workspaceId, scriptId);
        AiCondensedResult cond = ai.refreshCondensed(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId));
        // ③ 应用（短事务）
        return tx.execute(st -> applyCondensedTx(workspaceId, scriptId, saved, cond));
    }

    /** 保存的「写库」部分（短事务，**绝不调 LLM**）。 */
    private record Saved(int episodeNo, UUID episodeId, ScriptEpisodeResponse episode, String condensedStory) {
    }

    private Saved saveEpisodeTx(UUID userId, UUID workspaceId, UUID scriptId, UUID episodeId,
                                SaveEpisodeRequest req) {
        Script s = requireScript(workspaceId, scriptId);

        boolean created = episodeId == null;
        ScriptEpisode saved;
        if (created) {
            int no = episodes.findTopByScriptIdOrderByEpisodeNoDesc(scriptId)
                    .map(e -> e.episodeNo() + 1).orElse(1);
            String title = blankTo(req.title(), "第 " + no + " 集");
            saved = episodes.save(ScriptEpisode.create(scriptId, workspaceId, no, title,
                    req.content(), req.summary(), Boolean.TRUE.equals(req.aiPolished())));
        } else {
            saved = episodes.findByIdAndScriptId(episodeId, scriptId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该集不存在"));
            saved.patch(req.title() == null ? null : clip(req.title(), MAX_EPISODE_TITLE),
                    req.content(), req.summary(), req.aiPolished());
            saved = episodes.save(saved);
        }

        // 【B】保存本集提纲（该集照着哪份提纲写的；重开可见、便于续写与复盘）
        if (req.outline() != null && !req.outline().isEmpty()) {
            saved.setOutline(ScriptMapper.toJson(req.outline()));
            saved = episodes.save(saved);
        }

        if (saved.title().isBlank()) {
            saved.patch("第 " + saved.episodeNo() + " 集", null, null, null);
            saved = episodes.save(saved);
        }

        changes.save(ScriptChange.create(scriptId, workspaceId, saved.id(), saved.episodeNo(),
                created ? "episode_create" : "episode_update",
                JsonNodeFactory.instance.arrayNode(), "", "user"));

        if ("draft".equals(s.status())) {
            s.setStatus("writing");
        }
        scripts.save(s);
        log.info("script episode saved: script={} no={} created={}", scriptId, saved.episodeNo(), created);
        return new Saved(saved.episodeNo(), saved.id(), episodeOf(userId, workspaceId, saved), s.condensedStory());
    }

    /** 应用 AI 刷新结果（短事务）：精简的故事 + 待用户确认的一致性改动（Q4：不自动改历史章节）。 */
    private EpisodeSaveResult applyCondensedTx(UUID workspaceId, UUID scriptId, Saved saved,
                                              AiCondensedResult cond) {
        List<ScriptConflict> conflicts = cond.conflicts() == null ? List.of() : cond.conflicts();
        applyCondensedStory(workspaceId, scriptId, cond.condensedStory());
        if (!conflicts.isEmpty()) {
            changes.save(ScriptChange.create(scriptId, workspaceId, saved.episodeId(), saved.episodeNo(),
                    "consistency_proposal", conflictsJson(conflicts),
                    "一致性检查建议（待用户确认后改写）", "ai"));
        }
        return new EpisodeSaveResult(saved.episode(), requireScript(workspaceId, scriptId).condensedStory(),
                conflicts, recentChanges(scriptId, 5),
                cond.completedBeats() == null ? List.of() : cond.completedBeats(), ai.source());
    }

    /**
     * 把 AI 产出的「精简的故事」落库（**唯一入口**：保存后刷新 / 后台或手动刷新共用）。
     *
     * <p>2026-09-17 自查：原先保存路径与 {@code aiCondensed} 各写一份「setCondensedStory + save」，
     * 两处口径迟早会漂；现在只留这一个方法，传空串/ null 时不动库（不静默清空用户数据）。
     */
    private void applyCondensedStory(UUID workspaceId, UUID scriptId, String story) {
        if (story == null || story.isBlank()) {
            return;
        }
        Script cur = requireScript(workspaceId, scriptId);
        cur.setCondensedStory(story);
        scripts.save(cur);
    }

    @Transactional
    public void deleteEpisode(UUID userId, UUID workspaceId, UUID scriptId, UUID episodeId) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        ScriptEpisode e = episodes.findByIdAndScriptId(episodeId, scriptId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该集不存在"));
        int no = e.episodeNo();
        episodes.delete(e);
        episodes.flush();
        // 重排编号，保持 1..N 连续（uq_script_episode_no 依赖它）
        List<ScriptEpisode> rest = episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId);
        int i = 1;
        for (ScriptEpisode x : rest) {
            if (x.episodeNo() != i) {
                x.renumber(i);
                episodes.save(x);
            }
            i++;
        }
        changes.save(ScriptChange.create(scriptId, workspaceId, null, no, "episode_update",
                JsonNodeFactory.instance.arrayNode(), "删除第 " + no + " 集", "user"));
        if (rest.isEmpty() && "writing".equals(s.status())) {
            s.setStatus("draft");
            scripts.save(s);
        }
    }

    @Transactional(readOnly = true)
    public List<ScriptChangeResponse> listChanges(UUID userId, UUID workspaceId, UUID scriptId, int limit) {
        guard.requireMember(userId, workspaceId);
        requireScript(workspaceId, scriptId);
        return recentChanges(scriptId, limit <= 0 ? 50 : Math.min(limit, 200));
    }

    /**
     * Q4：用户确认后，AI 重写并应用历史章节改动。
     */
    @Transactional
    public ApplySyncResult applySync(UUID userId, UUID workspaceId, UUID scriptId, UUID episodeId,
                                     ApplySyncRequest req) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        List<ScriptEpisode> all = episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId);
        Map<Integer, ScriptEpisode> byNo = new LinkedHashMap<>();
        all.forEach(e -> byNo.put(e.episodeNo(), e));

        Map<Integer, ScriptAiService.EpisodeDraft> drafts = ai.rewriteEpisodes(s, all, req.items());
        if (drafts.isEmpty()) {
            throw new BizException(ErrorCode.DIRECTOR_PARSE_FAILED,
                    "AI 未能改写这些章节（可能未配置 LLM）；可先手动修改对应集");
        }
        List<ScriptEpisodeResponse> updated = new ArrayList<>();
        ArrayNode changedJson = JsonNodeFactory.instance.arrayNode();
        StringBuilder note = new StringBuilder("一致性改写：");
        for (ScriptConflict c : req.items()) {
            ScriptEpisode target = byNo.get(c.episodeNo() == null ? -1 : c.episodeNo());
            ScriptAiService.EpisodeDraft d = drafts.get(c.episodeNo());
            if (target == null || d == null) {
                note.append("第").append(c.episodeNo()).append("集未改写；");
                continue;
            }
            target.patch(d.title() == null || d.title().isBlank() ? null : clip(d.title(), MAX_EPISODE_TITLE),
                    d.content() == null || d.content().isBlank() ? null : d.content(),
                    d.summary() == null || d.summary().isBlank() ? null : d.summary(), null);
            episodes.save(target);
            updated.add(ScriptMapper.toEpisode(target));
            ObjectNode item = changedJson.addObject();
            item.put("episodeNo", target.episodeNo());
            item.put("title", target.title());
            item.put("what", nz(c.fix()).isBlank() ? nz(c.issue()) : c.fix());
            note.append("第").append(target.episodeNo()).append("集；");
        }
        // 改写后再刷新一次精简故事（保持唯一连续记忆与正文一致）
        AiCondensedResult cond = ai.refreshCondensed(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId));
        if (cond.condensedStory() != null && !cond.condensedStory().isBlank()) {
            s.setCondensedStory(cond.condensedStory());
            scripts.save(s);
        }
        changes.save(ScriptChange.create(scriptId, workspaceId, episodeId, null,
                "consistency_applied", changedJson, note.toString(), "ai"));
        log.info("script consistency applied: script={} episodes={}", scriptId, updated.size());
        return new ApplySyncResult(updated, s.condensedStory(), recentChanges(scriptId, 5), ai.source());
    }

    // ------------------------------------------------------------ AI

    /**
     * 单字段 AI 生成/更新。
     *
     * <p>写事务只有一个目的：【B】把本次提纲持久化（`scripts.outlines`），供下次「AI 更新」**复用同一份提纲**。
     * LLM 调用期间仍不持有事务——先读上下文 → 调 LLM → 再写提纲。
     */
    @Transactional
    public AiFieldResult aiField(UUID userId, UUID workspaceId, UUID scriptId, AiFieldRequest req) {
        guard.requireMember(userId, workspaceId);
        ScriptField field = ScriptField.of(req.field());
        if (field == null) {
            throw new BizException(ErrorCode.VALIDATION, "field 非法（可选：characters|story|conflict|"
                    + "plotStructure|language|stageDirections）");
        }
        Script s = requireScript(workspaceId, scriptId);
        ScriptPrompts.Outline stored = storedOutline(s, field.key());
        AiFieldResult r = ai.generateField(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId), field, req, stored);
        // 【B】本次提纲存入 scripts.outlines（与已存一致则不动，避免无意义写）
        if (r.outline() != null && !r.outline().isEmpty() && !r.outline().equals(stored.segments())) {
            s.putOutline(field.key(), ScriptMapper.toJson(r.outline()));
            scripts.save(s);
        }
        return r;
    }

    /** 读取已持久化的要素提纲（供复用；无则空）。 */
    private static ScriptPrompts.Outline storedOutline(Script s, String fieldKey) {
        return new ScriptPrompts.Outline(ScriptMapper.outlineList(s.outlineOf(fieldKey)));
    }

    /**
     * 新建剧本页：**无剧本 ID** 的单字段生成（不落库）。
     *
     * <p>把页面上已填的其它要素组装成一个**未持久化**的 Script 作为上下文，
     * 复用与正式路径完全相同的提示词与解析（避免“新建页一套、详情页另一套”）。
     */
    public AiFieldResult aiFieldPreview(AiFieldPreviewRequest req) {
        ScriptField field = ScriptField.of(req.field());
        if (field == null) {
            throw new BizException(ErrorCode.VALIDATION, "field 非法（可选：characters|story|conflict|"
                    + "plotStructure|language|stageDirections）");
        }
        Map<String, String> el = req.elements() == null ? Map.of() : req.elements();
        Script tmp = Script.create(null, null, req.title().trim(), req.genre().trim(),
                el.get("characters"), el.get("story"), el.get("conflict"),
                el.get("plotStructure"), el.get("language"), el.get("stageDirections"));
        return ai.generateField(tmp, List.of(), field, req.toFieldRequest(), ScriptPrompts.Outline.empty());
    }

    public AiNextEpisodeResult aiNextEpisode(UUID userId, UUID workspaceId, UUID scriptId,
                                             AiNextEpisodeRequest req) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        return ai.nextEpisode(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId), req);
    }

    /**
     * 润色作者自己写的正文（「我自己写」路径也能用 AI）：只产出值，不落库 ——
     * 前端拿回后填进编辑器，用户确认再保存。
     */
    public AiPolishResult aiPolish(UUID userId, UUID workspaceId, UUID scriptId, AiPolishRequest req) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        return ai.polishEpisode(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId), req);
    }

    @Transactional
    public AiCondensedResult aiCondensed(UUID userId, UUID workspaceId, UUID scriptId) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        // AI 调用在**事务外**（长 LLM 调用不占数据库连接，§5.7）：前端“保存一集”后会后台调它。
        AiCondensedResult r = ai.refreshCondensed(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId));
        tx.execute(st -> {
            applyCondensedStory(workspaceId, scriptId, r.condensedStory());
            changes.save(ScriptChange.create(scriptId, workspaceId, null, null, "condensed_refresh",
                    conflictsJson(r.conflicts()), "刷新精简的故事", "ai"));
            return null;
        });
        return r;
    }

    public AiGuideResult aiGuide(UUID userId, UUID workspaceId, UUID scriptId) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        return ai.guide(s, episodes.findByScriptIdOrderByEpisodeNoAsc(scriptId));
    }

    // ------------------------------------------------------------ 转成项目

    /**
     * 把一集转化成项目：建项目 → 内部 brief 通道写入本集内容 → 导演生成分镜动作 + 正负提示词。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：三步各自独立事务，
     * 这样导演失败时项目与 brief 已经落库，用户可到项目页手动重试，不丢数据。
     */
    public ConvertToProjectResult toProject(UUID userId, UUID workspaceId, UUID scriptId, UUID episodeId,
                                            ConvertToProjectRequest req) {
        guard.requireMember(userId, workspaceId);
        Script s = requireScript(workspaceId, scriptId);
        ScriptEpisode e = episodes.findByIdAndScriptId(episodeId, scriptId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该集不存在"));

        String mode = req.modeOrDefault();
        if (!List.of("image", "video", "mixed").contains(mode)) {
            throw new BizException(ErrorCode.VALIDATION, "mode 必须为 image|video|mixed");
        }
        if ("mixed".equals(mode)) {
            throw new BizException(ErrorCode.VALIDATION, "转成项目请指定 image 或 video（mixed 需在项目页再定导演模式）");
        }
        BigDecimal duration = req.durationSec();
        if ("video".equals(mode)) {
            if (duration == null || duration.signum() <= 0) {
                duration = new BigDecimal("30");
            }
            if (duration.intValue() > maxVideoSec) {
                throw new BizException(ErrorCode.VALIDATION,
                        "视频目标时长不能超过 " + maxVideoSec + " 秒");
            }
        } else {
            duration = null;
        }

        String projectTitle = clip(s.title() + " · 第" + e.episodeNo() + "集 " + e.title(), 100);
        // 这一集已经转过项目？→ 默认在**同一个项目**里出新版本（V+1），不再新建项目（用户 2026-09-17）
        EpisodeProjectLink link = linksOf(userId, workspaceId, List.of(e)).get(e.id());
        ProjectResponse existing = null;
        if (link != null && !req.newProjectOrFalse()) {
            try {
                existing = projects.get(userId, workspaceId, link.projectId());
            } catch (RuntimeException ex) {
                log.warn("剧本转项目：原项目不可用（{}），改为新建项目", ex.getMessage());
            }
        }
        boolean appended = existing != null;
        ProjectResponse project = appended ? existing
                : projects.create(userId, workspaceId,
                new CreateProjectRequest(projectTitle, mode, req.aspectRatio(), duration,
                        req.styleTemplateId(), req.shotDurationSec()));
        if (appended) {
            // 类型/画幅/时长/风格是**项目级**设置：新版本沿用项目本身（要换规格请新建项目）
            mode = project.mode();
        }

        ObjectNode constraints = JsonNodeFactory.instance.objectNode();
        constraints.put("source", "script");
        constraints.put("scriptId", scriptId.toString());
        constraints.put("scriptEpisodeId", episodeId.toString());
        constraints.put("episodeNo", e.episodeNo());
        String rawText = req.condense()
                ? ai.condenseBrief(s, e)
                : buildRawBrief(s, e);
        BriefResponse brief = projects.createBriefInternal(workspaceId, project.id(), rawText, mode, constraints);

        GenerateResponse gen = null;
        String note = "";
        if (req.director()) {
            try {
                gen = director.generate(userId, workspaceId, project.id(),
                        new GenerateRequest(brief.id(), mode));
            } catch (RuntimeException ex) {
                log.warn("剧本转项目：导演生成失败（项目与 brief 已创建）script={} ep={} : {}",
                        scriptId, e.episodeNo(), ex.getMessage());
                note = "brief 已写入，但导演生成失败（" + safeMsg(ex) + "）；可到项目页点「生成方案」重试。";
            }
        } else {
            note = "已写入 brief（未自动生成方案）。";
        }
        if (appended) {
            note = appendNote(gen != null
                    ? "该集已有项目《" + project.title() + "》—— 已在同一个项目中生成新版本 V" + gen.revisionNo()
                            + "（未新建项目）。"
                    : "该集已有项目《" + project.title()
                            + "》—— brief 已写入该项目，但没有生成新版本（需勾「立即生成分镜与提示词」）。",
                    note);
        }
        int shotCount = gen == null ? 0 : gen.plan().path("shots").size();
        log.info("script->project: script={} ep={} project={} revision={} appended={} shots={}",
                scriptId, e.episodeNo(), project.id(),
                gen == null ? null : gen.revisionNo(), appended, shotCount);
        return new ConvertToProjectResult(project.id(), brief.id(),
                gen == null ? null : gen.revisionId(), shotCount, project.title(), note,
                gen == null ? null : gen.revisionNo(), appended);
    }

    /** 拼接两条提示（空串不产生多余分隔）。 */
    private static String appendNote(String a, String b) {
        return (a == null || a.isBlank()) ? b : a + " " + b;
    }

    /** 原文带入（不精简）时的结构化 brief。 */
    private static String buildRawBrief(Script s, ScriptEpisode e) {
        return "【剧本】" + s.title() + "（" + s.genre() + "）\n"
                + "【本集】第 " + e.episodeNo() + " 集《" + e.title() + "》\n"
                + (e.summary() == null || e.summary().isBlank() ? "" : "【本集摘要】" + e.summary() + "\n")
                + (s.condensedStory() == null || s.condensedStory().isBlank() ? "" :
                        "【已有剧情（精简）】\n" + s.condensedStory() + "\n")
                + "【本集正文】\n" + (e.content() == null ? "" : e.content());
    }

    // ------------------------------------------------------------ 内部工具

    private Script requireScript(UUID workspaceId, UUID scriptId) {
        return scripts.findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, scriptId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "剧本不存在或不在本工作区"));
    }

    private ScriptResponse toResponse(Script s) {
        List<ScriptEpisode> eps = episodes.findByScriptIdOrderByEpisodeNoAsc(s.id());
        return ScriptMapper.toResponse(s, eps.size(), ScriptMapper.charCount(s, eps));
    }

    private ScriptCard toCard(Script s, String ownerName, long like, long fav, boolean liked, boolean favored) {
        List<ScriptEpisode> eps = episodes.findByScriptIdOrderByEpisodeNoAsc(s.id());
        return new ScriptCard(s.id(), s.title(), s.genre(), s.status(), s.shareStatus(), ownerName,
                eps.size(), ScriptMapper.charCount(s, eps), ScriptMapper.excerpt(s, 120),
                s.createdAt(), s.updatedAt(), like, fav, liked, favored);
    }

    private List<ScriptChangeResponse> recentChanges(UUID scriptId, int limit) {
        List<ScriptChange> all = changes.findByScriptIdOrderByCreatedAtDesc(scriptId);
        return all.stream().limit(Math.max(1, limit)).map(ScriptMapper::toChange).toList();
    }

    private ScriptPage page(List<Script> all, int page, int size, UUID viewer, boolean withMarks) {
        int p = Math.max(0, page);
        int z = Math.max(1, Math.min(size, 50));
        int from = Math.min(p * z, all.size());
        int to = Math.min(from + z, all.size());
        List<Script> slice = all.subList(from, to);

        Map<UUID, String> names = ownerNames(slice);
        Map<UUID, long[]> stats = withMarks ? markStats(slice) : Map.of();
        // 集数与字数：一次批量查（避免每张卡一次查询）
        Map<UUID, List<ScriptEpisode>> epsByScript = new HashMap<>();
        if (!slice.isEmpty()) {
            for (ScriptEpisode e : episodes.findByScriptIdInOrderByScriptIdAscEpisodeNoAsc(
                    slice.stream().map(Script::id).toList())) {
                epsByScript.computeIfAbsent(e.scriptId(), k -> new ArrayList<>()).add(e);
            }
        }
        List<ScriptCard> items = new ArrayList<>();
        for (Script s : slice) {
            List<ScriptEpisode> eps = epsByScript.getOrDefault(s.id(), List.of());
            long[] m = stats.getOrDefault(s.id(), new long[2]);
            boolean liked = withMarks && marks.existsByScriptIdAndUserIdAndKind(s.id(), viewer, "like");
            boolean favored = withMarks && marks.existsByScriptIdAndUserIdAndKind(s.id(), viewer, "fav");
            items.add(new ScriptCard(s.id(), s.title(), s.genre(), s.status(), s.shareStatus(),
                    names.getOrDefault(s.createdBy(), ""), eps.size(), ScriptMapper.charCount(s, eps),
                    ScriptMapper.excerpt(s, 120), s.createdAt(), s.updatedAt(),
                    m[0], m[1], liked, favored));
        }
        return new ScriptPage(items, p, z, all.size(), to < all.size());
    }

    private Map<UUID, long[]> markStats(List<Script> slice) {
        Map<UUID, long[]> out = new HashMap<>();
        List<UUID> ids = slice.stream().map(Script::id).toList();
        if (ids.isEmpty()) return out;
        for (ScriptMark mm : marks.findByScriptIdInAndKind(ids, "like")) {
            out.computeIfAbsent(mm.scriptId(), k -> new long[2])[0]++;
        }
        for (ScriptMark mm : marks.findByScriptIdInAndKind(ids, "fav")) {
            out.computeIfAbsent(mm.scriptId(), k -> new long[2])[1]++;
        }
        return out;
    }

    private Map<UUID, String> ownerNames(List<Script> slice) {
        Set<UUID> ids = new HashSet<>();
        slice.forEach(s -> ids.add(s.createdBy()));
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

    private static Comparator<Script> updatedDesc() {
        return Comparator.comparing(Script::updatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
    }

    private static boolean hasAnyElement(Script s) {
        return !s.characters().isBlank() || !s.story().isBlank() || !s.conflict().isBlank()
                || !s.plotStructure().isBlank() || !s.language().isBlank() || !s.stageDirections().isBlank();
    }

    private static ArrayNode conflictsJson(List<ScriptConflict> conflicts) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (ScriptConflict c : conflicts) {
            ObjectNode o = arr.addObject();
            o.put("episodeNo", c.episodeNo() == null ? 0 : c.episodeNo());
            o.put("title", nz(c.title()));
            String what = nz(c.fix()).isBlank() ? nz(c.issue()) : c.fix();
            o.put("what", what);
        }
        return arr;
    }

    private static String safeMsg(RuntimeException ex) {
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : clip(m, 200);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private String emailOf(UUID userId) {
        if (userId == null) return "";
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
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作剧本集市审批");
        }
    }

    /** 供 Controller 复用工作区头校验错误文案。 */
    public static String workspaceHint() {
        return WORKSPACE_HINT;
    }
}
