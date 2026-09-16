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
 * ★ P14 主体设定（人物档案）：性别/年龄/身高/体态/性格/外貌。
 *
 * <p>为什么值得单测（2026-09-16 用户实测）：关键帧把「宝玉」画成了**女性** ——
 * 根因是主体只有名字，LLM 与视觉模型只能按名字猜性别。这份档案要同时进
 * ①导演/重写 LLM 上下文、②出图正词的 {@code Picture N = 主体[档案]}、③定妆照提示词，
 * 三处必须同口径；而且必须能从**旧数据（无字段）**平滑读出来。
 */
class PlanSubjectsTraitsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void normalisesGenderSoMaleIsNeverRenderedFemale() {
        for (String raw : new String[]{"male", "Male", "M", "man", "男", "男性"}) {
            assertEquals("male", PlanSubjects.Traits.normGender(raw), raw);
        }
        for (String raw : new String[]{"female", "F", "woman", "女", "女性"}) {
            assertEquals("female", PlanSubjects.Traits.normGender(raw), raw);
        }
        assertEquals("other", PlanSubjects.Traits.normGender("unknown"));
        assertEquals("other", PlanSubjects.Traits.normGender("未知"));
        assertEquals("", PlanSubjects.Traits.normGender(""));
        assertEquals("", PlanSubjects.Traits.normGender(null));
    }

    @Test
    void describeIsBilingualForGenderAndFollowsPromptLanguage() {
        PlanSubjects.Traits t = new PlanSubjects.Traits("male", "17", "178cm", "清瘦", "多情敏感", "大红箭袖");
        String zh = t.describe(true);
        assertTrue(zh.contains("性别 男 male"), zh);
        assertTrue(zh.contains("年龄 17"), zh);
        assertTrue(zh.contains("外貌/服饰 大红箭袖"), zh);

        String en = t.describe(false);
        assertTrue(en.contains("gender male (男)"), en);
        assertTrue(en.contains("age 17"), en);
        assertTrue(en.contains("look/costume 大红箭袖"), en);
        // 未知性别（other）也要说清楚，不能留空让模型自己猜
        assertTrue(new PlanSubjects.Traits("other", "", "", "", "", "").describe(true).contains("其他"),
                new PlanSubjects.Traits("other", "", "", "", "", "").describe(true));
    }

    @Test
    void emptyTraitsProduceNoDescription() {
        assertTrue(PlanSubjects.Traits.EMPTY.isEmpty());
        assertEquals("", PlanSubjects.Traits.EMPTY.describe(true));
        assertEquals("", PlanSubjects.Traits.EMPTY.describe(false));
        assertEquals("", PlanSubjects.Traits.EMPTY.shortLabel());
        PlanSubjects.Traits partial = new PlanSubjects.Traits("female", "十七八岁", "", "娇小", "", "");
        assertFalse(partial.isEmpty());
        assertEquals("女·十七八岁·娇小", partial.shortLabel());
    }

    @Test
    void readFromPlanJsonAndWriteBackFlat() {
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","kind":"person","gender":"男","age":"17",
                  "height":"178cm","build":"清瘦","personality":"多情",
                  "appearance":"大红箭袖","portraitAssetId":"p1"}]}
                """);
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(plan);
        assertEquals(1, subs.size());
        PlanSubjects.Traits t = subs.get(0).traitsOrEmpty();
        assertEquals("male", t.gender());     // 「男」→ male
        assertEquals("17", t.age());
        assertEquals("清瘦", t.build());

        // 写回后仍是扁平字段（前端/手工编辑都按扁平键读），且能再读回来
        ObjectNode out = M.createObjectNode();
        PlanSubjects.write(out, subs);
        JsonNode one = out.path("subjects").get(0);
        assertEquals("male", one.path("gender").asText());
        assertEquals("清瘦", one.path("build").asText());
        assertEquals("male", PlanSubjects.parse(out).get(0).traitsOrEmpty().gender());
    }

    @Test
    void legacyPlanWithoutTraitsStillParses() {
        JsonNode plan = json("{\"subjects\":[{\"name\":\"宝玉\",\"refs\":[],\"enabled\":true}]}");
        assertTrue(PlanSubjects.parse(plan).get(0).traitsOrEmpty().isEmpty());
    }

    @Test
    void aliasDuplicateMergeKeepsTheTraitsOfBoth() {
        // 「宝玉」（有性别/年龄）与别称条目「宝二爷」（有体态/定妆照）→ 归并成一条，
        // 信息不能丢：本名取“有定妆照的那个”（现有规则），另一条降级成别名。
        JsonNode plan = json("""
                {"subjects":[
                  {"name":"宝玉","kind":"person","aliases":["宝二爷"],"gender":"male","age":"17"},
                  {"name":"宝二爷","kind":"person","gender":"male","build":"清瘦","portraitAssetId":"p9"}
                ]}
                """);
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(plan);
        assertEquals(1, subs.size(), subs.toString());
        PlanSubjects.Traits t = subs.get(0).traitsOrEmpty();
        assertEquals("宝二爷", subs.get(0).name(), "本名取有定妆照的那个（既有规则）");
        assertTrue(subs.get(0).aliases().contains("宝玉"), subs.get(0).aliases().toString());
        assertEquals("male", t.gender());
        assertEquals("17", t.age(), "被并进来的那条的年龄不能丢");
        assertEquals("清瘦", t.build());
        assertTrue(subs.get(0).hasPortrait());
        // 按任一名称都能查到档案（生成时用的是方案里的主体名/主角名）
        assertEquals("17", PlanSubjects.traitsOf(plan, "宝玉").age());
        assertEquals("清瘦", PlanSubjects.traitsOf(plan, "宝二爷").build());
    }

    @Test
    void traitsOfFindsByAliasToo() {
        JsonNode plan = json("{\"subjects\":[{\"name\":\"宝玉\",\"aliases\":[\"宝二爷\"],\"gender\":\"male\"}]}");
        assertEquals("male", PlanSubjects.traitsOf(plan, "宝二爷").gender());
        assertTrue(PlanSubjects.traitsOf(plan, "袭人").isEmpty());
    }
}
