package studio.weaveora.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.engine.api.EngineSettingsRequest;
import studio.weaveora.engine.api.EngineSettingsResponse;
import studio.weaveora.engine.domain.UserEngineSettings;
import studio.weaveora.engine.domain.UserEngineSettingsRepository;
import studio.weaveora.infra.crypto.AesGcm;

import java.util.Map;
import java.util.UUID;

/** 用户级引擎设置：读写（凭据密文入库、响应打码）与任务路由判定。 */
@Service
public class EngineSettingsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineSettingsService.class);

    private final UserEngineSettingsRepository repo;
    private final ModelSchemaService schemaService;
    private final String storeKey;

    public EngineSettingsService(UserEngineSettingsRepository repo, ModelSchemaService schemaService,
                                 @Value("${weaveora.store-key:}") String storeKey) {
        this.repo = repo;
        this.schemaService = schemaService;
        this.storeKey = storeKey;
    }

    @Transactional(readOnly = true)
    public UserEngineSettings load(UUID userId) {
        return repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
    }

    /** 任务路由：kind 为 clip → 视频引擎，其余（still 等）→ 图片引擎。 */
    @Transactional(readOnly = true)
    public String resolveEngine(UUID userId, String kind) {
        UserEngineSettings s = load(userId);
        String e = "clip".equals(kind) ? s.videoEngine() : s.imageEngine();
        return "cloud".equals(e) ? "cloud" : "gpu";
    }

    /** 云执行凭据（internal/worker 通道使用，一次性返回明文）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> cloudPlain(UUID userId) {
        UserEngineSettings s = load(userId);
        return java.util.Map.of(
                "image", java.util.Map.of(
                        "baseUrl", str(s.imageCloudBaseUrl()),
                        "authType", s.imageCloudAuthType() == null ? "api_key" : s.imageCloudAuthType(),
                        "apiKey", str(AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher())),
                        "username", str(s.imageCloudUsername()),
                        "password", str(AesGcm.decrypt(storeKey, s.imageCloudPasswordCipher())),
                        "model", str(s.imageCloudModel()),
                        // P12：把 schema 归一化出的参数映射 + 用户全局参数一并下发，
                        // worker 才能按「这个模型真正认识的字段名」填参（参考图字段名各模型不同）
                        "mapping", s.imageModelSchema() == null ? java.util.Map.of()
                                : java.util.Map.of("m", s.imageModelSchema().path("mapping")),
                        "params", s.imageParams() == null ? java.util.Map.of() : s.imageParams(),
                        "schemaParams", s.imageModelSchema() == null ? java.util.List.of()
                                : s.imageModelSchema().path("params")),
                "video", java.util.Map.of(
                        "apiKey", str(AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher())),
                        "model", str(s.videoCloudModel()),
                        "mapping", s.videoModelSchema() == null ? java.util.Map.of()
                                : java.util.Map.of("m", s.videoModelSchema().path("mapping")),
                        "params", s.videoParams() == null ? java.util.Map.of() : s.videoParams(),
                        "schemaParams", s.videoModelSchema() == null ? java.util.List.of()
                                : s.videoModelSchema().path("params")));
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }
    @Transactional(readOnly = true)
    public EngineSettingsResponse toResponse(UUID userId) {
        UserEngineSettings s = load(userId);
        String imgKey = AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher());
        String vidKey = AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher());
        boolean pwdSet = AesGcm.decrypt(storeKey, s.imageCloudPasswordCipher()) != null;
        return new EngineSettingsResponse(
                s.imageEngine(), s.videoEngine(),
                s.imageCloudBaseUrl(), s.imageCloudAuthType(), s.imageCloudModel(),
                AesGcm.mask(imgKey), s.imageCloudUsername(), pwdSet,
                s.videoCloudModel(), AesGcm.mask(vidKey),
                s.gpuServerUrl(), s.gpuServerPort(),
                s.imageModelSchema(), s.videoModelSchema(), s.imageParams(), s.videoParams());
    }

    /**
     * P12：主动刷新模型调用参数说明（配了/换了模型就该知道它认哪些参数）。
     *
     * <p>失败只记日志不抛（保存配置不该被外部接口拖挂）；返回值里 schema 仍为旧值。
     */
    @Transactional
    public EngineSettingsResponse refreshSchemas(UUID userId, boolean image, boolean video) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        if (image && s.imageCloudModel() != null && !s.imageCloudModel().isBlank()) {
            try {
                var schema = schemaService.fetchReplicate(s.imageCloudModel(),
                        AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher()));
                if (schema != null) {
                    s.setImageModelSchema(schema);
                    s.setImageModelSchemaAt(java.time.OffsetDateTime.now());
                    log.info("model schema refreshed user={} kind=image model={} params={} refsField={}",
                            userId, s.imageCloudModel(), schema.path("params").size(),
                            schema.path("mapping").path("refs").asText("-"));
                }
            } catch (RuntimeException e) {
                log.warn("拉取图片模型参数失败 user={} model={}: {}", userId, s.imageCloudModel(), e.getMessage());
            }
        }
        if (video && s.videoCloudModel() != null && !s.videoCloudModel().isBlank()) {
            try {
                var schema = schemaService.fetchReplicate(s.videoCloudModel(),
                        AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher()));
                if (schema != null) {
                    s.setVideoModelSchema(schema);
                    s.setVideoModelSchemaAt(java.time.OffsetDateTime.now());
                    log.info("model schema refreshed user={} kind=video model={} params={} refsField={}",
                            userId, s.videoCloudModel(), schema.path("params").size(),
                            schema.path("mapping").path("refs").asText("-"));
                }
            } catch (RuntimeException e) {
                log.warn("拉取视频模型参数失败 user={} model={}: {}", userId, s.videoCloudModel(), e.getMessage());
            }
        }
        repo.save(s);
        return toResponse(userId);
    }

    @Transactional
    public EngineSettingsResponse update(UUID userId, EngineSettingsRequest req) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        if (req.imageEngine() != null) s.setImageEngine(req.imageEngine());
        if (req.videoEngine() != null) s.setVideoEngine(req.videoEngine());
        if (req.imageCloudBaseUrl() != null) s.setImageCloudBaseUrl(req.imageCloudBaseUrl());
        if (req.imageCloudAuthType() != null) s.setImageCloudAuthType(req.imageCloudAuthType());
        if (req.imageCloudApiKey() != null && !req.imageCloudApiKey().isBlank()) {
            s.setImageCloudApiKeyCipher(AesGcm.encrypt(storeKey, req.imageCloudApiKey().trim()));
        }
        if (req.imageCloudUsername() != null) s.setImageCloudUsername(req.imageCloudUsername());
        if (req.imageCloudPassword() != null && !req.imageCloudPassword().isBlank()) {
            s.setImageCloudPasswordCipher(AesGcm.encrypt(storeKey, req.imageCloudPassword()));
        }
        boolean imageModelChanged = req.imageCloudModel() != null
                && !req.imageCloudModel().trim().equals(str(s.imageCloudModel()));
        if (req.imageCloudModel() != null) s.setImageCloudModel(req.imageCloudModel());
        if (req.videoCloudApiKey() != null && !req.videoCloudApiKey().isBlank()) {
            s.setVideoCloudApiKeyCipher(AesGcm.encrypt(storeKey, req.videoCloudApiKey().trim()));
        }
        boolean videoModelChanged = req.videoCloudModel() != null
                && !req.videoCloudModel().trim().equals(str(s.videoCloudModel()));
        if (req.videoCloudModel() != null) s.setVideoCloudModel(req.videoCloudModel());
        if (req.gpuServerUrl() != null) s.setGpuServerUrl(req.gpuServerUrl());
        if (req.gpuServerPort() != null) s.setGpuServerPort(req.gpuServerPort());
        // P12：全局参数（画质等）按 schema 收口——只留该模型真认识的键，防手改坏调用
        if (req.imageParams() != null) {
            s.setImageParams(schemaService.sanitizeParams(s.imageModelSchema(), req.imageParams()));
        }
        if (req.videoParams() != null) {
            s.setVideoParams(schemaService.sanitizeParams(s.videoModelSchema(), req.videoParams()));
        }
        repo.save(s);
        // 换了模型 → 主动拉一次它的调用说明（失败不阻塞保存）
        if (imageModelChanged || videoModelChanged) {
            return refreshSchemas(userId, imageModelChanged, videoModelChanged);
        }
        return toResponse(userId);
    }
}
