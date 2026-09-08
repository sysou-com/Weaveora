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

    private final UserEngineSettingsRepository repo;
    private final String storeKey;

    public EngineSettingsService(UserEngineSettingsRepository repo,
                                 @Value("${weaveora.store-key:}") String storeKey) {
        this.repo = repo;
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
                        "model", str(s.imageCloudModel())),
                "video", java.util.Map.of(
                        "apiKey", str(AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher())),
                        "model", str(s.videoCloudModel())));
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
                s.gpuServerUrl(), s.gpuServerPort());
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
        if (req.imageCloudModel() != null) s.setImageCloudModel(req.imageCloudModel());
        if (req.videoCloudApiKey() != null && !req.videoCloudApiKey().isBlank()) {
            s.setVideoCloudApiKeyCipher(AesGcm.encrypt(storeKey, req.videoCloudApiKey().trim()));
        }
        if (req.videoCloudModel() != null) s.setVideoCloudModel(req.videoCloudModel());
        if (req.gpuServerUrl() != null) s.setGpuServerUrl(req.gpuServerUrl());
        if (req.gpuServerPort() != null) s.setGpuServerPort(req.gpuServerPort());
        repo.save(s);
        return toResponse(userId);
    }
}
