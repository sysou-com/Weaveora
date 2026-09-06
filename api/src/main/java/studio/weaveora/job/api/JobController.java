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
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.job.JobService;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 对外 Job 端点（§17.2/§17.5）。 */
@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/projects/{projectId}/jobs")
    public ResponseEntity<List<JobView>> create(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateJobRequest req) {
        return ResponseEntity.ok(jobService.create(uid(request), ws(workspaceId), projectId, req));
    }

    @GetMapping("/projects/{projectId}/jobs")
    public ResponseEntity<List<JobView>> list(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(jobService.listByProject(uid(request), ws(workspaceId), projectId));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobView> get(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(jobService.get(uid(request), ws(workspaceId), jobId));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<JobView> cancel(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(jobService.cancel(uid(request), ws(workspaceId), jobId));
    }

    /** 批量重试所选失败/已取消任务（§20.2：生成新 job） */
    @PostMapping("/projects/{projectId}/jobs/retry")
    public ResponseEntity<List<JobView>> retry(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody BatchJobRequest req) {
        return ResponseEntity.ok(jobService.retry(uid(request), ws(workspaceId), projectId, req.jobIds()));
    }

    /** 批量删除所选失败/已取消任务记录 */
    @PostMapping("/projects/{projectId}/jobs/delete")
    public ResponseEntity<Map<String, Integer>> deleteJobs(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody BatchJobRequest req) {
        int removed = jobService.delete(uid(request), ws(workspaceId), projectId, req.jobIds());
        return ResponseEntity.ok(Map.of("deleted", removed));
    }

    /** 管理员：查看全部 queued/running 任务 */
    @GetMapping("/admin/queue/jobs")
    public ResponseEntity<List<JobView>> adminQueue(HttpServletRequest request) {
        return ResponseEntity.ok(jobService.adminQueue(uid(request)));
    }

    /** 管理员：手工让任务失败（解除阻塞） */
    @PostMapping("/admin/queue/jobs/{jobId}/fail")
    public ResponseEntity<Map<String, Integer>> adminFail(
            HttpServletRequest request,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(Map.of("failed", jobService.adminFail(uid(request), jobId)));
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
