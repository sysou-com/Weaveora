#!/usr/bin/env python3
"""给 ModelSchemaService 打补丁：解 allOf/$ref、从描述里解析 maxItems、补 quality 分组。"""
import io

P = "api/src/main/java/studio/weaveora/engine/ModelSchemaService.java"
s = io.open(P, encoding="utf-8").read()

# ---- 1) 分组：参考图/画幅/格式都算 quality ----
old = """        if (QUALITY_CANDIDATES.contains(name) || n.contains("quality") || n.contains("resolution")
                || n.contains("megapixel") || n.contains("frame") || n.equals("fps")) {
            return "quality";
        }"""
new = """        if (QUALITY_CANDIDATES.contains(name) || n.contains("quality") || n.contains("resolution")
                || n.contains("megapixel") || n.contains("frame") || n.equals("fps")
                || n.contains("aspect") || n.contains("format") || n.contains("size")) {
            return "quality";
        }"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# ---- 2) 归一化时解引用 ----
old = """        JsonNode props = lv.path("openapi_schema").path("components").path("schemas").path("Input").path("properties");
        ArrayNode params = out.putArray("params");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        props.fieldNames().forEachRemaining(n -> byName.put(n, props.get(n)));"""
new = """        JsonNode schemas = lv.path("openapi_schema").path("components").path("schemas");
        JsonNode props = schemas.path("Input").path("properties");
        ArrayNode params = out.putArray("params");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        // 必须解 allOf/$ref：枚举型参数常写成 {"allOf":[{"$ref":"#/components/schemas/aspect_ratio"}], "default":"1:1"}
        props.fieldNames().forEachRemaining(n -> byName.put(n, deref(props.get(n), schemas)));"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# ---- 3) maxItems 兜底：从描述里读 “Maximum 5 images” ----
old = """            if (v.path("items").has("maxItems")) {
                p.put("maxItems", v.path("items").path("maxItems").asInt());
            } else if (v.has("maxItems")) {
                p.put("maxItems", v.get("maxItems").asInt());
            }"""
new = """            if (v.path("items").has("maxItems")) {
                p.put("maxItems", v.path("items").path("maxItems").asInt());
            } else if (v.has("maxItems")) {
                p.put("maxItems", v.get("maxItems").asInt());
            } else if ("array".equals(typeOf(v))) {
                // 有些模型只把上限写在描述里（flux-2-klein-9b：“Maximum 5 images”）
                int max = maxFromDesc(v.path("description").asText(""));
                if (max > 0) {
                    p.put("maxItems", max);
                }
            }"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# ---- 4) refsMax 也吃描述兜底 ----
old = """            if (isArray) {
                JsonNode items = rv.path("items");
                int max = items.path("maxItems").asInt(rv.path("maxItems").asInt(0));
                if (max > 0) {
                    mapping.put("refsMax", max);
                }
            }"""
new = """            if (isArray) {
                JsonNode items = rv.path("items");
                int max = items.path("maxItems").asInt(rv.path("maxItems").asInt(
                        maxFromDesc(rv.path("description").asText(""))));
                if (max > 0) {
                    mapping.put("refsMax", max);
                }
            }"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# ---- 5) 新增 deref / maxFromDesc ----
old = """    static String typeOf(JsonNode v) {"""
new = """    /**
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
                .compile("(?i)maximum\\\\s+(\\\\d+)").matcher(desc);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String typeOf(JsonNode v) {"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# typeOf 空值保护
old = """    static String typeOf(JsonNode v) {
        if (v.has("enum")) {"""
new = """    static String typeOf(JsonNode v) {
        if (v == null) {
            return "unknown";
        }
        if (v.has("enum")) {"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("patched", P)
