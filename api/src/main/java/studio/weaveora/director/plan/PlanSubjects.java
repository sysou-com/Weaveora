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
 * 剧情主体（P13 参考图升级；P14 增加「主体设定」属性）。
 *
 * <p>升级前的结构是扁平的 {@code referenceAssets:[{assetId,subject,region}]}；升级后按**主体**聚合：
 * <pre>
 * plan.subjects[] = [{
 *   name:"宝玉", kind:"person", aliases:["贾宝玉","宝二爷"], enabled:true, locked:false,
 *   refs:[{assetId:"…", checked:true, region:{x,y,w,h}}],   // 用户素材图（勾选才参与锚定）
 *   portraitAssetId:"…", portraitVersion:2,                 // 定妆图（由素材图生成，优先用于锚定）
 *   gender:"male", age:"17", height:"178cm", build:"清瘦",   // ★ P14 主体设定（2026-09-16 用户要求）
 *   personality:"多情敏感", appearance:"面若中秋之月，大红箭袖，项上金螭璎珞"
 * }]
 * </pre>
 *
 * <p>为什么要有定妆图：直接把用户上传的随手图喂模型，风格/构图不可控 → 一致性差；
 * 先生成一张「标准角色设定图」再拿它锚定所有分镜，才是稳定的做法。
 *
 * <p>为什么还要有「主体设定」（性别/年龄/身高/体态/性格/外貌，P14）：定妆照只约束**长相**，
 * 而 LLM 写提示词、视觉模型画人时还需要知道**性别/年龄段/体态**——实测出现过「宝玉被当女性」
 * （同一镜里 LLM 用了 she/her，视觉模型就照着画），根因就是主体只有名字没有属性。
 * 这些属性会被写进：① 导演/重写提示词的 user 消息；② 出图正词里的 {@code Picture N = 主体（属性…）}；
 * ③ 定妆照提示词。三处口径一致，才不会再自相矛盾。
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

    /**
     * ★ P14 主体设定（2026-09-16 用户要求）—— 喂给 LLM 与视觉模型的「人物档案」。
     *
     * <p>全部字段可选（空串 = 未知，不注入）。{@code gender} 收敛成 {@code male|female|other|""}
     * 三档，因为它是「宝玉被当女性」的直接解药，必须能双语无歧义地注入
     * （中文提示词写「性别 男 male」，英文写「gender male (男)」）。
     * 其余字段保留用户原话（身高可能写「178cm」也可能写「高挑」，不做强制解析）。
     */
    public record Traits(String gender, String age, String height, String build,
                         String personality, String appearance) {

        public static final Traits EMPTY = new Traits("", "", "", "", "", "");

        public static Traits empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return blank(gender) && blank(age) && blank(height) && blank(build)
                    && blank(personality) && blank(appearance);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }

        /** 性别规范化：男/male/man/男性/M → male；女/female/woman → female；其余（含「未知」）→ other。 */
        public static String normGender(String raw) {
            if (raw == null) {
                return "";
            }
            String v = raw.trim().toLowerCase();
            if (v.isEmpty()) {
                return "";
            }
            if (v.equals("m") || v.equals("man") || v.equals("boy") || v.equals("male")
                    || v.startsWith("male") || v.contains("男")) {
                return "male";
            }
            if (v.equals("f") || v.equals("woman") || v.equals("girl") || v.equals("female")
                    || v.startsWith("female") || v.contains("女")) {
                return "female";
            }
            return "other";
        }

        /** 中文档位（未知 → 空串，不注入）。 */
        public String genderZh() {
            return switch (gender == null ? "" : gender) {
                case "male" -> "男";
                case "female" -> "女";
                case "other" -> "其他";
                default -> "";
            };
        }

        /** 英文档位（未知 → 空串）。 */
        public String genderEn() {
            return switch (gender == null ? "" : gender) {
                case "male" -> "male";
                case "female" -> "female";
                case "other" -> "unspecified gender";
                default -> "";
            };
        }

        /**
         * 一行式描述（供提示词注入）。
         *
         * @param zh 是否中文口径（跟随正词语言；性别双语并写，杜绝「宝玉画成女性」）
         */
        public String describe(boolean zh) {
            List<String> parts = new ArrayList<>();
            String gz = genderZh();
            if (!gz.isEmpty()) {
                parts.add(zh ? "性别 " + gz + " " + genderEn() : "gender " + genderEn() + " (" + gz + ")");
            }
            if (!blank(age)) {
                parts.add((zh ? "年龄 " : "age ") + age.trim());
            }
            if (!blank(height)) {
                parts.add((zh ? "身高 " : "height ") + height.trim());
            }
            if (!blank(build)) {
                parts.add((zh ? "体态 " : "build ") + build.trim());
            }
            if (!blank(personality)) {
                parts.add((zh ? "性格 " : "personality ") + personality.trim());
            }
            if (!blank(appearance)) {
                parts.add((zh ? "外貌/服饰 " : "look/costume ") + appearance.trim());
            }
            return String.join(zh ? "；" : "; ", parts);
        }

        /** 极简摘要（列表 UI / 日志用），如「男·17·清瘦」。 */
        public String shortLabel() {
            List<String> parts = new ArrayList<>();
            String gz = genderZh();
            if (!gz.isEmpty()) {
                parts.add(gz);
            }
            if (!blank(age)) {
                parts.add(age.trim());
            }
            if (!blank(build)) {
                parts.add(build.trim());
            } else if (!blank(height)) {
                parts.add(height.trim());
            }
            return String.join("·", parts);
        }

        /** 从主体 JSON 读（容错多种键名，方便手工改过方案的数据）。 */
        public static Traits parse(JsonNode s) {
            if (s == null || !s.isObject()) {
                return EMPTY;
            }
            return new Traits(
                    normGender(s.path("gender").asText("")),
                    firstText(s, "age"),
                    firstText(s, "height"),
                    firstText(s, "build", "figure", "body"),
                    firstText(s, "personality", "character", "temperament"),
                    firstText(s, "appearance", "look", "costume", "outfit"));
        }

        private static String firstText(JsonNode s, String... keys) {
            for (String k : keys) {
                JsonNode n = s.get(k);
                if (n != null && !n.isNull()) {
                    String v = n.asText("").trim();
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
            return "";
        }

        /** 写回主体 JSON（空字段不写，保持方案干净）。 */
        public void write(ObjectNode o) {
            List<String[]> kv = new ArrayList<>();
            if (!blank(gender)) {
                kv.add(new String[]{"gender", gender});
            }
            if (!blank(age)) {
                kv.add(new String[]{"age", age.trim()});
            }
            if (!blank(height)) {
                kv.add(new String[]{"height", height.trim()});
            }
            if (!blank(build)) {
                kv.add(new String[]{"build", build.trim()});
            }
            if (!blank(personality)) {
                kv.add(new String[]{"personality", personality.trim()});
            }
            if (!blank(appearance)) {
                kv.add(new String[]{"appearance", appearance.trim()});
            }
            for (String[] one : kv) {
                o.put(one[0], one[1]);
            }
        }

        /** 合并（别称归并/抽取合并用）：谁非空用谁，本条目优先。 */
        public Traits merge(Traits other) {
            if (other == null) {
                return this;
            }
            return new Traits(
                    blank(gender) ? other.gender : gender,
                    blank(age) ? other.age : age,
                    blank(height) ? other.height : height,
                    blank(build) ? other.build : build,
                    blank(personality) ? other.personality : personality,
                    blank(appearance) ? other.appearance : appearance);
        }
    }

    /** 一个剧情主体。 */
    public record Subject(String name, String kind, List<String> aliases, boolean enabled, boolean locked,
                          List<Ref> refs, String portraitAssetId, int portraitVersion, Traits traits) {

        public Subject(String name, String kind, List<String> aliases, boolean enabled, boolean locked,
                       List<Ref> refs, String portraitAssetId, int portraitVersion) {
            this(name, kind, aliases, enabled, locked, refs, portraitAssetId, portraitVersion, Traits.EMPTY);
        }

        /** 参与锚定的素材图（已勾选）。 */
        public List<Ref> checkedRefs() {
            return refs.stream().filter(Ref::checked).toList();
        }

        public boolean hasPortrait() {
            return portraitAssetId != null && !portraitAssetId.isBlank();
        }

        public Traits traitsOrEmpty() {
            return traits == null ? Traits.EMPTY : traits;
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
        return mergeAliasDuplicates(List.copyOf(out.values()));
    }

    /** 主体名（本名或别称）→ 设定；找不到返回 {@link Traits#EMPTY}。 */
    public static Traits traitsOf(JsonNode plan, String name) {
        if (name == null || name.isBlank()) {
            return Traits.EMPTY;
        }
        for (Subject s : parse(plan)) {
            if (isSameSubject(s, name)) {
                return s.traitsOrEmpty();
            }
        }
        return Traits.EMPTY;
    }

    /**
     * 别称归并：把「名字是另一个主体别称」的条目认定成**同一个主体**。
     *
     * <p>为什么必须做（2026-09-16 用户实测：第 4 镜一致性又崩）：参考图面板里如果某张图被标成
     * **别称**（如「宝二爷」，而主体本名叫「宝玉」），前端 {@code syncReferenceAssets} 按标注名分组，
     * 于是方案里多出一个叫「宝二爷」的**新主体** —— 它带着素材图但**没有定妆照**，
     * 而真主体「宝玉」的 refs 反而被清空。生成时 {@code enforcePortraits} 给「宝二爷」找不到定妆照：
     * 主主体直接报错，非主主体被默默剔除 → **那一镜就丢了身份锚定**（人物对不上参考图）。
     *
     * <p>规则：名称（本名或别称）出现在另一个主体的 name/aliases 中 → 归并为一组；
     * 组内**优先留“有定妆照”的那个作为本名**（没有就看声明顺序），合并 refs（按 assetId 去重、
     * checked 取或）与 aliases（取并集），注入提示词也只用本名。
     */
    static List<Subject> mergeAliasDuplicates(List<Subject> in) {
        if (in == null || in.size() < 2) {
            return in == null ? List.of() : in;
        }
        int n = in.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        // token（本名或别称）→ 最早声明该 token 的主体下标
        Map<String, Integer> owner = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            owner.putIfAbsent(in.get(i).name(), i);
            for (String a : in.get(i).aliases()) {
                owner.putIfAbsent(a, i);
            }
        }
        for (int i = 0; i < n; i++) {
            Subject s = in.get(i);
            List<String> tokens = new ArrayList<>();
            tokens.add(s.name());
            tokens.addAll(s.aliases());
            for (String tk : tokens) {
                Integer j = owner.get(tk);
                if (j != null) {
                    union(parent, i, j);
                }
            }
        }
        // 分组（按首次出现顺序）
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<Subject> out = new ArrayList<>();
        for (List<Integer> g : groups.values()) {
            if (g.size() == 1) {
                out.add(in.get(g.get(0)));
                continue;
            }
            // 本名 = 组内第一个“有定妆照”的；都没有就取声明顺序第一个
            int keep = g.get(0);
            for (int idx : g) {
                if (in.get(idx).hasPortrait()) {
                    keep = idx;
                    break;
                }
            }
            Subject base = in.get(keep);
            List<String> aliases = new ArrayList<>(base.aliases());
            Map<String, Ref> refs = new LinkedHashMap<>();
            for (Ref r : base.refs()) {
                refs.putIfAbsent(r.assetId(), r);
            }
            String portrait = base.portraitAssetId();
            int pv = base.portraitVersion();
            Traits traits = base.traitsOrEmpty();
            List<String> dropped = new ArrayList<>();
            for (int idx : g) {
                if (idx == keep) {
                    continue;
                }
                Subject d = in.get(idx);
                dropped.add(d.name());
                for (String a : d.aliases()) {
                    if (!a.isBlank() && !a.equals(base.name()) && !aliases.contains(a)) {
                        aliases.add(a);
                    }
                }
                if (!d.name().isBlank() && !d.name().equals(base.name()) && !aliases.contains(d.name())) {
                    aliases.add(d.name());     // 被并进来的本名 → 降级成别称，保留可匹配性
                }
                for (Ref r : d.refs()) {
                    Ref old = refs.get(r.assetId());
                    refs.put(r.assetId(), old == null ? r
                            : new Ref(r.assetId(), old.checked() || r.checked(),
                                      old.region() != null ? old.region() : r.region()));
                }
                if ((portrait == null || portrait.isBlank()) && d.hasPortrait()) {
                    portrait = d.portraitAssetId();
                    pv = d.portraitVersion();
                }
                traits = traits.merge(d.traitsOrEmpty());
            }
            out.add(new Subject(base.name(), base.kind(), List.copyOf(aliases), base.enabled(), base.locked(),
                    List.copyOf(refs.values()), portrait == null ? "" : portrait, pv, traits));
            System.out.println("[PlanSubjects] 别称归并：「" + String.join("、", dropped)
                    + "」→ 同一主体「" + base.name() + "」（别名 " + aliases + "，合并后 " + refs.size() + " 张参考图）");
        }
        return List.copyOf(out);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
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
                s.path("portraitVersion").asInt(0),
                Traits.parse(s));
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
            // ★ P14 主体设定（性别/年龄/身高/体态/性格/外貌）—— 空的不写
            s.traitsOrEmpty().write(o);
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

    /**
     * 名字是否属于该主体（本名或别称，含中文 2 字片段容错）。
     *
     * <p>用途：按主体名反查主体/定妆照时也要认别称 —— 否则「方案里叫宝玉、图上标了宝二爷」
     * 会被当成两个主体，其中一个没有定妆照 → 那一镜丢身份锚定（用户实测第 4 镜）。
     */
    public static boolean isSameSubject(Subject s, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim();
        if (n.equals(s.name())) {
            return true;
        }
        if (s.aliases().contains(n)) {
            return true;
        }
        // 反向：传入的名字是主体本名的一部分/别称的写法（如 传「贾宝玉」、主体名「宝玉」）
        return nameMatches(s.name(), n);
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
