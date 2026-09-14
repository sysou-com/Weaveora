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

    /**
     * GPU 服务器「最大支持分辨率」（机器能力）：480p / 720p / 1080p / auto。
     * motion 出片尺寸会被压到该上限以内（换 GPU 卡就改这个，而不是逐镜调）。
     */
    @Column(name = "gpu_max_resolution")
    private String gpuMaxResolution;

    // P12：模型 input schema 缓存（展示「调用说明」+ 归一化参数映射给 worker）
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "image_model_schema")
    private com.fasterxml.jackson.databind.JsonNode imageModelSchema;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "video_model_schema")
    private com.fasterxml.jackson.databind.JsonNode videoModelSchema;

    @Column(name = "image_model_schema_at")
    private OffsetDateTime imageModelSchemaAt;

    @Column(name = "video_model_schema_at")
    private OffsetDateTime videoModelSchemaAt;

    /** 用户可改的全局参数（画质等），键需在 schema 里存在 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "image_params")
    private com.fasterxml.jackson.databind.JsonNode imageParams;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "video_params")
    private com.fasterxml.jackson.databind.JsonNode videoParams;

    /** 拉取模型参数说明失败的原因（有值时界面应提示“需重新刷新”，而不是继续显示旧 schema） */
    @Column(name = "image_model_schema_error")
    private String imageModelSchemaError;

    @Column(name = "video_model_schema_error")
    private String videoModelSchemaError;

    /** 网关通道（OpenAI Images 兼容）单次最多参考图张数；0/空 = 未知 */
    @Column(name = "gateway_refs_max")
    private Integer gatewayRefsMax;

    /** 网关通道：用户粘贴的示例请求（curl / JSON body），用于解析出参数格式 */
    @Column(name = "gateway_sample")
    private String gatewaySample;

    /**
     * 服务地址（配音/配乐、对口型、转写、人脸）——见 V15 迁移注释里的结构。
     *
     * <p>为什么放这里：这些服务原先由 worker 机器的环境变量决定，换 GPU 服务器就得改脚本、
     * 重启 worker；收敛到用户级配置后可随时切换（worker 按任务下发，空值回退环境变量）。
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "services")
    private com.fasterxml.jackson.databind.JsonNode services;

    /** 已配置模型库（图片/视频各一份 JSON 数组；元素含 baseUrl/model/params/schema…） */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "image_model_presets")
    private com.fasterxml.jackson.databind.JsonNode imageModelPresets;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "video_model_presets")
    private com.fasterxml.jackson.databind.JsonNode videoModelPresets;

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
    public com.fasterxml.jackson.databind.JsonNode imageModelSchema() { return imageModelSchema; }
    public void setImageModelSchema(com.fasterxml.jackson.databind.JsonNode v) { this.imageModelSchema = v; }
    public com.fasterxml.jackson.databind.JsonNode videoModelSchema() { return videoModelSchema; }
    public void setVideoModelSchema(com.fasterxml.jackson.databind.JsonNode v) { this.videoModelSchema = v; }
    public OffsetDateTime imageModelSchemaAt() { return imageModelSchemaAt; }
    public void setImageModelSchemaAt(OffsetDateTime v) { this.imageModelSchemaAt = v; }
    public OffsetDateTime videoModelSchemaAt() { return videoModelSchemaAt; }
    public void setVideoModelSchemaAt(OffsetDateTime v) { this.videoModelSchemaAt = v; }
    public com.fasterxml.jackson.databind.JsonNode imageParams() { return imageParams; }
    public void setImageParams(com.fasterxml.jackson.databind.JsonNode v) { this.imageParams = v; }
    public com.fasterxml.jackson.databind.JsonNode videoParams() { return videoParams; }
    public void setVideoParams(com.fasterxml.jackson.databind.JsonNode v) { this.videoParams = v; }
    public String imageModelSchemaError() { return imageModelSchemaError; }
    public void setImageModelSchemaError(String v) { this.imageModelSchemaError = v; }
    public String videoModelSchemaError() { return videoModelSchemaError; }
    public void setVideoModelSchemaError(String v) { this.videoModelSchemaError = v; }
    public com.fasterxml.jackson.databind.JsonNode imageModelPresets() { return imageModelPresets; }
    public void setImageModelPresets(com.fasterxml.jackson.databind.JsonNode v) { this.imageModelPresets = v; }
    public com.fasterxml.jackson.databind.JsonNode videoModelPresets() { return videoModelPresets; }
    public void setVideoModelPresets(com.fasterxml.jackson.databind.JsonNode v) { this.videoModelPresets = v; }
    public String gatewaySample() { return gatewaySample; }
    public void setGatewaySample(String v) { this.gatewaySample = v; }
    public Integer gatewayRefsMax() { return gatewayRefsMax; }
    public void setGatewayRefsMax(Integer v) { this.gatewayRefsMax = v; }
    public com.fasterxml.jackson.databind.JsonNode services() { return services; }
    public void setServices(com.fasterxml.jackson.databind.JsonNode v) { this.services = v; }
    public String gpuMaxResolution() { return gpuMaxResolution; }

    public void setGpuMaxResolution(String v) { this.gpuMaxResolution = blankToNull(v); }

    public Integer gpuServerPort() { return gpuServerPort; }
    public void setGpuServerPort(Integer v) { this.gpuServerPort = v; }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
