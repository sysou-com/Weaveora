package studio.weaveora.asset.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import studio.weaveora.asset.AssetService;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.project.api.ProjectController;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 资产端点：参考图上传/列表（项目维度）+ 下载。 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;
    private final studio.weaveora.asset.VoicePresetService voicePresetService;

    public AssetController(AssetService assetService,
                           studio.weaveora.asset.VoicePresetService voicePresetService) {
        this.assetService = assetService;
        this.voicePresetService = voicePresetService;
    }

    /** 上传参考图（W2C）：multipart 字段 file */
    @PostMapping(value = "/projects/{projectId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetResponse> upload(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(assetService.uploadReference(
                uid(request), ws(workspaceId), projectId, file));
    }

    /**
     * P9：克隆音色 —— 上传样本 → 处理 → 返回原件/处理后两个资产（前端做 A/B 对比试听）。
     */
    @PostMapping(value = "/projects/{projectId}/voice-presets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createVoicePreset(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "trimSilence", defaultValue = "true") Boolean trimSilence,
            @RequestParam(value = "loudnorm", defaultValue = "true") Boolean loudnorm,
            @RequestParam(value = "denoise", defaultValue = "false") Boolean denoise,
            @RequestParam(value = "limitLength", defaultValue = "false") Boolean limitLength,
            @RequestParam(value = "pitchSemitones", defaultValue = "0") Double pitchSemitones) {
        var opts = new studio.weaveora.asset.AudioProcessService.Options(
                Boolean.TRUE.equals(trimSilence), Boolean.TRUE.equals(loudnorm),
                Boolean.TRUE.equals(denoise), Boolean.TRUE.equals(limitLength),
                pitchSemitones == null ? 0 : pitchSemitones);
        var c = voicePresetService.create(uid(request), ws(workspaceId), projectId, name, file, opts);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("rawAssetId", c.rawAssetId());
        body.put("presetAssetId", c.presetAssetId());
        body.put("durationSec", c.durationSec());
        body.put("warnings", c.warnings());
        return ResponseEntity.ok(body);
    }

    /**
     * P12 音色试听：返回「该音色自己的样本资产」。
     *
     * <p>克隆音色 → 回克隆时录入的那段音频；内置音色 → 回模板音频（「你好，欢迎试音」，
     * 首次现合成并缓存）。前端拿 assetId 走既有 {@code /assets/{id}/download} 播放。
     */
    @PostMapping("/projects/{projectId}/voice-presets/audition")
    public ResponseEntity<Map<String, Object>> audition(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(value = "voice", required = false) String voice,
            @RequestParam(value = "assetId", required = false) String assetId) {
        var a = voicePresetService.audition(uid(request), ws(workspaceId), projectId, voice, assetId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assetId", a.assetId().toString());
        body.put("durationMs", a.durationMs());
        body.put("cached", a.cached());
        body.put("fromSample", a.fromSample());
        return ResponseEntity.ok(body);
    }

    /** P9：转写样本（whisper 在 GPU 机器上），并把文本回写到音色快照供下次复用。 */
    @PostMapping("/projects/{projectId}/voice-presets/{assetId}/transcribe")
    public ResponseEntity<Map<String, String>> transcribe(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId) {
        String text = voicePresetService.transcribeAndCache(
                uid(request), ws(workspaceId), projectId, assetId);
        return ResponseEntity.ok(Map.of("text", text == null ? "" : text));
    }

    /** P9-B：把音色样本直接当本行配音（服务端复制，无需重新上传）。 */
    @PostMapping("/projects/{projectId}/shots/{shotNo}/lines/{lineIndex}/voice-from-sample")
    public ResponseEntity<Map<String, Object>> useSampleAsLineVoice(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable int shotNo,
            @PathVariable int lineIndex,
            @RequestParam("srcAssetId") String srcAssetId,
            @RequestParam(value = "atSec", defaultValue = "0") Double atSec,
            @RequestParam(value = "subject", required = false) String subject) {
        var c = voicePresetService.useAsLineVoice(uid(request), ws(workspaceId), projectId,
                shotNo, lineIndex, atSec == null ? 0 : atSec, subject, UUID.fromString(srcAssetId));
        return ResponseEntity.ok(Map.of("assetId", c.presetAssetId(), "durationSec", c.durationSec()));
    }

    /** P9：删除音色（原件 + 处理后）。 */
    @PostMapping("/projects/{projectId}/voice-presets/{assetId}/delete")
    public ResponseEntity<Map<String, String>> deleteVoicePreset(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId,
            @RequestParam(value = "rawAssetId", required = false) String rawAssetId) {
        voicePresetService.delete(uid(request), ws(workspaceId), projectId, assetId,
                rawAssetId == null || rawAssetId.isBlank() ? null : UUID.fromString(rawAssetId));
        return ResponseEntity.ok(Map.of("deleted", "ok"));
    }

    /**
     * P8：导入配音 —— 用户上传自己配好的那一段声音（mp3/wav/m4a/aac/ogg/flac）。
     * 落的资产 kind=voice + shot_no，prompt_snapshot 带 line_index/at_sec/subject，
     * 与生成产物同格式，混音/导出无需区分来源。
     */
    @PostMapping(value = "/projects/{projectId}/voice-lines", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetResponse> uploadVoiceLine(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("shotNo") Integer shotNo,
            @RequestParam(value = "lineIndex", defaultValue = "0") Integer lineIndex,
            @RequestParam(value = "atSec", defaultValue = "0") Double atSec,
            @RequestParam(value = "subject", required = false) String subject) {
        return ResponseEntity.ok(assetService.uploadVoiceLine(
                uid(request), ws(workspaceId), projectId, shotNo,
                lineIndex == null ? 0 : lineIndex, atSec == null ? 0 : atSec, subject, file));
    }

    @GetMapping("/projects/{projectId}/assets")
    public ResponseEntity<List<AssetResponse>> list(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(assetService.listByProject(
                uid(request), ws(workspaceId), projectId));
    }

    @GetMapping("/assets/{assetId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID assetId) {
        AssetService.Download d = assetService.getForDownload(uid(request), ws(workspaceId), assetId);
        InputStreamResource res = new InputStreamResource(d.stream());
        String name = d.asset().id() + "." + ext(d.contentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(res);
    }

    /** 批量删除所选资产（资产库勾选，删行+删文件） */
    @PostMapping("/projects/{projectId}/assets/delete")
    public ResponseEntity<Map<String, Integer>> delete(
            HttpServletRequest request,
            @RequestHeader(value = ProjectController.WORKSPACE_HEADER, required = false) String workspaceId,
            @PathVariable UUID projectId,
            @jakarta.validation.Valid @RequestBody BatchAssetRequest req) {
        int removed = assetService.delete(uid(request), ws(workspaceId), projectId, req.assetIds());
        return ResponseEntity.ok(Map.of("deleted", removed));
    }

    private static String ext(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "application/zip" -> "zip";
            default -> "png";
        };
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
