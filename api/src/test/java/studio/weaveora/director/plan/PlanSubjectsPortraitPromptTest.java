package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 2026-09-23（用户要求）：定妆照**自定义提示词**要能保存（弹框加「保存提示词」）。
 *
 * <p>为什么值得单测：这份词存在方案 `subjects[]` 里，必须能从**旧数据（无字段）**平滑读出，
 * 且经过「改写回方案 / 别名归并 / 一键抽取主体」这三条重写路径都**不能被洗掉** ——
 * 洗掉是静默的（用户只会发现"我保存的提示词又没了"），所以把契约钉在测试里。
 */
class PlanSubjectsPortraitPromptTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void legacyPlanWithoutPromptFieldsParsesAsEmpty() {
        JsonNode plan = json("{\"subjects\":[{\"name\":\"宝玉\",\"kind\":\"person\",\"gender\":\"male\"}]}");
        PlanSubjects.PortraitPrompt pp = PlanSubjects.parse(plan).get(0).portraitPromptOrEmpty();
        assertTrue(pp.isEmpty(), pp.toString());
        assertFalse(pp.hasPrompt());
        assertEquals("zh", pp.langOrZh(), "缺语言时按前端默认（中文）算");
    }

    @Test
    void readFromPlanJsonAndWriteBackFlat() {
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","kind":"person",
                  "portraitPositivePrompt":"我的自定义正词",
                  "portraitNegativePrompt":"我的自定义负词",
                  "portraitPromptLang":"en"}]}
                """);
        PlanSubjects.Subject s = PlanSubjects.parse(plan).get(0);
        assertTrue(s.portraitPromptOrEmpty().hasPrompt());
        assertEquals("我的自定义正词", s.portraitPromptOrEmpty().positive());
        assertEquals("en", s.portraitPromptOrEmpty().langOrZh());

        // 写回后仍是扁平键（前端/手工编辑都按扁平键读），且能再读回来
        ObjectNode out = M.createObjectNode();
        PlanSubjects.write(out, List.of(s));
        JsonNode one = out.path("subjects").get(0);
        assertEquals("我的自定义正词", one.path("portraitPositivePrompt").asText());
        assertEquals("en", one.path("portraitPromptLang").asText());
        assertEquals("我的自定义正词", PlanSubjects.parse(out).get(0).portraitPromptOrEmpty().positive());
    }

    @Test
    void emptyPromptIsNotWrittenBackSoPlansStayClean() {
        JsonNode plan = json("{\"subjects\":[{\"name\":\"宝玉\",\"kind\":\"person\"}]}");
        ObjectNode out = M.createObjectNode();
        PlanSubjects.write(out, PlanSubjects.parse(plan));
        JsonNode one = out.path("subjects").get(0);
        assertFalse(one.has("portraitPositivePrompt"), one.toString());
        assertFalse(one.has("portraitNegativePrompt"), one.toString());
        assertFalse(one.has("portraitPromptLang"), one.toString());
    }

    @Test
    void onlyLangSavedStillCountsAsNoPrompt() {
        // 只切了语言、没保存词 → 仍应回落到系统默认模板（hasPrompt=false），
        // 否则弹框会显示一段空的正词
        PlanSubjects.PortraitPrompt pp = new PlanSubjects.PortraitPrompt("", "", "en");
        assertFalse(pp.hasPrompt());
        assertFalse(pp.isEmpty(), "语言本身要保留");
        assertEquals("en", pp.langOrZh());
    }

    @Test
    void aliasDuplicateMergeKeepsThePrompt() {
        JsonNode plan = json("""
                {"subjects":[
                  {"name":"宝玉","kind":"person","aliases":["宝二爷"],
                   "portraitPositivePrompt":"宝玉的自定义词","portraitPromptLang":"zh"},
                  {"name":"宝二爷","kind":"person","portraitAssetId":"p9"}
                ]}
                """);
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(plan);
        assertEquals(1, subs.size(), subs.toString());
        assertEquals("宝玉的自定义词", subs.get(0).portraitPromptOrEmpty().positive(),
                "被并进来的那条没有词 → 保留有词的那条");
    }

    @Test
    void mergePrefersSelfAndOnlyFillsBlanks() {
        PlanSubjects.PortraitPrompt a = new PlanSubjects.PortraitPrompt("A正", "", "zh");
        PlanSubjects.PortraitPrompt b = new PlanSubjects.PortraitPrompt("B正", "B负", "en");
        PlanSubjects.PortraitPrompt merged = a.merge(b);
        assertEquals("A正", merged.positive());
        assertEquals("B负", merged.negative(), "本条目空着的字段用另一份补");
        assertEquals("zh", merged.lang());
        assertEquals("A正", a.merge(null).positive());
    }

    /**
     * 回归：这条是最容易腐化的地方 —— 别的调用点用**不含提示词的兼容构造**重建主体时，
     * 用户保存的词会被静默清空。这里用「解析 → 逐字段重建 → 写回」模拟那条路径，
     * 断言只有显式带上 `portraitPromptOrEmpty()` 才不会丢。
     */
    @Test
    void rebuildingASubjectWithTheCompatConstructorLosesThePrompt() {
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","kind":"person",
                  "portraitPositivePrompt":"保存过的词","portraitPromptLang":"zh"}]}
                """);
        PlanSubjects.Subject old = PlanSubjects.parse(plan).get(0);
        PlanSubjects.Subject compat = new PlanSubjects.Subject(old.name(), old.kind(), old.aliases(),
                old.enabled(), old.locked(), old.refs(), old.portraitAssetId(), old.portraitVersion(),
                old.traitsOrEmpty());
        ObjectNode out = M.createObjectNode();
        PlanSubjects.write(out, List.of(compat));
        assertFalse(out.path("subjects").get(0).has("portraitPositivePrompt"),
                "兼容构造 = 不带提示词（所以各重写路径必须显式带上 portraitPromptOrEmpty()）");

        PlanSubjects.Subject kept = new PlanSubjects.Subject(old.name(), old.kind(), old.aliases(),
                old.enabled(), old.locked(), old.refs(), old.portraitAssetId(), old.portraitVersion(),
                old.traitsOrEmpty(), old.portraitPromptOrEmpty());
        ObjectNode out2 = M.createObjectNode();
        PlanSubjects.write(out2, List.of(kept));
        assertEquals("保存过的词", out2.path("subjects").get(0).path("portraitPositivePrompt").asText());
    }
}
