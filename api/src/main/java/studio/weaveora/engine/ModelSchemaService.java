package studio.weaveora.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 云模型「调用参数说明」：配模型时主动拉取模型的 input schema，归一化成
 * <ol>
 *   <li><b>参数说明</b>（给用户看：名字/类型/默认值/可选值/说明，精简版）；</li>
 *   <li><b>参数映射 mapping</b>（给 worker 用：参考图字段叫什么、prompt 字段叫什么、有没有
 *       aspect_ratio / negative_prompt / steps / cfg / megapixels…）。</li>
 * </ol>
 *
 * <p>为什么必须这么做（踩过的坑）：不同模型同名参数不同名 —— 参考图在 flux-2-klein-9b 叫
 * {@code images}、在别的模型叫 {@code input_images}/{@code image}；<b>Replicate 对未知字段是
 * 静默忽略</b>，猜错时不会报错，只是「参考图完全没生效、一致性全无」。
 */
@Service
public class ModelSchemaService {

    private static final Logger log = LoggerFactory.getLogger(ModelSchemaService.class);
    private static final String REPLICATE_API = "https://api.replicate.com/v1";

    /** 候选字段名（越靠前越优先）。顺序来自各模型实际 schema 的观察，避免"猜一个名字"。 */
    private static final List<String> REFS_CANDIDATES = List.of(
            "input_images", "images", "reference_images", "ref_images", "image",
            "init_image", "input_image", "image_prompt", "start_image", "first_frame_image");
    private static final List<String> PROMPT_CANDIDATES = List.of("prompt", "text", "positive_prompt", "caption");
    private static final List<String> NEGATIVE_CANDIDATES = List.of("negative_prompt", "negative");
    private static final List<String> ASPECT_CANDIDATES = List.of("aspect_ratio", "aspect", "ratio");
    private static final List<String> WIDTH_CANDIDATES = List.of("width");
    private static final List<String> HEIGHT_CANDIDATES = List.of("height");
    private static final List<String> SEED_CANDIDATES = List.of("seed", "random_seed");
    private static final List<String> STEPS_CANDIDATES = List.of("num_inference_steps", "steps", "num_steps");
    private static final List<String> CFG_CANDIDATES = List.of("guidance_scale", "guidance", "cfg");
    private static final List<String> QUALITY_CANDIDATES = List.of(
            "megapixels", "output_quality", "quality", "resolution", "num_frames", "fps");
    private static final List<String> LAST_FRAME_CANDIDATES = List.of(
            "last_frame_image", "end_image", "tail_image", "last_image");

