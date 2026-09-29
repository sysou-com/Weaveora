package studio.weaveora.job.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.job.ProductionRunService;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.UUID;

/**
 * 一键成片「制作流程」端点（P0/P1，2026-09-29）。
 *
 * <p>前端只做两件事：展示 {@code GET .../runs/status} 的五个阶段，和一个「确定 / 下一步」按钮
 * 调 {@code POST .../runs/stages/{stage}/confirm}。门禁与建任务都在后端，UI 不承载业务规则。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class ProductionRunController {

    private final ProductionRunService runs;

    public ProductionRunController(ProductionRunService runs) {
        this.runs = runs;
    }

    /** 制作流程总览（只读）。revisionId 省略时用项目当前确认稿。 */
    @GetMapping("/status")
    public ResponseEntity<ProductionRunService.RunStatus> status(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(name = "revisionId", required = false) UUID revisionId,
            @RequestParam(name = "approved", required = false, defaultValue = "false") boolean approved) {
        return ResponseEntity.ok(runs.status(uid(request), ws(workspaceId), projectId, resolveRevision(revisionId, approved)));
    }

    /** 「准备」：自动分配角色音色 +（可选）按剧情生成台词 + 按台词时长/引擎能力重排镜头时长。 */
    @PostMapping("/prepare")
    public ResponseEntity<ProductionRunService.PrepareResult> prepare(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody PrepareRequest req) {
        boolean withLines = req.lines() == null || Boolean.TRUE.equals(req.lines());
        return ResponseEntity.ok(runs.prepare(uid(request), ws(workspaceId), projectId,
                resolveRevision(req.revisionId(), false), withLines));
    }

    /** 只重排镜头时长（确定性、不跑 LLM）：前端打开项目页时自动调用。 */
    @PostMapping("/replan")
    public ResponseEntity<ProductionRunService.PrepareResult> replan(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody RunRequest req) {
        return ResponseEntity.ok(runs.replanTiming(uid(request), ws(workspaceId), projectId,
                resolveRevision(req.revisionId(), false)));
    }

    /** 点「确定 / 下一步」：记录确认并批量下发该阶段缺失的任务（幂等）。 */
    @PostMapping("/stages/{stage}/confirm")
    public ResponseEntity<ProductionRunService.RunStatus> confirm(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable String stage,
            @Valid @RequestBody RunRequest req) {
        return ResponseEntity.ok(runs.confirm(uid(request), ws(workspaceId), projectId,
                resolveRevision(req.revisionId(), true), stage));
    }

    /** 重试该阶段所有失败任务。 */
    @PostMapping("/stages/{stage}/retry")
    public ResponseEntity<ProductionRunService.RunStatus> retry(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable String stage,
            @Valid @RequestBody RunRequest req) {
        return ResponseEntity.ok(runs.retryFailed(uid(request), ws(workspaceId), projectId,
                resolveRevision(req.revisionId(), true), stage));
    }

    /**
     * 解析 revision：优先显式传入；否则要求项目已有确认稿。
     * （这里不直接查库，交给 service —— 但 confirm 必须绑定确认稿，故直接要求传入。）
     */
    private UUID resolveRevision(UUID revisionId, boolean required) {
        if (revisionId != null) {
            return revisionId;
        }
        if (required) {
            throw new BizException(ErrorCode.VALIDATION, "缺少 revisionId（制作流程必须绑定当前确认稿）");
        }
        throw new BizException(ErrorCode.VALIDATION, "缺少 revisionId");
    }

    public record RunRequest(UUID revisionId) {
    }

    /**
     * 「准备」请求。
     *
     * @param lines 是否允许自动生成台词（默认 true）。前端打开页面时的**自动准备**传 false
     *              （只做确定性的「按引擎能力/台词时长重排」），用户主动点「准备」时才让它跑 LLM 写台词。
     */
    public record PrepareRequest(UUID revisionId, Boolean lines) {
    }

    private UUID uid(HttpServletRequest request) {
        String uid = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return UUID.fromString(uid);
    }

    private UUID ws(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "缺少 " + ProjectController.WORKSPACE_HEADER + " 请求头");
        }
        return UUID.fromString(workspaceId);
    }
}
