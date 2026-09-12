package studio.weaveora.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网关（OpenAI Images 兼容，如火山方舟）通道的「参数说明」获取。
 *
 * <p>为什么不能像 Replicate 那样自动拿到：Replicate 的模型接口带 {@code openapi_schema}
 * （逐参数的类型/默认值/枚举），而方舟只提供 {@code /api/v3/models} —— 只有
 * <b>模态（input/output modalities）与任务类型</b>，<b>没有任何逐参数规范</b>
 * （2026-09 实测：{@code /api/v3/openapi.json} 404）。
 *
 * <p>因此这里分两步：
 * <ol>
 *   <li>{@link #probeGateway}：调 {@code /models} 判断「这个模型存不存在、是否吃图、任务类型」；</li>
 *   <li>{@link #parseSample}：把用户粘贴的**示例请求**（curl 或 JSON body）解析成与 Replicate
 *       同形状的参数说明 + 参数映射 —— worker 侧无需任何改动即可按新模型的字段名填参。</li>
 * </ol>
 */
public final class GatewayModelProbe {

    private static final Logger log = LoggerFactory.getLogger(GatewayModelProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private GatewayModelProbe() {
    }

    /** 由 images/generations 端点推出「模型列表」端点。 */
    static String modelsUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) {
            return "";
        }
        int q = b.indexOf('?');
        if (q > 0) {
            b = b.substring(0, q);
        }
        b = b.replaceAll("/+$", "");
        if (b.endsWith("/images/generations")) {
            b = b.substring(0, b.length() - "/images/generations".length());
        }
        return b + "/models";
    }

    /**
     * 探测网关上的模型元信息。
     *
     * @return {@code {ok, modelExists, inputModalities[], outputModalities[], taskTypes[], note}}；
     *         失败时 {@code {ok:false, error:"..."}}（不抛异常，界面照旧可保存/可用）。
     */
    public static ObjectNode probeGateway(String baseUrl, String apiKey, String modelId) {
        ObjectNode out = MAPPER.createObjectNode();
        String url = modelsUrl(baseUrl);
        if (url.isEmpty() || !url.startsWith("http")) {
            out.put("ok", false);
            out.put("error", "BaseURL 未配置");
            return out;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Weaveora/1.0)")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                out.put("ok", false);
                out.put("error", "网关模型列表请求失败（HTTP " + resp.statusCode() + "）：" + trunc(resp.body(), 160));
                return out;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            out.put("ok", true);
            out.put("url", url);
            out.put("modelCount", data.isArray() ? data.size() : 0);
            JsonNode hit = null;
            if (data.isArray()) {
                for (JsonNode m : data) {
                    if (m.path("id").asText("").equalsIgnoreCase(modelId)) {
                        hit = m;
                        break;
                    }
                }
                if (hit == null) {   // 退回按 name 前缀匹配（如 doubao-seedream-5-0）
                    for (JsonNode m : data) {
                        String nm = m.path("name").asText("");
                        if (!nm.isEmpty() && modelId != null && modelId.toLowerCase(Locale.ROOT)
                                .startsWith(nm.toLowerCase(Locale.ROOT))) {
                            hit = m;
                            break;
                        }
                    }
                }
            }
            out.put("modelExists", hit != null);
            out.set("inputModalities", hit == null ? MAPPER.createArrayNode() : hit.path("modalities").path("input_modalities"));
            out.set("outputModalities", hit == null ? MAPPER.createArrayNode() : hit.path("modalities").path("output_modalities"));
            out.set("taskTypes", hit == null ? MAPPER.createArrayNode() : hit.path("task_type"));
            boolean takesImage = false;
            for (JsonNode m : out.path("inputModalities")) {
                if ("image".equalsIgnoreCase(m.asText())) {
                    takesImage = true;
                }
            }
            out.put("takesImage", takesImage);
            if (hit == null) {
                out.put("note", "网关模型列表里没有「" + modelId + "」—— 检查模型 id 拼写/是否已开通");
            } else if (!takesImage) {
                out.put("note", "该模型输入模态只有 " + out.path("inputModalities")
                        + "：不支持参考图（人物一致性无法生效）");
            } else {
                out.put("note", "该模型支持图片输入（任务类型 " + out.path("taskTypes")
                        + "）；参考图字段名与张数上限请用「示例请求」解析或按官方文档填写");
            }
            return out;
        } catch (Exception e) {
            log.warn("网关模型探测失败 {}: {}", url, e.getMessage());
            out.put("ok", false);
            out.put("error", "网关模型探测失败：" + e.getMessage());
            return out;
        }
    }

    /**
     * 解析用户粘贴的示例（curl 指令 / JSON body / 纯参数说明行）→ 与 Replicate 同形状的 schema。
     *
     * <p>只做「字段名与类型」的识别，不猜业务语义之外的东西；识别不到参考图字段时明确提示。
     */
    public static ObjectNode parseSample(String sample) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("provider", "sample");
        ArrayNode params = out.putArray("params");
        ObjectNode mapping = out.putObject("mapping");
        ArrayNode notes = out.putArray("notes");
        if (sample == null || sample.isBlank()) {
            notes.add("没有提供示例请求：请在「示例请求」里粘贴一段 curl 或 JSON body（含你要用的字段），再点「解析示例」");
            return out;
        }
        JsonNode body = extractJson(sample);
        if (body == null) {
            notes.add("没能从示例里认出 JSON 参数体：请粘贴形如 " +
                    "curl https://... -d '{\"model\":\"...\",\"prompt\":\"...\",\"image\":[\"...\"]}' 的内容");
            return out;
        }
        if (body.has("input") && body.path("input").isObject()) {
            body = body.path("input");          // Replicate 风格 {input:{...}} 也支持
        }
        final JsonNode bodyNode = body;          // 下面要进 lambda（需 effectively final）
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        bodyNode.fieldNames().forEachRemaining(n -> byName.put(n, bodyNode.get(n)));
        String model = byName.containsKey("model") ? byName.get("model").asText("") : "";
        out.put("model", model);

        for (Map.Entry<String, JsonNode> e : byName.entrySet()) {
            String name = e.getKey();
            JsonNode v = e.getValue();
            ObjectNode p = params.addObject();
            p.put("name", name);
            p.put("type", ModelSchemaService.typeOf(v));
            if (v != null && !v.isNull() && !v.isContainerNode()) {
                p.set("sampleValue", v);
            }
            if (v != null && v.isArray()) {
                p.put("maxItems", 0);
            }
            p.put("group", ModelSchemaService.groupOf(name));
            p.put("userEditable", ModelSchemaService.isUserEditable(name, v == null ? MAPPER.createObjectNode() : v));
        }

        // 与 Replicate 完全一致的字段推断规则（含 image_input / input_files 等命名 + 通用兜底）
        ModelSchemaService.CandidatePick pick = ModelSchemaService.pickRefs(byName);
        if (pick != null) {
            mapping.put("refs", pick.name());
            mapping.put("refsIsArray", pick.array());
            if (pick.guessed()) {
                mapping.put("refsGuessed", true);
            }
        }
        for (Map.Entry<String, String> e : Map.of(
                "prompt", "prompt", "size", "size", "aspect", "aspect_ratio",
                "width", "width", "height", "height", "seed", "seed",
                "negative", "negative_prompt").entrySet()) {
            if (byName.containsKey(e.getValue())) {
                mapping.put(e.getKey(), e.getValue());
            }
        }
        if (mapping.path("refs").asText("").isEmpty()) {
            notes.add("示例里没有找到参考图字段：若该模型支持图片输入，请在示例中带上它（常见名：image / images / image_input / input_images / input_files）");
        } else {
            notes.add("参考图字段识别为「" + mapping.path("refs").asText() + "」"
                    + (mapping.path("refsIsArray").asBoolean(false) ? "（数组，可多张）" : "（单张）")
                    + "；张数上限请按官方文档填「参考图上限」");
        }
        notes.add("参数说明来自你粘贴的示例（不是官方 schema）：字段名与类型已识别，默认值仅供参照");
        return out;
    }

    /** 从任意文本里抽出「最像请求体」的 JSON 对象（先找含 prompt/input 的）。 */
    static JsonNode extractJson(String text) {
        List<String> candidates = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    candidates.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        JsonNode best = null;
        for (String c : candidates) {
            try {
                JsonNode n = MAPPER.readTree(c);
                if (!n.isObject()) {
                    continue;
                }
                String low = c.toLowerCase(Locale.ROOT);
                if (best == null || low.contains("\"prompt\"") || low.contains("\"input\"")) {
                    best = n;
                }
            } catch (Exception ignore) {
                // 不是合法 JSON，跳过
            }
        }
        return best;
    }

    static String typeOfJson(JsonNode v) {
        if (v == null || v.isNull()) {
            return "unknown";
        }
        if (v.isArray()) {
            return "array";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        if (v.isInt() || v.isLong()) {
            return "integer";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isTextual()) {
            return "string";
        }
        return "unknown";
    }

    private static String trunc(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