    /** 这些字段由系统填（参考图/prompt/seed），不作为"全局可调参数"给用户改。 */
    private static final List<String> SYSTEM_OWNED = List.of(
            "prompt", "text", "positive_prompt", "negative_prompt", "seed", "random_seed");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** 拉取并归一化模型 schema（失败返回 null，调用方不要阻塞保存）。 */
    public ObjectNode fetchReplicate(String model, String token) {
        if (model == null || model.isBlank()) {
            return null;
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("未配置 Replicate API Key，无法获取模型参数说明");
        }
        String clean = model.contains(":") ? model.substring(0, model.indexOf(':')) : model.trim();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(REPLICATE_API + "/models/" + clean))
                    .timeout(Duration.ofSeconds(25))
                    // 不带浏览器 UA 会被 Cloudflare 挡成 403（实测）
                    .header("User-Agent", "Mozilla/5.0 (compatible; Weaveora/1.0)")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token.trim())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) {
                throw new IllegalStateException("Replicate 上找不到模型「" + clean + "」（检查 owner/name 拼写）");
            }
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("获取模型参数失败（HTTP " + resp.statusCode() + "）："
                        + trunc(resp.body()));
            }
            return normalize(clean, mapper.readTree(resp.body()));
        } catch (java.io.IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取模型参数失败（网络）：" + e.getMessage());
        }
    }

    /** JSON（Replicate model 对象）→ 归一化 schema。 */
    ObjectNode normalize(String model, JsonNode raw) {
        ObjectNode out = mapper.createObjectNode();
        out.put("provider", "replicate");
        out.put("model", model);
        JsonNode lv = raw.path("latest_version");
        out.put("version", lv.path("id").asText(""));
        out.put("versionCreatedAt", lv.path("created_at").asText(""));
        out.put("fetchedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        JsonNode schemas = lv.path("openapi_schema").path("components").path("schemas");
        JsonNode props = schemas.path("Input").path("properties");
        ArrayNode params = out.putArray("params");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        // 必须解 allOf/$ref：枚举型参数常写成 {"allOf":[{"$ref":"#/components/schemas/aspect_ratio"}], "default":"1:1"}
        props.fieldNames().forEachRemaining(n -> byName.put(n, deref(props.get(n), schemas)));

        for (Map.Entry<String, JsonNode> e : byName.entrySet()) {
            JsonNode v = e.getValue();
            ObjectNode p = params.addObject();
            p.put("name", e.getKey());
            p.put("type", typeOf(v));
            if (v.has("default") && !v.get("default").isNull()) {
                p.set("default", v.get("default"));
            }
            if (v.has("enum")) {
                ArrayNode en = p.putArray("enum");
                v.get("enum").forEach(x -> en.add(x.asText()));
            }
            if (v.has("maximum")) {
                p.put("max", v.get("maximum").asDouble());
            }
            if (v.path("items").has("maxItems")) {
                p.put("maxItems", v.path("items").path("maxItems").asInt());
            } else if (v.has("maxItems")) {
                p.put("maxItems", v.get("maxItems").asInt());
            } else if ("array".equals(typeOf(v))) {
                // 有些模型只把上限写在描述里（flux-2-klein-9b：“Maximum 5 images”）
                int max = maxFromDesc(v.path("description").asText(""));
                if (max > 0) {
                    p.put("maxItems", max);
                }
            }
            p.put("required", false);
            String desc = v.path("description").asText("");
            if (!desc.isBlank()) {
                p.put("desc", trunc(desc.replaceAll("\\s+", " ")));
            }
            p.put("group", groupOf(e.getKey()));
            p.put("userEditable", isUserEditable(e.getKey(), v));
        }
        JsonNode required = lv.path("openapi_schema").path("components").path("schemas").path("Input").path("required");
        if (required.isArray()) {
            List<String> req = new ArrayList<>();
            required.forEach(x -> req.add(x.asText()));
            for (JsonNode p : params) {
                if (req.contains(p.path("name").asText())) {
                    ((ObjectNode) p).put("required", true);
                }
            }
        }

        // 参数映射：worker 按它填参数（这是修「参考图没生效」的关键）
        ObjectNode mapping = out.putObject("mapping");
        putIfFound(mapping, "refs", byName, REFS_CANDIDATES);
        putIfFound(mapping, "prompt", byName, PROMPT_CANDIDATES);
        putIfFound(mapping, "negative", byName, NEGATIVE_CANDIDATES);
        putIfFound(mapping, "aspect", byName, ASPECT_CANDIDATES);
        putIfFound(mapping, "width", byName, WIDTH_CANDIDATES);
        putIfFound(mapping, "height", byName, HEIGHT_CANDIDATES);
        putIfFound(mapping, "seed", byName, SEED_CANDIDATES);
        putIfFound(mapping, "steps", byName, STEPS_CANDIDATES);
        putIfFound(mapping, "cfg", byName, CFG_CANDIDATES);
        putIfFound(mapping, "lastFrame", byName, LAST_FRAME_CANDIDATES);
        // 参考图字段是否能接多张（数组）以及上限
        String refsField = mapping.path("refs").asText("");
        if (!refsField.isEmpty()) {
            JsonNode rv = byName.get(refsField);
            boolean isArray = "array".equals(typeOf(rv));
            mapping.put("refsIsArray", isArray);
            if (isArray) {
                JsonNode items = rv.path("items");
                int max = items.path("maxItems").asInt(rv.path("maxItems").asInt(
                        maxFromDesc(rv.path("description").asText(""))));
                if (max > 0) {
                    mapping.put("refsMax", max);
                }
            }
        }
        ArrayNode notes = out.putArray("notes");
        if (refsField.isEmpty()) {
            notes.add("该模型没有可识别的参考图字段：人物一致性不会生效（可换支持参考图的模型，如 FLUX.2 系）");
        } else if (!mapping.path("refsIsArray").asBoolean(false)) {
            notes.add("参考图字段 " + refsField + " 只收单张：多主体镜只会用「主主体」那一张");
        }
        if (mapping.path("negative").asText("").isEmpty()) {
            notes.add("该模型不支持 negative_prompt（FLUX.2 系即是如此），负向提示词会被忽略");
        }
        return out;
    }

    private static void putIfFound(ObjectNode mapping, String key, Map<String, JsonNode> byName, List<String> candidates) {
        for (String c : candidates) {
            if (byName.containsKey(c)) {
                mapping.put(key, c);
                return;
            }
        }
    }

    /**
     * 解开 {@code allOf: [{$ref: "#/components/schemas/xxx"}]}（Replicate 的枚举参数都这么写）。
     *
     * <p>不解引用的话，这类参数会退化成 {@code unknown} → 不能判断类型、拿不到 enum、
     * 也没法让用户在「全局参数」里改画质（实测踩过）。
     */
    static JsonNode deref(JsonNode v, JsonNode schemas) {
        if (v == null || !v.isObject()) {
            return v;
        }
        if (!v.has("allOf") || !v.get("allOf").isArray()) {
            return v;
        }
        ObjectNode merged = v.deepCopy();
        merged.remove("allOf");
        for (JsonNode part : v.get("allOf")) {
            JsonNode target = part;
            if (part.hasNonNull("$ref")) {
                String ref = part.get("$ref").asText("");
                int slash = ref.lastIndexOf('/');
                target = schemas.path(ref.substring(slash + 1));
            }
            if (target != null && target.isObject()) {
                target.fields().forEachRemaining(e -> {
                    if (!merged.has(e.getKey())) {
                        merged.set(e.getKey(), e.getValue());
                    }
                });
            }
        }
        return merged;
    }

    /** 从描述里抠「Maximum N」（有些模型不写 maxItems，只写在文案里）。 */
    static int maxFromDesc(String desc) {
        if (desc == null || desc.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)maximum\\s+(\\d+)").matcher(desc);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String typeOf(JsonNode v) {
        if (v == null) {
            return "unknown";
        }
        if (v.has("enum")) {
            return "enum";
        }
        String t = v.path("type").asText("");
        if ("array".equals(t)) {
            return "array";
        }
        return t.isEmpty() ? "unknown" : t;
    }

    /** 参数分组：refs / prompt / quality / control / other（前端据此分组显示）。 */
    static String groupOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (REFS_CANDIDATES.contains(name)) {
            return "refs";
        }
        if (n.contains("prompt") || n.equals("text") || n.equals("caption")) {
            return "prompt";
        }
        if (QUALITY_CANDIDATES.contains(name) || n.contains("quality") || n.contains("resolution")
                || n.contains("megapixel") || n.contains("frame") || n.equals("fps")
                || n.contains("aspect") || n.contains("format") || n.contains("size")) {
            return "quality";
        }
        if (n.contains("seed") || n.contains("step") || n.contains("guidance") || n.contains("safety")
                || n.contains("go_fast") || n.equals("cfg")) {
            return "control";
        }
        return "other";
    }

    /** 允许用户在「全局参数」里改的：有默认值/枚举的标量参数，且不由系统独占。 */
    static boolean isUserEditable(String name, JsonNode v) {
        if (SYSTEM_OWNED.contains(name)) {
            return false;
        }
        if (REFS_CANDIDATES.contains(name)) {
            return false;
        }
        String t = typeOf(v);
        return "integer".equals(t) || "number".equals(t) || "boolean".equals(t)
                || "enum".equals(t) || "string".equals(t);
    }

    /** 从 schema 里挑出「用户改过的全局参数」，只保留 schema 认识的键（防手改坏调用）。 */
    JsonNode sanitizeParams(JsonNode schema, JsonNode userParams) {
        ObjectNode out = mapper.createObjectNode();
        if (schema == null || userParams == null || !userParams.isObject()) {
            return out;
        }
        JsonNode params = schema.path("params");
        for (JsonNode p : params) {
            String name = p.path("name").asText("");
            if (name.isEmpty() || !p.path("userEditable").asBoolean(false)) {
                continue;
            }
            if (!userParams.has(name)) {
                continue;
            }
            JsonNode v = userParams.get(name);
            if (v == null || v.isNull()) {
                continue;
            }
            String t = p.path("type").asText("");
            // 类型对齐：数值/布尔/枚举各按 schema 收口
            if ("integer".equals(t) && v.isNumber()) {
                out.put(name, v.asInt());
            } else if ("number".equals(t) && v.isNumber()) {
                out.put(name, v.asDouble());
            } else if ("boolean".equals(t) && v.isBoolean()) {
                out.put(name, v.asBoolean());
            } else if (v.isTextual()) {
                String s = v.asText();
                if ("enum".equals(t) && p.has("enum")) {
                    for (JsonNode e : p.get("enum")) {
                        if (s.equals(e.asText())) {
                            out.put(name, s);
                            break;
                        }
                    }
                } else if (!"enum".equals(t)) {
                    out.put(name, s);
                }
            }
        }
        return out;
    }

    private static String trunc(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
