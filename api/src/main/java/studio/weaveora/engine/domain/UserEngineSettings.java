package studio.weaveora.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 用户级生成引擎配置：图片/视频可分别走 GPU 或云 API；凭据以密文入库。 */
@Table(name = "user_engine_settings")
@jakarta.persistence.Entity
public class UserEngineSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "image_engine", nullable = false)
    private String imageEngine = "gpu";

    @Column(name = "video_engine", nullable = false)
    private String videoEngine = "gpu";

    @Column(name = "image_cloud_base_url")
    private String imageCloudBaseUrl;

    @Column(name = "image_cloud_auth_type", nullable = false)
    private String imageCloudAuthType = "api_key";

    @Column(name = "image_cloud_api_key_cipher")
    private String imageCloudApiKeyCipher;

    @Column(name = "image_cloud_username")
    private String imageCloudUsername;

    @Column(name = "image_cloud_password_cipher")
    private String imageCloudPasswordCipher;

    @Column(name = "image_cloud_model")
    private String imageCloudModel;

    @Column(name = "video_cloud_api_key_cipher")
    private String videoCloudApiKeyCipher;

    @Column(name = "video_cloud_model")
    private String videoCloudModel;

    @Column(name = "gpu_server_url")
    private String gpuServerUrl;

    @Column(name = "gpu_server_port")
    private Integer gpuServerPort;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserEngineSettings() {
    }

    public static UserEngineSettings defaults(UUID userId) {
        UserEngineSettings s = new UserEngineSettings();
        s.userId = userId;
        return s;
    }

    public UUID userId() { return userId; }
    public String imageEngine() { return imageEngine; }
    public void setImageEngine(String v) { this.imageEngine = v == null || v.isBlank() ? "gpu" : v; }
    public String videoEngine() { return videoEngine; }
    public void setVideoEngine(String v) { this.videoEngine = v == null || v.isBlank() ? "gpu" : v; }
    public String imageCloudBaseUrl() { return imageCloudBaseUrl; }
    public void setImageCloudBaseUrl(String v) { this.imageCloudBaseUrl = blankToNull(v); }
    public String imageCloudAuthType() { return imageCloudAuthType; }
    public void setImageCloudAuthType(String v) { this.imageCloudAuthType = "basic".equals(v) ? "basic" : "api_key"; }
    public String imageCloudApiKeyCipher() { return imageCloudApiKeyCipher; }
    public void setImageCloudApiKeyCipher(String v) { this.imageCloudApiKeyCipher = v; }
    public String imageCloudUsername() { return imageCloudUsername; }
    public void setImageCloudUsername(String v) { this.imageCloudUsername = blankToNull(v); }
    public String imageCloudPasswordCipher() { return imageCloudPasswordCipher; }
    public void setImageCloudPasswordCipher(String v) { this.imageCloudPasswordCipher = v; }
    public String imageCloudModel() { return imageCloudModel; }
    public void setImageCloudModel(String v) { this.imageCloudModel = blankToNull(v); }
    public String videoCloudApiKeyCipher() { return videoCloudApiKeyCipher; }
    public void setVideoCloudApiKeyCipher(String v) { this.videoCloudApiKeyCipher = v; }
    public String videoCloudModel() { return videoCloudModel; }
    public void setVideoCloudModel(String v) { this.videoCloudModel = blankToNull(v); }
    public String gpuServerUrl() { return gpuServerUrl; }
    public void setGpuServerUrl(String v) { this.gpuServerUrl = blankToNull(v); }
    public Integer gpuServerPort() { return gpuServerPort; }
    public void setGpuServerPort(Integer v) { this.gpuServerPort = v; }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
