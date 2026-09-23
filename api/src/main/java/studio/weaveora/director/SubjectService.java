package studio.weaveora.director;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.director.plan.PlanSubjects;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P13 剧情主体：一键从剧情里抽取主体元素（人物 / 载具装备 / 物件 / 场景）。
 *
 * <p>只**提议**不覆盖：已有的主体保留（含用户改过的别名、勾选、定妆图），只补新的。
 */
@Service
public class SubjectService {

    private static final Logger log = LoggerFactory.getLogger(SubjectService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DirectorLlm llm;
    private final PlanReader planReader;
    private final WorkspaceGuard guard;
    private final ProjectContextPort projects;

    public SubjectService(DirectorLlm llm, PlanReader planReader, WorkspaceGuard guard,
                          ProjectContextPort projects) {
        this.llm = llm;
        this.planReader = planReader;
        this.guard = guard;
        this.projects = projects;
    }

    /** 抽取结果：合并后的完整主体列表 + 新增了哪些。 */
    public record ExtractResult(List<PlanSubjects.Subject> subjects, List<String> added, String source) {
    }

    @Transactional(readOnly = true)
    public ExtractResult extract(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频方案支持抽取剧情主体");
        }
        List<PlanSubjects.Subject> existing = new ArrayList<>(PlanSubjects.parse(plan));
        Map<String, PlanSubjects.Subject> byName = new LinkedHashMap<>();
        existing.forEach(s -> byName.put(s.name(), s));

        List<PlanSubjects.Subject> proposed = ask(project, plan);
        List<String> added = new ArrayList<>();
        for (PlanSubjects.Subject p : proposed) {
            // 别名互认合并：LLM 常把「可卿」和已存在的「秦可卿」拆成两条 —— 能互认就并成一条
            String mergeInto = null;
            for (PlanSubjects.Subject old : byName.values()) {
                if (old.name().equals(p.name())
                        || old.aliases().contains(p.name())
                        || p.aliases().contains(old.name())
                        || (!p.aliases().isEmpty() && p.aliases().stream().anyMatch(old.aliases()::contains))) {
                    mergeInto = old.name();
                    break;
                }
            }
            if (mergeInto != null && !mergeInto.equals(p.name())) {
                PlanSubjects.Subject old = byName.get(mergeInto);
                List<String> merged = new ArrayList<>(old.aliases());
                if (!merged.contains(p.name())) {
                    merged.add(p.name());
                }
                for (String a : p.aliases()) {
                    if (!merged.contains(a) && !a.equals(old.name())) {
                        merged.add(a);
                    }
                }
                byName.put(mergeInto, new PlanSubjects.Subject(old.name(), old.kind(), merged,
                        old.enabled(), old.locked(), old.refs(), old.portraitAssetId(), old.portraitVersion(),
                        // ★ P14：合并时把 LLM 新抽到的属性并进来（旧值优先，缺失才用新的）
                        old.traitsOrEmpty().merge(p.traitsOrEmpty()),
                        // ★ 2026-09-23：定妆照自定义提示词属于用户，抽取主体时**必须原样带走**
                        //   （旧调用点用的是不含该字段的兼容构造 → 等于每次抽取都把用户保存的提示词洗掉）
                        old.portraitPromptOrEmpty().merge(p.portraitPromptOrEmpty())));
                continue;
            }
            if (byName.containsKey(p.name())) {
                // 已存在：只补齐别名（不动用户的勾选/定妆图）
                PlanSubjects.Subject old = byName.get(p.name());
                List<String> merged = new ArrayList<>(old.aliases());
                for (String a : p.aliases()) {
                    if (!merged.contains(a) && !a.equals(old.name())) {
                        merged.add(a);
                    }
                }
                byName.put(p.name(), new PlanSubjects.Subject(old.name(),
                        old.kind().equals(PlanSubjects.KIND_PERSON) ? p.kind() : old.kind(),
                        merged, old.enabled(), old.locked(), old.refs(),
                        old.portraitAssetId(), old.portraitVersion(),
                        // ★ P14：用户已经填过的属性不覆盖，只补空的
                        old.traitsOrEmpty().merge(p.traitsOrEmpty()),
                        // ★ 2026-09-23：同上 —— 保用户的定妆照提示词
                        old.portraitPromptOrEmpty().merge(p.portraitPromptOrEmpty())));
            } else {
                byName.put(p.name(), p);
                added.add(p.name());
            }
        }
        log.info("subjects extracted project={} existing={} added={}", projectId, existing.size(), added);
        return new ExtractResult(List.copyOf(byName.values()), added, llm.source());
    }

