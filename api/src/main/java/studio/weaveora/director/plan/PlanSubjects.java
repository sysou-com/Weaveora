package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧情主体（P13 参考图升级）。
 *
 * <p>升级前的结构是扁平的 {@code referenceAssets:[{assetId,subject,region}]}；升级后按**主体**聚合：
 * <pre>
 * plan.subjects[] = [{
 *   name:"宝玉", kind:"person", aliases:["贾宝玉","宝二爷"], enabled:true, locked:false,
 *   refs:[{assetId:"…", checked:true, region:{x,y,w,h}}],   // 用户素材图（勾选才参与锚定）
 *   portraitAssetId:"…", portraitVersion:2                  // 定妆图（由素材图生成，优先用于锚定）
 * }]
 * </pre>
 *
 * <p>为什么要有定妆图：直接把用户上传的随手图喂模型，风格/构图不可控 → 一致性差；
 * 先生成一张「标准角色设定图」再拿它锚定所有分镜，才是稳定的做法。
 */
public final class PlanSubjects {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 主体类型：人 / 载具装备 / 物件 / 场景（影响定妆图提示词） */
    public static final String KIND_PERSON = "person";
    public static final String KIND_VEHICLE = "vehicle";
    public static final String KIND_OBJECT = "object";
    public static final String KIND_SCENE = "scene";

    /** 用户素材图（勾选才参与参考）。 */
    public record Ref(String assetId, boolean checked, JsonNode region) {
    }

    /** 一个剧情主体。 */
    public record Subject(String name, String kind, List<String> aliases, boolean enabled, boolean locked,
                          List<Ref> refs, String portraitAssetId, int portraitVersion) {

        /** 参与锚定的素材图（已勾选）。 */
        public List<Ref> checkedRefs() {
            return refs.stream().filter(Ref::checked).toList();
        }

        public boolean hasPortrait() {
            return portraitAssetId != null && !portraitAssetId.isBlank();
        }

        /** 该主体可用于锚定的资产：定妆图优先，其次勾选的素材图。 */
        public String anchorAssetId() {
            if (hasPortrait()) {
                return portraitAssetId;
            }
            List<Ref> c = checkedRefs();
            return c.isEmpty() ? null : c.get(0).assetId();
        }
    }

    private PlanSubjects() {
    }

    /** 从方案里读主体：优先 {@code subjects[]}，兼容旧的 {@code referenceAssets[]}。 */
    public static List<Subject> parse(JsonNode plan) {
        Map<String, Subject> out = new LinkedHashMap<>();
        if (plan == null || plan.isMissingNode()) {
            return List.of();
        }
        JsonNode subs = plan.get("subjects");
        if (subs != null && subs.isArray()) {
            for (JsonNode s : subs) {
                Subject sub = parseOne(s);
                if (sub != null) {
                    out.put(sub.name(), sub);
                }
            }
        }
        if (out.isEmpty()) {
            // 旧结构迁移：referenceAssets[{assetId,subject,region}] → 按 subject 聚合
            JsonNode legacy = plan.get("referenceAssets");
            if (legacy != null && legacy.isArray()) {
                Map<String, List<Ref>> bySubject = new LinkedHashMap<>();
                for (JsonNode ra : legacy) {
                    String assetId = ra.path("assetId").asText("");
                    if (assetId.isBlank()) {
                        continue;
                    }
                    String subject = ra.path("subject").asText("").trim();
                    bySubject.computeIfAbsent(subject, k -> new ArrayList<>())
                            .add(new Ref(assetId, true, ra.has("region") ? ra.get("region") : null));
                }
                bySubject.forEach((name, refs) -> out.put(name,
                        new Subject(name, KIND_PERSON, List.of(), true, false, refs, null, 0)));
            }
        }
        return List.copyOf(out.values());
    }

    private static Subject parseOne(JsonNode s) {
        String name = s.path("name").asText("").trim();
        if (name.isEmpty()) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode a : s.path("aliases")) {
            String v = a.asText("").trim();
            if (!v.isEmpty() && !v.equals(name)) {
                aliases.add(v);
            }
        }
        List<Ref> refs = new ArrayList<>();
        for (JsonNode r : s.path("refs")) {
            String assetId = r.path("assetId").asText("");
            if (assetId.isBlank()) {
                continue;
            }
            // 兼容旧字段：没有 checked 视为勾选（旧数据加入即参与）
            boolean checked = !r.has("checked") || r.path("checked").asBoolean(true);
            refs.add(new Ref(assetId, checked, r.has("region") && !r.get("region").isNull() ? r.get("region") : null));
        }
        String kind = s.path("kind").asText(KIND_PERSON);
        return new Subject(name, kind.isEmpty() ? KIND_PERSON : kind,
                aliases,
                !s.has("enabled") || s.path("enabled").asBoolean(true),
                s.path("locked").asBoolean(false),
                refs,
                s.path("portraitAssetId").asText(""),
                s.path("portraitVersion").asInt(0));
    }

    /** 写回方案（覆盖 referenceAssets 以保持旧代码可读：只写勾选的）。 */
    public static void write(ObjectNode plan, List<Subject> subjects) {
        ArrayNode arr = MAPPER.createArrayNode();
        ArrayNode legacy = MAPPER.createArrayNode();
        for (Subject s : subjects) {
            ObjectNode o = arr.addObject();
            o.put("name", s.name());
            o.put("kind", s.kind());
            ArrayNode al = o.putArray("aliases");
            s.aliases().forEach(al::add);
            o.put("enabled", s.enabled());
            o.put("locked", s.locked());
            ArrayNode refs = o.putArray("refs");
            for (Ref r : s.refs()) {
                ObjectNode ro = refs.addObject();
                ro.put("assetId", r.assetId());
                ro.put("checked", r.checked());
                if (r.region() != null) {
                    ro.set("region", r.region());
                }
                if (r.checked()) {          // 旧字段：保持 referenceAssets 与勾选一致
                    ObjectNode lo = legacy.addObject();
                    lo.put("assetId", r.assetId());
                    lo.put("subject", s.name());
                    if (r.region() != null) {
                        lo.set("region", r.region());
                    }
                }
            }
            if (s.hasPortrait()) {
                o.put("portraitAssetId", s.portraitAssetId());
                o.put("portraitVersion", s.portraitVersion());
            }
        }
        plan.set("subjects", arr);
        plan.set("referenceAssets", legacy);
    }

    /** 主体名/别名是否出现在文本里（含中文「省姓氏」容错）。 */
    public static boolean matches(String text, Subject s) {
        if (nameMatches(text, s.name())) {
            return true;
        }
        for (String a : s.aliases()) {
            if (nameMatches(text, a)) {
                return true;
            }
        }
        return false;
    }

    /** 名称匹配：精确包含 + 中文名退一步做 2 字片段（秦可卿 ↔「可卿」）。 */
    public static boolean nameMatches(String text, String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String t = text == null ? "" : text;
        String n = name.trim();
        if (t.contains(n)) {
            return true;
        }
        if (n.length() < 3) {
            return false;
        }
        for (int i = 0; i + 2 <= n.length(); i++) {
            if (t.contains(n.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }
}
