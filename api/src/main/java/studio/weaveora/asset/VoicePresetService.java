package studio.weaveora.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.director.plan.AudioPlan;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * P9 克隆音色：用户录/传一段声音 → {@link AudioProcessService} 处理 → 落成可复用的「音色」。
 *
 * <p>存两份资产：
 * <ul>
 *   <li>{@code voice_preset_src} 原件 —— 供前后对比试听与回溯</li>
 *   <li>{@code voice_preset} 处理后的 24kHz 单声道参考音 —— 真正喂给 CosyVoice 的那份</li>
 * </ul>
 *
 * <p>转写（可选）走 GPU 机器的 tts 服务（whisper 装在那边），见 {@code /transcribe}。
 */
@Service
public class VoicePresetService {

    private static final Logger log = LoggerFactory.getLogger(VoicePresetService.class);

    /** 允许的样本音频类型（与导入配音一致） */
    private static final Set<String> ALLOWED_AUDIO = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/flac", "audio/x-flac",
            "audio/webm", "video/webm");   // 浏览器 MediaRecorder 产出的常是 webm/opus
    private static final long MAX_SAMPLE = 50L * 1024 * 1024;

    /** 音色试听的模板文本（P12）：内置音色第一次试听时现合成一条，之后永久复用。 */
    public static final String AUDITION_TEXT = "你好，欢迎试音";

    private final AssetRepository assets;
    private final StoragePort storage;
    private final WorkspaceGuard guard;
    private final ProjectContextPort projects;
    private final AudioProcessService processor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** GPU 机器 tts 服务的地址（经 SSH 反向隧道暴露在 API 服务器 loopback 上） */
    private final String ttsUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public VoicePresetService(AssetRepository assets, StoragePort storage, WorkspaceGuard guard,
                              ProjectContextPort projects, AudioProcessService processor,
                              @Value("${weaveora.tts-url:http://127.0.0.1:18091}") String ttsUrl) {
        this.assets = assets;
        this.storage = storage;
        this.guard = guard;
        this.projects = projects;
        this.processor = processor;
        this.ttsUrl = ttsUrl == null || ttsUrl.isBlank() ? "http://127.0.0.1:18091" : ttsUrl;
    }

    /** 创建结果。 */
    public record Created(String rawAssetId, String presetAssetId, double durationSec, List<String> warnings) {
    }

    /** 上传样本 → 处理 → 落库。 */
    @Transactional
    public Created create(UUID userId, UUID workspaceId, UUID projectId, String name, MultipartFile file,
                          AudioProcessService.Options opts) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "没有收到音频文件");
        }
        if (file.getSize() > MAX_SAMPLE) {
            throw new BizException(ErrorCode.UPLOAD_TOO_LARGE, "样本不能超过 50MB（建议 5–15 秒就够）");
        }
        String mime = normalizeMime(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_AUDIO.contains(mime)) {
            throw new BizException(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED,
                    "不支持该音频类型（" + mime + "）。可用：mp3/wav/m4a/aac/ogg/flac/webm");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传内容失败", e);
        }
        if (raw.length == 0) {
            throw new BizException(ErrorCode.VALIDATION, "上传内容为空");
        }
        String ext = extOf(mime);

        // 1) 原件（保留，供 A/B 对比）
        String rawKey = workspaceId + "/" + projectId + "/vp-src/" + UUID.randomUUID() + "." + ext;
        put(rawKey, raw, mime);
        Asset rawAsset = assets.save(Asset.output(workspaceId, projectId, null, null, null,
                "voice_preset_src", rawKey, mime, null, null, null, null, null));

        // 2) 处理成参考音
        AudioProcessService.Result r;
        try {
            r = processor.process(raw, ext, opts);
        } catch (RuntimeException e) {
            log.warn("样本处理失败: {}", e.getMessage());
            throw new BizException(ErrorCode.VALIDATION,
                    "音频处理失败（可能是格式不支持或文件损坏）：" + e.getMessage());
        }
        String key = workspaceId + "/" + projectId + "/vp/" + UUID.randomUUID() + ".wav";
        put(key, r.audio(), "audio/wav");
        ObjectNode snap = mapper.createObjectNode();
        snap.put("name", name == null ? "" : name.trim());
        snap.put("source", "clone-sample");
        snap.put("duration_sec", r.durationSec());
        snap.put("trim_silence", opts.trimSilence());
        snap.put("loudnorm", opts.loudnorm());
        snap.put("denoise", opts.denoise());
        snap.put("pitch_semitones", opts.pitchSemitones());
        Asset preset = assets.save(Asset.output(workspaceId, projectId, null, null, null,
                "voice_preset", key, "audio/wav", null, null, null,
                (int) Math.round(r.durationSec() * 1000), snap));

        return new Created(rawAsset.id().toString(), preset.id().toString(), r.durationSec(), r.warnings());
    }

    /**
     * 转写一段样本（用 GPU 机器上的 whisper）。失败不抛业务异常以外的惊讶，
     * 只返回空串让前端提示「可手填文本」—— 转写是锦上添花，不该阻断克隆。
     */
    @Transactional(readOnly = true)
    public String transcribe(UUID userId, UUID workspaceId, UUID projectId, UUID assetId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        Asset a = assets.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "音色资产不存在"));
        if (!a.projectId().equals(projectId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "音色资产不属于该项目");
        }
        StoragePort.StoredObject obj = storage.get(a.storageKey());
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "样本文件已不存在");
        }
        byte[] bytes;
        try (InputStream in = obj.stream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取样本失败", e);
        }
        return transcribeBytes(bytes, obj.contentType());
    }

    /** 直接把音频字节发给 tts 服务做转写（不依赖文件在 GPU 机器上存在）。 */
    public String transcribeBytes(byte[] bytes, String mime) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ttsUrl + "/transcribe"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", mime == null || mime.isBlank() ? "audio/wav" : mime)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("转写失败 {}: {}", resp.statusCode(), resp.body());
                return "";
            }
            JsonNode j = mapper.readTree(resp.body());
            return j.path("text").asText("").trim();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("转写不可用（{}）：{}", ttsUrl, e.getMessage());
            return "";
        }
    }

    /**
     * P9-B：把已存在的音色样本（或任一音频资产）**当成本行配音**落库。
     *
     * <p>服务端直接复制字节，前端无需重新上传；落的资产与生成产物同格式
     * （kind=voice + shot_no + line_index/at_sec/subject 快照）。
     */
    @Transactional
    public Created useAsLineVoice(UUID userId, UUID workspaceId, UUID projectId, int shotNo,
                                int lineIndex, double atSec, String subject, UUID srcAssetId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        Asset src = assets.findByIdAndWorkspaceId(srcAssetId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "源音频资产不存在"));
        if (!src.projectId().equals(projectId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "源音频资产不属于该项目");
        }
        StoragePort.StoredObject obj = storage.get(src.storageKey());
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "源音频文件已不存在");
        }
        byte[] bytes;
        try (InputStream in = obj.stream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取源音频失败", e);
        }
        String ext = extOfMime(src.mime());
        String key = workspaceId + "/" + projectId + "/voice/" + UUID.randomUUID() + "." + ext;
        put(key, bytes, src.mime());
        ObjectNode snap = mapper.createObjectNode();
        snap.put("kind", "voice");
        snap.put("line_index", Math.max(0, lineIndex));
        snap.put("at_sec", Math.max(0, atSec));
        snap.put("line_kind", lineIndex == 0 && (subject == null || subject.isBlank()) ? "narration" : "dialogue");
        if (subject != null && !subject.isBlank()) {
            snap.put("subject", subject.trim());
        }
        snap.put("source", "clone-sample");
        snap.put("src_asset", srcAssetId.toString());
        Asset out = assets.save(Asset.output(workspaceId, projectId, null, null, shotNo, "voice",
                key, src.mime(), null, null, null, src.durationMs(), snap));
        return new Created(null, out.id().toString(),
                src.durationMs() == null ? 0 : src.durationMs() / 1000.0, List.of());
    }

    /** 将音频资产转写并把文本回写到音色资产快照（方便下次直接复用）。 */
    @Transactional
    public String transcribeAndCache(UUID userId, UUID workspaceId, UUID projectId, UUID assetId) {
        String text = transcribe(userId, workspaceId, projectId, assetId);
        if (!text.isBlank()) {
            assets.findByIdAndWorkspaceId(assetId, workspaceId).ifPresent(a -> {
                ObjectNode snap = a.promptSnapshot() instanceof ObjectNode o ? o.deepCopy()
                        : mapper.createObjectNode();
                snap.put("prompt_text", text);
                a.attachPromptSnapshot(snap);
                assets.save(a);
            });
        }
        return text;
    }

    /** 删除音色（原件 + 处理后 + 存储文件）。 */
    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID projectId, UUID presetAssetId, UUID rawAssetId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        for (UUID id : new UUID[]{presetAssetId, rawAssetId}) {
            if (id == null) {
                continue;
            }
            assets.findByIdAndWorkspaceId(id, workspaceId).ifPresent(a -> {
                if (!a.projectId().equals(projectId)) {
                    return;
                }
                try {
                    storage.delete(a.storageKey());
                } catch (RuntimeException ignore) {
                    // 文件已丢不阻塞记录删除
                }
                assets.delete(a);
            });
        }
    }

    /**
     * P12 音色试听：**直接听这个音色自己的样本**，而不是念当前镜头的旁白。
     *
     * <ul>
     *   <li>克隆音色（{@code clone:<id>}）→ 直接回「克隆时录入的那段音频」（处理后 24kHz 参考音），零延迟。</li>
     *   <li>内置音色（中文女…）→ 回项目内缓存的模板音频（{@value #AUDITION_TEXT}）；没有就现合成一条并落库，
     *       下次秒回。</li>
     * </ul>
     *
     * @param cached true = 命中了已有音频（克隆音色 / 已生成的模板），false = 本次新合成
     * @param fromSample true = 回的是克隆时录入的那段音频（不是生成的模板）
     */
    public record Audition(UUID assetId, Integer durationMs, boolean cached, boolean fromSample) {
    }

    /**
     * 音色试听：拿到「该音色的样本资产」（必要时生成并缓存模板）。
     *
     * @param voice         内置音色名（中文女…）；克隆音色时可不传
     * @param presetAssetId 克隆音色的**资产 id**（声音库里的 {@code voice_prest}）。
     *                      注意：plan 里的 {@code clone:<id>} 是音色名生成的 slug，**不是** UUID，
     *                      前面错把它当资产 id 用，前端点了就报「音色标识非法」。
     */
    @Transactional
    public Audition audition(UUID userId, UUID workspaceId, UUID projectId, String voice, String presetAssetId) {
        guard.requireMember(userId, workspaceId);
        projects.require(userId, workspaceId, projectId);
        String v = voice == null ? "" : voice.trim();

        // 1) 克隆音色：克隆时录入/处理好的那份就是最真实的试听样本，直接用
        String cloneAssetId = presetAssetId == null ? "" : presetAssetId.trim();
        if (cloneAssetId.isEmpty() && AudioPlan.isClone(v)) {
            // 兼容老调用：clone:<uuid> 也接（但 plan 里通常是 slug，所以前端必须传 assetId）
            String tail = AudioPlan.cloneId(v);
            if (isUuid(tail)) {
                cloneAssetId = tail;
            } else {
                throw new BizException(ErrorCode.VALIDATION, "克隆音色试听要传 assetId="
                        + "，「" + v + "」里的 " + tail + " 是音色标识而不是资产 id");
            }
        }
        if (!cloneAssetId.isEmpty()) {
            UUID presetId;
            try {
                presetId = UUID.fromString(cloneAssetId);
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.VALIDATION, "音色资产 id 非法：" + cloneAssetId);
            }
            Asset a = assets.findByIdAndWorkspaceId(presetId, workspaceId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "该克隆音色已不存在（可能被删除），请重新选择音色"));
            if (!projectId.equals(a.projectId())) {
                throw new BizException(ErrorCode.NOT_FOUND, "该克隆音色不属于本项目");
            }
            if (!"voice_preset".equals(a.kind()) && !"voice_preset_src".equals(a.kind())) {
                throw new BizException(ErrorCode.VALIDATION, "资产 " + a.kind() + " 不是音色样本（kind 应为 voice_preset）");
            }
            if (storage.get(a.storageKey()) == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "音色文件已丢失，请重新录制该音色");
            }
            return new Audition(a.id(), a.durationMs(), true, true);
        }

        // 2) 内置音色：先找项目内缓存的模板（同音色只生成一次）
        if (v.isEmpty()) {
            v = AudioPlan.DEFAULT_VOICE;
        }
        if (!AudioPlan.BUILTIN_VOICES.contains(v)) {
            throw new BizException(ErrorCode.VALIDATION, "「" + v
                    + "」不是内置音色（" + String.join("/", AudioPlan.BUILTIN_VOICES)
                    + "），也不是克隆音色，无法生成试听模板；请改选内置音色，或先克隆一个音色");
        }
        for (Asset a : assets.findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, "voice_demo")) {
            if (v.equals(snapText(a, "voice")) && storage.get(a.storageKey()) != null) {
                return new Audition(a.id(), a.durationMs(), true, false);
            }
        }

        // 3) 没有模板 → 现合成一条并落库（下次秒回）
        Synthesized s = synthesizeDemo(v);
        String key = workspaceId + "/" + projectId + "/voice-demo/" + UUID.randomUUID() + ".wav";
        put(key, s.audio(), "audio/wav");
        ObjectNode snap = mapper.createObjectNode();
        snap.put("voice", v);
        snap.put("text", AUDITION_TEXT);
        snap.put("source", "audition-template");
        Asset demo = assets.save(Asset.output(workspaceId, projectId, null, null, null,
                "voice_demo", key, "audio/wav", null, null, null, s.durationMs(), snap));
        return new Audition(demo.id(), s.durationMs(), false, false);
    }

    private record Synthesized(byte[] audio, Integer durationMs) {
    }

    /** 调 GPU 机器的 tts 服务合成模板音频。 */
    private Synthesized synthesizeDemo(String voice) {
        ObjectNode body = mapper.createObjectNode();
        body.put("text", AUDITION_TEXT);
        body.put("voice", voice);
        body.put("speed", 1.0);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ttsUrl + "/tts"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                String msg = new String(resp.body(), StandardCharsets.UTF_8);
                throw new BizException(ErrorCode.TTS_UNAVAILABLE,
                        "音色试听合成失败（HTTP " + resp.statusCode() + "）：" + msg.substring(0, Math.min(200, msg.length())));
            }
            byte[] wav = resp.body();
            if (wav == null || wav.length == 0) {
                throw new BizException(ErrorCode.TTS_UNAVAILABLE, "音色试听合成返回空音频");
            }
            Integer ms = resp.headers().firstValue("X-Duration-Ms")
                    .map(h -> {
                        try {
                            return Integer.valueOf(h.trim());
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }).orElse(null);
            return new Synthesized(wav, ms);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("音色试听合成不可用（{}）：{}", ttsUrl, e.getMessage());
            throw new BizException(ErrorCode.TTS_UNAVAILABLE,
                    "配音服务暂时不可用（" + ttsUrl + "）：" + e.getMessage()
                            + "；确认 GPU 机器与隧道（ComfyTTS / 18091）是否在运行");
        }
    }

    /** 是否是合法 UUID（用于容错解析 clone:<uuid>）。 */
    private static boolean isUuid(String s) {
        if (s == null || s.length() != 36) {
            return false;
        }
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 读资产快照里的字符串字段（取不到就空串）。 */
    private static String snapText(Asset a, String field) {
        return a.promptSnapshot() == null ? "" : a.promptSnapshot().path(field).asText("");
    }

    private void put(String key, byte[] bytes, String mime) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            storage.put(key, in, bytes.length, mime);
        } catch (IOException e) {
            throw new IllegalStateException("音色文件存储失败", e);
        }
    }

    private static String extOfMime(String mime) {
        return extOf(mime == null ? "" : mime.toLowerCase());
    }

    private static String extOf(String mime) {
        return switch (mime) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/aac" -> "aac";
            case "audio/ogg" -> "ogg";
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/webm", "video/webm" -> "webm";
            default -> "bin";
        };
    }

    private static String normalizeMime(String ct, String filename) {
        String m = ct == null ? "" : ct.split(";")[0].trim().toLowerCase();
        if (!m.isEmpty() && !"application/octet-stream".equals(m)) {
            return m;
        }
        String n = filename == null ? "" : filename.toLowerCase();
        int dot = n.lastIndexOf('.');
        String e = dot >= 0 ? n.substring(dot + 1) : "";
        return switch (e) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "webm" -> "audio/webm";
            default -> m.isEmpty() ? "application/octet-stream" : m;
        };
    }
}