    private List<PlanSubjects.Subject> ask(ProjectContextPort.ProjectSnapshot project, JsonNode plan) {
        String system = """
                你是短剧/影视的场记。从给定剧情里抽取**需要在画面上保持一致的主体元素**，并尽量给出它们的**人物档案**。
                要求：
                1. 只抽真正会反复出现、需要形象一致的主体：人物、载具/装备、重要物件、关键场景；
                2. 同一主体的不同称呼合并成一条，把其他叫法写进 aliases（如 宝玉/贾宝玉/宝二爷）；
                3. kind 只能是 person | vehicle | object | scene；
                4. 不要抽泛称（如“众人”“士兵”），不要抽纯情绪/抽象概念；
                5. 最多 12 条，按重要度排序。
                6. ★ kind=person 时**必须**尽量确定并填写：gender（male|female|other，拿不准写 other，绝不允许猜反）、
                   age（数字或年龄段，如 “17” / “十六七岁” / “中年”）、height、build（体态，如 清瘦/丰膾/嫗小/魁梧）、
                   personality（性格）、appearance（外貌与服饰要点，写具体：发式/服色/配饰）。
                   依据剧情与人名常识判断（如“宝玉”“贾宝玉”是男性少年）；拿不准就不写，禁止臆造。
                   非 person（载具/物件/场景）不用填 gender/age，只填 appearance 描述外形。
                只输出 JSON：{"subjects":[{"name":"宝玉","kind":"person","aliases":["贾宝玉","宝二爷"],
                  "gender":"male","age":"17","height":"178cm","build":"清瘦",
                  "personality":"多情敏感","appearance":"面若中秋之月，大红箭袖，项上金螭璎珞"}]}
                """;
        StringBuilder user = new StringBuilder();
        user.append("项目：").append(plan.path("title").asText("")).append('\n');
        user.append("主题：").append(plan.path("script").path("theme").asText("")).append('\n');
        // ★ P14：把「设定年代」也告诉场记 —— 年代影响称谓/服制/身份（影响年龄与造型的推断）
        String era = plan.path("setting").path("era").asText("");
        if (!era.isBlank()) {
            user.append("设定年代：").append(era).append('\n');
        }
        user.append("概要：").append(plan.path("logline").asText("")).append('\n');
        for (JsonNode s : plan.path("shots")) {
            user.append("第").append(s.path("shot_no").asInt()).append("镜：")
                    .append(s.path("action").asText("")).append(' ')
                    .append(s.path("zh").asText("")).append('\n');
        }
        user.append("请输出 JSON。");
        try {
            String raw = llm.generateJson(new LlmRequest(system, user.toString(),
                    plan.path("title").asText(""), plan.path("logline").asText(""), "video",
                    project.aspectRatio(), null, null));
            return parse(raw);
        } catch (Exception e) {
            log.warn("抽取剧情主体失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 LLM 输出（容忍围栏与多余字段）。 */
    static List<PlanSubjects.Subject> parse(String raw) {
        List<PlanSubjects.Subject> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        int b = raw.indexOf('{');
        int e = raw.lastIndexOf('}');
        if (b < 0 || e <= b) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(raw.substring(b, e + 1));
            for (JsonNode n : root.path("subjects")) {
                String name = n.path("name").asText("").trim();
                if (name.isEmpty()) {
                    continue;
                }
                String kind = n.path("kind").asText(PlanSubjects.KIND_PERSON).trim().toLowerCase();
                if (!List.of(PlanSubjects.KIND_PERSON, PlanSubjects.KIND_VEHICLE,
                        PlanSubjects.KIND_OBJECT, PlanSubjects.KIND_SCENE).contains(kind)) {
                    kind = PlanSubjects.KIND_PERSON;
                }
                List<String> aliases = new ArrayList<>();
                for (JsonNode a : n.path("aliases")) {
                    String v = a.asText("").trim();
                    if (!v.isEmpty() && !v.equals(name)) {
                        aliases.add(v);
                    }
                }
                // ★ P14：连带人物档案一起解析（性别/年龄/身高/体态/性格/外貌）；
                //   gender 走 normGender 归一（男/male/M → male），未知一律 other —— 宁可写 other 也不能猜反。
                PlanSubjects.Traits traits = PlanSubjects.Traits.parse(n);
                if (PlanSubjects.KIND_PERSON.equals(kind)
                        && (traits.gender() == null || traits.gender().isBlank())) {
                    traits = new PlanSubjects.Traits("other", traits.age(), traits.height(), traits.build(),
                            traits.personality(), traits.appearance());
                }
                out.add(new PlanSubjects.Subject(name, kind, aliases, true, false,
                        List.of(), "", 0, traits));
            }
        } catch (Exception ignored) {
            // 非法 JSON → 当作没产出
        }
        return out;
    }

    /** 把主体列表写回方案 JSON（返回新的方案节点，调用方决定是否保存）。 */
    public ObjectNode applyTo(JsonNode plan, List<PlanSubjects.Subject> subjects) {
        ObjectNode copy = plan.deepCopy();
        PlanSubjects.write(copy, subjects);
        return copy;
    }

    /** 供前端预览：当前方案里的主体（含定妆图状态）。 */
    public ArrayNode list(JsonNode plan) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (PlanSubjects.Subject s : PlanSubjects.parse(plan)) {
            ObjectNode o = arr.addObject();
            o.put("name", s.name());
            o.put("kind", s.kind());
            o.put("enabled", s.enabled());
            o.put("locked", s.locked());
            o.put("refCount", s.refs().size());
            o.put("checkedCount", s.checkedRefs().size());
            o.put("hasPortrait", s.hasPortrait());
            o.put("portraitVersion", s.portraitVersion());
            ArrayNode al = o.putArray("aliases");
            s.aliases().forEach(al::add);
        }
        return arr;
    }
}
