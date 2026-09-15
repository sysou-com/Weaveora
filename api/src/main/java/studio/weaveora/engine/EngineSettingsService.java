package studio.weaveora.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import studio.weaveora.engine.api.EngineSettingsRequest;
import studio.weaveora.engine.api.EngineSettingsResponse;
import studio.weaveora.engine.domain.UserEngineSettings;
import studio.weaveora.engine.domain.UserEngineSettingsRepository;
import studio.weaveora.infra.crypto.AesGcm;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.Map;
import java.util.UUID;

/** 用户级引擎设置：读写（凭据密文入库、响应打码）与任务路由判定。 */
@Service
public class EngineSettingsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineSettingsService.class);

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final UserEngineSettingsRepository repo;
    private final ModelSchemaService schemaService;
    private final String storeKey;
    /** 语音/转写服务默认地址（weaveora.tts-url；worker 侧默认 :8091，此处含 ssh 隧道场景默认 :18091） */
    private final String defaultTtsUrl;

    public EngineSettingsService(UserEngineSettingsRepository repo, ModelSchemaService schemaService,
                                 @Value("${weaveora.store-key:}") String storeKey,
                                 @Value("${weaveora.tts-url:http://127.0.0.1:18091}") String ttsUrl) {
        this.repo = repo;
        this.schemaService = schemaService;
        this.storeKey = storeKey;
        this.defaultTtsUrl = (ttsUrl == null || ttsUrl.isBlank()) ? "http://127.0.0.1:18091" : ttsUrl;
    }

    /**
     * 服务地址默认值（未配置时回退到这里；也是 worker 端环境变量默认值的镜像）。
     *
     * <p>换 GPU 服务器时只需在「生成引擎配置 → 服务地址」里改这几项，不用再去改 worker 脚本、重启 worker。
     */
    public com.fasterxml.jackson.databind.node.ObjectNode servicesWithDefaults(UserEngineSettings s) {
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        com.fasterxml.jackson.databind.JsonNode cur = s == null ? null : s.services();
        // ★ 两类地址要分清（踩过坑，不要混）：
        //   ① **机器本地**地址（127.0.0.1:8091 这类）绝不能由 API 侧填默认值 —— API 主机与 worker 主机不是同一台，
        //      填了会让 worker 用错端口（当时 API 默认 tts=127.0.0.1:18091 是 API 机上的 SSH 隧道口，
        //      worker 机的 TTS 在 :8091，下发过去配音直接失败）。所以 gpu 为空时一律留空 = 用 worker 自己的默认。
        //   ② 但**用户已在配置页填了「GPU 服务器地址」**时，各服务都从它推导：
        //      `<gpu>:8001`（ComfyUI）、`<gpu>/audio`（配音/转写）、`<gpu>/talk`（整脸口型）、`<gpu>`（人脸）。
        //      理由：**GPU 公网 IP/端口会变**，只改「GPU 服务器地址」这一处就能全量跟随，
        //      不必也不应该把 IP 写进 worker 脚本 / 部署脚本 / 代码。
        String gpu = gpuComfyUrlOrEmpty(s); // 已带端口；空 = 用户没配 GPU
        out.set("tts", merge(cur, "tts", urlNode(gpu, "/audio")));
        out.set("music", merge(cur, "music", mapper.createObjectNode()
                .put("engine", "comfy").put("url", "").put("ckpt", "")));
        out.set("lipsync", merge(cur, "lipsync", mapper.createObjectNode()
                .put("comfyUrl", gpu).put("workflow", "")
                .put("timeout", 1800).put("fps", 0)));
        out.set("transcribe", merge(cur, "transcribe", urlNode(gpu, "/audio")));
        out.set("face", merge(cur, "face", mapper.createObjectNode()
                .put("url", gpu).put("latentsyncDir", "")));
        // ★ talk（整脸音频驱动 / jaw-lip）：喊叫、尖叫、吟唱这类"嘴大张"镜，用它替代 LatentSync。
        //   跑在 GPU 机的 talk_server.py（默认 :8094，网关路径 /talk）。
        //   jawGain=1.0 零改动（原生 EchoMimic 输出），1.25 = 增强档（按响度包络把下半脸再往下拉）。
        out.set("talk", merge(cur, "talk", mapper.createObjectNode()
                .put("url", gpu.isBlank() ? "" : gpu + "/talk")
                .put("enabled", true).put("jawGain", 1.0)));
        // ★ image（文生图，本机 ComfyUI）：engine=comfy 时 worker 直接把 workflow（文生图）/
        //   img2imgWorkflow（关键帧当底图）两个 API 格式 JSON POST 给 ComfyUI（当前 Qwen-Image + Lightning 8 步）。
        //   两个路径是 **worker 机器上的绝对路径**（装在哪台机就填哪台的）→ 默认留空 = worker 自带默认（老 SDXL 路线）。
        out.set("image", merge(cur, "image", mapper.createObjectNode()
                .put("engine", "builtin").put("comfyUrl", gpu)
                .put("workflow", "").put("img2imgWorkflow", "").put("model", "")
                .put("steps", 0).put("denoise", 0.65)));
        // ★ motion：自托管图生视频（Wan2.2 I2V-A14B 双专家）的**档位**随任务下发。
        //   为什么必须走这里：clip 的 payload.params 在 JobService.videoShotPayload() 里只塞了
        //   {width,height}，preset/steps/lora_*/cfg_* 若不靠这条链路下发就永远到不了 worker ——
        //   表现为「在『生成引擎配置 → 视频参数』里改了档位，出片毫无变化」。
        //   与 tts/face 同构：用户显式配了才覆盖，没配就是空对象（worker 用自己的默认档）。
        out.set("motion", motionServices(cur, s));
        return out;
    }

    /** 能从「视频参数」透传到自托管 motion 引擎的键（白名单，避免把云模型字段塞进图里）。 */
    private static final java.util.Set<String> MOTION_KEYS = java.util.Set.of(
            "preset", "steps", "switch", "switch_step", "cfg", "cfg_high", "cfg_low",
            "lora_high", "lora_low", "lora_high_name", "lora_low_name", "shift",
            "sampler_name", "scheduler", "model_high", "model_low",
            "mode", "dual", "width", "height", "frames", "fps", "resolution");

    /**
     * 视频引擎是自托管（GPU）时，sanitize 之后仍要保住 motion 档位键。
     *
     * 为什么：sanitizeParams 在「有 schema」时只留 schema 认识的 userEditable 键 ——
     * 而 schema 描述的是**云视频模型**，根本不包含 preset/lora_high 这些自托管采参。
     * 一旦用户刷新过云模型 schema，他们填的档位就会被**静默丢弃**（表现为「UI 配了没效果」）。
     * 所以 engine=gpu 时把这些键从原值里补回。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode keepMotionKeys(
            com.fasterxml.jackson.databind.JsonNode sanitized,
            com.fasterxml.jackson.databind.JsonNode raw,
            String videoEngine) {
        com.fasterxml.jackson.databind.node.ObjectNode out = (sanitized != null && sanitized.isObject())
                ? ((com.fasterxml.jackson.databind.node.ObjectNode) sanitized).deepCopy()
                : mapper.createObjectNode();
        if ("gpu".equals(videoEngine) && raw != null && raw.isObject()) {
            raw.fields().forEachRemaining(e -> {
                String key = snake(e.getKey());
                if (MOTION_KEYS.contains(key) && e.getValue() != null && !e.getValue().isNull()) {
                    out.set(key, e.getValue());
                }
            });
        }
        return out;
    }

    /** camelCase → snake_case（前端两种写法都可能出现，统一收拢成 worker 认识的键）。 */
    private static String snake(String k) {
        StringBuilder b = new StringBuilder();
        for (char c : k.toCharArray()) {
            if (Character.isUpperCase(c)) {
                b.append('_').append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** 自托管 motion 档位：services.motion 显式值 + 用户「视频参数」里的采样键（白名单）。 */
    private com.fasterxml.jackson.databind.node.ObjectNode motionServices(
            com.fasterxml.jackson.databind.JsonNode cur, UserEngineSettings s) {
        com.fasterxml.jackson.databind.node.ObjectNode merged = (com.fasterxml.jackson.databind.node.ObjectNode)
                merge(cur, "motion", mapper.createObjectNode());
        // GPU 服务器「最大支持分辨率」→ motion 的默认出片上限（用户若在视频参数里显式写了
        // resolution，由下面的循环覆盖它 —— 逐项优先）。这是**机器能力**，换卡改这里即可。
        if (s != null && s.gpuMaxResolution() != null && !s.gpuMaxResolution().isBlank()) {
            merged.put("resolution", s.gpuMaxResolution().trim());
        }
        if (s != null && s.videoParams() != null && s.videoParams().isObject()) {
            s.videoParams().fields().forEachRemaining(e -> {
                String key = snake(e.getKey());
                // switch 是 switch_step 的别名；两个都收
                if (MOTION_KEYS.contains(key)) {
                    merged.set(key, e.getValue());
                }
            });
        }
        return merged;
    }

    /**
     * 用户配了 GPU 服务器 → 推导 ComfyUI 地址（对口型/配乐默认走它）；没配就返回空
     * （空 = 用 worker 机器自己的 WEAVEORA_COMFY_URL 默认值，不要替它猜）。
     */
    private static String gpuComfyUrlOrEmpty(UserEngineSettings s) {
        if (s == null || s.gpuServerUrl() == null || s.gpuServerUrl().isBlank()) {
            return "";
        }
        String base = s.gpuServerUrl().trim().replaceAll("/+$", "");
        Integer port = s.gpuServerPort();
        if (port == null || port <= 0 || base.matches(".*:\\d+$")) {
            return base;
        }
        return base + ":" + port;
    }

    /**
     * 服务地址节点：**配了 GPU 服务器地址**时推导成 `<gpu><path>`，否则留空（= 用 worker 机器自己的默认）。
     *
     * <p>为什么这么定：GPU 公网 IP/端口会变，用户只该在「生成引擎配置 → GPU 服务器地址」改一处；
     * 把 IP 填进 worker 脚本/部署脚本/代码都会在换机后静默失效。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode urlNode(String gpuBase, String path) {
        com.fasterxml.jackson.databind.node.ObjectNode n = mapper.createObjectNode();
        n.put("url", (gpuBase == null || gpuBase.isBlank()) ? "" : gpuBase + path);
        return n;
    }

    /** 取某项服务配置（用户值覆盖默认值，逐字段合并）。
     *
     * <p>★ 关键：用户值为 **null / 缺失** 一律视为「未配置」→ **保留推导值/默认值**，不能用 null 覆盖。
     * 踩过的坑（2026-09-15）：前端表单用 `value || null` 提交空输入框，若让 null 覆盖，
     * 用户每次保存都会把从「GPU 服务器地址」推导出来的 comfyUrl/talk.url 洗掉 → worker 拿到 null，
     * 只能回退到自己的环境变量（可能就是另一台/旧地址）。
     */
    private static com.fasterxml.jackson.databind.JsonNode merge(
            com.fasterxml.jackson.databind.JsonNode cur, String key,
            com.fasterxml.jackson.databind.node.ObjectNode defaults) {
        if (cur == null || !cur.isObject() || !cur.path(key).isObject()) {
            return defaults;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = defaults.deepCopy();
        cur.path(key).fields().forEachRemaining(e -> {
            if (e.getValue() == null || e.getValue().isNull()) {
                return;   // null = 未配置 → 不覆盖
            }
            out.set(e.getKey(), e.getValue());
        });
        return out;
    }

    /** worker 用：某用户的服务地址（已填默认值）。 */
    @Transactional(readOnly = true)
    public com.fasterxml.jackson.databind.JsonNode servicesOf(UUID userId) {
        return servicesWithDefaults(load(userId));
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
                                : s.imageModelSchema().path("params"),
                        // 参考图上限：后台固定 15 张（不再让用户配；超出 worker 裁剪并告警 —— 防手滑烧 API 费用）
                        "refsMax", 15),
                "video", java.util.Map.of(
                        "apiKey", str(AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher())),
                        "model", str(s.videoCloudModel()),
                        "mapping", s.videoModelSchema() == null ? java.util.Map.of()
                                : java.util.Map.of("m", s.videoModelSchema().path("mapping")),
                        "params", s.videoParams() == null ? java.util.Map.of() : s.videoParams(),
                        "schemaParams", s.videoModelSchema() == null ? java.util.List.of()
                                : s.videoModelSchema().path("params")),
                "services", servicesWithDefaults(s));
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }
    @Transactional(readOnly = true)
    public EngineSettingsResponse toResponse(UUID userId) {
        UserEngineSettings s = load(userId);
        this.current = s;
        String imgKey = AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher());
        String vidKey = AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher());
        boolean pwdSet = AesGcm.decrypt(storeKey, s.imageCloudPasswordCipher()) != null;
        return new EngineSettingsResponse(
                s.imageEngine(), s.videoEngine(),
                s.imageCloudBaseUrl(), s.imageCloudAuthType(), s.imageCloudModel(),
                AesGcm.mask(imgKey), s.imageCloudUsername(), pwdSet,
                s.videoCloudModel(), AesGcm.mask(vidKey),
                s.gpuServerUrl(), s.gpuServerPort(), s.gpuMaxResolution(),
                fresh(true) ? s.imageModelSchema() : null,
                fresh(false) ? s.videoModelSchema() : null,
                s.imageParams(), s.videoParams(),
                s.imageModelSchemaError(), s.videoModelSchemaError(), s.gatewayRefsMax(),
                s.gatewaySample(), s.imageModelPresets(), s.videoModelPresets(),
                servicesWithDefaults(s));
    }

    // ---------------------------------------------------------------- P12 模型库

    /** 列出模型库（kind=image|video）。 */
    @Transactional(readOnly = true)
    public JsonNode listPresets(UUID userId, String kind) {
        UserEngineSettings s = load(userId);
        JsonNode arr = "video".equals(kind) ? s.videoModelPresets() : s.imageModelPresets();
        return arr == null || !arr.isArray() ? mapper.createArrayNode() : arr;
    }

    /**
     * 新增/更新一个模型条目，并**顺手刷新它的参数说明**。
     *
     * <p>为什么在这做：换模型/改参数后必须重新解析参数格式（Replicate 拉 schema；网关解析示例），
     * 否则界面展示的会是上一个模型的说明（实测踩过）。
     */
    @Transactional
    public EngineSettingsResponse upsertPreset(UUID userId, studio.weaveora.engine.api.ModelPresetRequest req) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(req.kind());
        String model = req.model() == null ? "" : req.model().trim();
        if (model.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "模型名不能为空");
        }
        String baseUrl = req.baseUrl() == null ? "" : req.baseUrl().trim();
        boolean apply = req.apply() != null && req.apply();
        if (apply) {
            if (video) {
                s.setVideoCloudModel(model);
            } else {
                s.setImageCloudBaseUrl(baseUrl);
                s.setImageCloudModel(model);
            }
        }
        if (!video) {
            if (req.gatewayRefsMax() != null) {
                s.setGatewayRefsMax(req.gatewayRefsMax() >= 0 ? req.gatewayRefsMax() : null);
            }
            if (req.gatewaySample() != null) {
                s.setGatewaySample(req.gatewaySample().isBlank() ? null : req.gatewaySample());
            }
        }
        if (req.params() != null) {
            if (video) {
                s.setVideoParams(keepMotionKeys(schemaService.sanitizeParams(s.videoModelSchema(), req.params()),
                        req.params(), s.videoEngine()));
            } else {
                s.setImageParams(schemaService.sanitizeParams(s.imageModelSchema(), req.params()));
            }
        }
        repo.save(s);
        refreshSchemas(userId, !video, video);
        UserEngineSettings fresh = repo.findByUserId(userId).orElse(s);
        this.current = fresh;
        upsertPresetInto(fresh, video, baseUrl, model, presetEntry(fresh, video, baseUrl, model));
        repo.save(fresh);
        return toResponse(userId);
    }

    /**
     * 视频模型 schema 里「帧数」字段的上限（如 Wan 的 num_frames.max=121）。
     *
     * <p>「运动帧数」的可用上限必须按**模型**收口：本机 GPU 由显存决定，
     * 云模型由它自己声明的 max 决定（旧实现只用一个全局配置 300，比模型大 -> 送出去会被拒/被截）。
     */
    public Integer videoSchemaFramesMax(UUID userId) {
        UserEngineSettings s = current;
        if (s == null || !userId.equals(s.userId())) {
            s = repo.findByUserId(userId).orElse(null);
        }
        if (s == null) {
            return null;
        }
        return framesMaxOf(s.videoModelSchema());
    }

    private static Integer framesMaxOf(com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode params = schema.path("params");
        if (!params.isArray()) {
            return null;
        }
        for (String cand : java.util.List.of("num_frames", "frames", "video_length", "length", "frame_count")) {
            for (com.fasterxml.jackson.databind.JsonNode pn : params) {
                if (cand.equals(pn.path("name").asText("")) && pn.path("max").isNumber()) {
                    return (int) Math.round(pn.path("max").asDouble());
                }
            }
        }
        return null;
    }

    /** 只刷新某个条目的参数说明（界面上的「刷新参数说明」）。 */
    @Transactional
    public EngineSettingsResponse refreshPreset(UUID userId, String kind, String baseUrl, String model) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(kind);
        if (baseUrl != null && !baseUrl.isBlank() && !video) {
            s.setImageCloudBaseUrl(baseUrl.trim());
        }
        if (model != null && !model.isBlank()) {
            if (video) {
                s.setVideoCloudModel(model.trim());
            } else {
                s.setImageCloudModel(model.trim());
            }
        }
        repo.save(s);
        refreshSchemas(userId, !video, video);
        UserEngineSettings fresh = repo.findByUserId(userId).orElse(s);
        this.current = fresh;
        upsertPresetInto(fresh, video, baseUrl, model, presetEntry(fresh, video, baseUrl, model));
        repo.save(fresh);
        return toResponse(userId);
    }

    /** 删除一个条目。 */
    @Transactional
    public EngineSettingsResponse deletePreset(UUID userId, String kind, String baseUrl, String model) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        boolean video = "video".equals(kind);
        ArrayNode keep = mapper.createArrayNode();
        for (JsonNode e : arr(s, video)) {
            if (!sameKey(e, baseUrl, model)) {
                keep.add(e);
            }
        }
        if (video) {
            s.setVideoModelPresets(keep);
        } else {
            s.setImageModelPresets(keep);
        }
        repo.save(s);
        return toResponse(userId);
    }

    private JsonNode arr(UserEngineSettings s, boolean video) {
        JsonNode a = video ? s.videoModelPresets() : s.imageModelPresets();
        return a != null && a.isArray() ? a : mapper.createArrayNode();
    }

    private static boolean sameKey(JsonNode e, String baseUrl, String model) {
        return e.path("model").asText("").equals(model == null ? "" : model.trim())
                && e.path("baseUrl").asText("").equals(baseUrl == null ? "" : baseUrl.trim());
    }

    /** 组装一条库条目（含该条目对应的参数说明/参数）。 */
    private ObjectNode presetEntry(UserEngineSettings s, boolean video, String baseUrl, String model) {
        ObjectNode e = mapper.createObjectNode();
        e.put("baseUrl", baseUrl == null ? "" : baseUrl.trim());
        e.put("model", model == null ? "" : model.trim());
        e.set("params", video ? s.videoParams() : s.imageParams());
        e.set("schema", video ? s.videoModelSchema() : s.imageModelSchema());
        java.time.OffsetDateTime at = video ? s.videoModelSchemaAt() : s.imageModelSchemaAt();
        e.put("schemaAt", at == null ? "" : at.toString());
        String err = video ? s.videoModelSchemaError() : s.imageModelSchemaError();
        e.put("schemaError", err == null ? "" : err);
        if (!video) {
            e.put("gatewayRefsMax", s.gatewayRefsMax() == null ? 0 : s.gatewayRefsMax());
            e.put("gatewaySample", s.gatewaySample() == null ? "" : s.gatewaySample());
        }
        e.put("updatedAt", java.time.OffsetDateTime.now().toString());
        return e;
    }

    /** 按 (kind, baseUrl, model) 覆盖写入模型库。 */
    private void upsertPresetInto(UserEngineSettings s, boolean video, String baseUrl, String model,
                                  ObjectNode entry) {
        ArrayNode keep = mapper.createArrayNode();
        for (JsonNode e : arr(s, video)) {
            if (!sameKey(e, baseUrl, model)) {
                keep.add(e);
            }
        }
        keep.add(entry);
        if (video) {
            s.setVideoModelPresets(keep);
        } else {
            s.setImageModelPresets(keep);
        }
    }

    /** 网关通道（OpenAI Images 兼容）？网关不是 Replicate，无法自动拉 schema。 */
    private static boolean isGateway(String baseUrl) {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * 缓存的 schema 是否仍然适用于当前配置的模型。
     *
     * <p>换了模型但拉取失败时，旧 schema 会误导用户（实测：换成方舟模型后界面还在显示
     * comfyui/any-comfyui-workflow 的「input_file 只收单张 / 7 个参数」）。模型名对不上就不返回。
     */
    private boolean fresh(boolean image) {
        UserEngineSettings s = current;
        if (s == null) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode sch = image ? s.imageModelSchema() : s.videoModelSchema();
        String model = image ? s.imageCloudModel() : s.videoCloudModel();
        if (sch == null || model == null || model.isBlank()) {
            return false;
        }
        return model.trim().equals(sch.path("model").asText(""));
    }

    private UserEngineSettings current;

    /**
     * P12：主动刷新模型调用参数说明（配了/换了模型就该知道它认哪些参数）。
     *
     * <p>失败时**清空旧 schema 并记录原因**（不能让上一个模型的参数说明继续误导用户）；
     * 网关通道（方舟等 OpenAI 兼容）不拉 Replicate schema，直接给提示。
     */
    @Transactional
    public EngineSettingsResponse refreshSchemas(UUID userId, boolean image, boolean video) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
        if (image && s.imageCloudModel() != null && !s.imageCloudModel().isBlank()) {
            if (isGateway(s.imageCloudBaseUrl())) {
                // 网关通道（方舟等）：模型 id 不是 Replicate 模型，拉 Replicate 无意义。
                // 改为：① 探测 /models（存在性 + 模态 + 任务类型）② 解析用户粘贴的示例请求
                String key = AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher());
                var probe = studio.weaveora.engine.GatewayModelProbe.probeGateway(
                        s.imageCloudBaseUrl(), key, s.imageCloudModel());
                String sample = s.gatewaySample();
                if (sample != null && !sample.isBlank()) {
                    var parsed = studio.weaveora.engine.GatewayModelProbe.parseSample(sample);
                    parsed.set("gatewayProbe", probe);
                    if (!parsed.hasNonNull("model") || parsed.path("model").asText("").isEmpty()) {
                        parsed.put("model", s.imageCloudModel() == null ? "" : s.imageCloudModel());
                    }
                    s.setImageModelSchema(parsed);
                    s.setImageModelSchemaAt(java.time.OffsetDateTime.now());
                    s.setImageModelSchemaError(null);
                    log.info("网关模型参数已按示例解析 user={} model={} refsField={} params={}",
                            userId, s.imageCloudModel(), parsed.path("mapping").path("refs").asText("-"),
                            parsed.path("params").size());
                } else {
                    var minimal = mapper.createObjectNode();
                    minimal.put("provider", "gateway");
                    minimal.put("model", s.imageCloudModel() == null ? "" : s.imageCloudModel());
                    minimal.put("fetchedAt", java.time.OffsetDateTime.now().toString());
                    minimal.putArray("params");
                    var m2 = minimal.putObject("mapping");
                    m2.put("refs", "image");          // 网关（方舟/OpenAI 兼容）通行字段名
                    m2.put("refsIsArray", true);
                    minimal.putArray("notes").add("未能自动获取参数：网关（如火山方舟）只提供模型列表（模态/任务类型），"
                            + "没有逐参数规范。请在下方粘贴一段「示例请求」后点「解析示例」，即可自动识别参考图字段等参数名。");
                    minimal.set("gatewayProbe", probe);
                    s.setImageModelSchema(minimal);
                    s.setImageModelSchemaAt(java.time.OffsetDateTime.now());
                    s.setImageModelSchemaError("网关通道：已探测模型元信息但未获得参数规范 —— "
                            + "请粘贴示例请求（curl / JSON body）后点「解析示例」（参考图默认按 image 字段发送）");
                }
            } else {
                try {
                    var schema = schemaService.fetchReplicate(s.imageCloudModel(),
                            AesGcm.decrypt(storeKey, s.imageCloudApiKeyCipher()));
                    if (schema != null) {
                        s.setImageModelSchema(schema);
                        s.setImageModelSchemaAt(java.time.OffsetDateTime.now());
                        s.setImageModelSchemaError(null);
                        log.info("model schema refreshed user={} kind=image model={} params={} refsField={}",
                                userId, s.imageCloudModel(), schema.path("params").size(),
                                schema.path("mapping").path("refs").asText("-"));
                    }
                } catch (RuntimeException e) {
                    // 关键：失败要清空旧 schema，否则界面会用上一个模型的参数说明误导用户
                    s.setImageModelSchema(null);
                    s.setImageModelSchemaError(e.getMessage());
                    log.warn("拉取图片模型参数失败 user={} model={}: {}", userId, s.imageCloudModel(), e.getMessage());
                }
            }
        }
        if (video && s.videoCloudModel() != null && !s.videoCloudModel().isBlank()) {
            try {
                var schema = schemaService.fetchReplicate(s.videoCloudModel(),
                        AesGcm.decrypt(storeKey, s.videoCloudApiKeyCipher()));
                if (schema != null) {
                    s.setVideoModelSchema(schema);
                    s.setVideoModelSchemaAt(java.time.OffsetDateTime.now());
                    s.setVideoModelSchemaError(null);
                    log.info("model schema refreshed user={} kind=video model={} params={} refsField={}",
                            userId, s.videoCloudModel(), schema.path("params").size(),
                            schema.path("mapping").path("refs").asText("-"));
                }
            } catch (RuntimeException e) {
                s.setVideoModelSchema(null);
                s.setVideoModelSchemaError(e.getMessage());
                log.warn("拉取视频模型参数失败 user={} model={}: {}", userId, s.videoCloudModel(), e.getMessage());
            }
        }
        repo.save(s);
        return toResponse(userId);
    }

    @Transactional
    public EngineSettingsResponse update(UUID userId, EngineSettingsRequest req) {
        UserEngineSettings s = repo.findByUserId(userId).orElseGet(() -> UserEngineSettings.defaults(userId));
        this.current = s;
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
        if (req.gpuMaxResolution() != null) s.setGpuMaxResolution(req.gpuMaxResolution());
        if (req.gatewayRefsMax() != null) s.setGatewayRefsMax(req.gatewayRefsMax() >= 0 ? req.gatewayRefsMax() : null);
        if (req.gatewaySample() != null) s.setGatewaySample(req.gatewaySample().isBlank() ? null : req.gatewaySample());
        // 服务地址（配音/配乐、对口型、转写、人脸）：整块替换；空串字段在 worker 侧会回退默认值
        if (req.services() != null) {
            s.setServices(req.services().isObject() ? req.services() : null);
        }
        // P12：全局参数（画质等）按 schema 收口——只留该模型真认识的键，防手改坏调用
        if (req.imageParams() != null) {
            s.setImageParams(schemaService.sanitizeParams(s.imageModelSchema(), req.imageParams()));
        }
        if (req.videoParams() != null) {
            s.setVideoParams(keepMotionKeys(schemaService.sanitizeParams(s.videoModelSchema(), req.videoParams()),
                    req.videoParams(), s.videoEngine()));
        }
        repo.save(s);
        // 换了模型 → 主动拉一次它的调用说明（失败不阻塞保存）
        if (imageModelChanged || videoModelChanged) {
            refreshSchemas(userId, imageModelChanged, videoModelChanged);
        }
        // P13：保存配置即入库（同 baseUrl+model 覆盖）—— 界面上不再需要「保存到模型库」按钮
        UserEngineSettings cur = repo.findByUserId(userId).orElse(s);
        this.current = cur;
        if (cur.imageCloudModel() != null && !cur.imageCloudModel().isBlank()) {
            upsertPresetInto(cur, false, cur.imageCloudBaseUrl(), cur.imageCloudModel(),
                    presetEntry(cur, false, cur.imageCloudBaseUrl(), cur.imageCloudModel()));
        }
        if (cur.videoCloudModel() != null && !cur.videoCloudModel().isBlank()) {
            upsertPresetInto(cur, true, null, cur.videoCloudModel(),
                    presetEntry(cur, true, null, cur.videoCloudModel()));
        }
        repo.save(cur);
        return toResponse(userId);
    }
}
