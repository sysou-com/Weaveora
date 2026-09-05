package studio.weaveora.job.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.job.JobService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker 内部通道（§19：节点注册/心跳/认领 + 任务回执；token 鉴权在 InternalAuthFilter，nginx 不对外暴露 /internal）。
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final JobService jobService;

    public InternalController(JobService jobService) {
        this.jobService = jobService;
    }

    public record RegisterRequest(String name, UUID workspaceId, JsonNode capabilities) {
    }

    public record ProgressRequest(int progress, String stage) {
    }

    public record CompleteAssetDto(String key, String mime, Integer width, Integer height, Long seed, Integer durationMs) {
    }

    public record CompleteRequest(List<CompleteAssetDto> assets) {
    }

    public record FailRequest(String code, String message) {
    }

    @PostMapping("/nodes/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        UUID nodeId = jobService.registerNode(req.name(), req.workspaceId(), req.capabilities());
        return ResponseEntity.ok(Map.of("nodeId", nodeId.toString()));
    }

    @PostMapping("/nodes/{nodeId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable UUID nodeId) {
        jobService.heartbeat(nodeId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/nodes/{nodeId}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable UUID nodeId) {
        return ResponseEntity.ok(jobService.claim(nodeId));
    }

    @PostMapping("/jobs/{jobId}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable UUID jobId,
                                                        @RequestBody ProgressRequest req) {
        jobService.progress(jobId, req.progress(), req.stage());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/jobs/{jobId}/complete")
    public ResponseEntity<List<AssetResponse>> complete(@PathVariable UUID jobId,
                                                        @RequestBody CompleteRequest req) {
        List<JobService.CompleteAsset> items = req.assets() == null ? List.of()
                : req.assets().stream()
                .map(a -> new JobService.CompleteAsset(a.key(), a.mime(), a.width(), a.height(),
                        a.seed(), a.durationMs()))
                .toList();
        return ResponseEntity.ok(jobService.complete(jobId, items));
    }

    @PostMapping("/jobs/{jobId}/fail")
    public ResponseEntity<Map<String, Object>> fail(@PathVariable UUID jobId,
                                                    @RequestBody FailRequest req) {
        jobService.fail(jobId, req.code(), req.message());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** worker 产物先传 API（dev 无 OSS 直传），返回存储 key 供 complete 引用。 */
    @PostMapping(value = "/jobs/{jobId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAsset(@PathVariable UUID jobId,
                                                           @RequestParam("file") MultipartFile file) throws IOException {
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String suffix = suffixOf(mime, file.getOriginalFilename());
        String key = jobService.storeWorkerFile(jobId, file.getBytes(), mime, suffix);
        return ResponseEntity.ok(Map.of("key", key));
    }

    /** 参考图字节读取（W4 一致性锚定）：?key=base64url(存储 key)。 */
    @GetMapping("/assets")
    public ResponseEntity<byte[]> asset(@RequestParam("key") String keyB64) throws IOException {
        String storageKey = new String(java.util.Base64.getUrlDecoder().decode(keyB64),
                java.nio.charset.StandardCharsets.UTF_8);
        var obj = jobService.readAssetByKey(storageKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(obj.contentType()))
                .body(obj.stream().readAllBytes());
    }

    private static String suffixOf(String mime, String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        if (mime.startsWith("video/") || name.endsWith(".mp4")) return "mp4";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || mime.contains("jpeg")) return "jpg";
        if (name.endsWith(".webp")) return "webp";
        if (mime.contains("png") || name.endsWith(".png")) return "png";
        return "bin";
    }
}
