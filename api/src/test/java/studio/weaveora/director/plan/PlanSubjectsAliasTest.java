package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 别称必须认定为「同一个主体」（P13，2026-09-16 用户实测第 4 镜一致性又崩）。
 *
 * <p>事故链：参考图面板里把某张图标成**别称**（主体本名「宝玉」、图上写「宝二爷」）→
 * 方案里多出一个叫「宝二爷」的主体：带素材图但**没有定妆照**，真主体「宝玉」的 refs 被清空 →
 * 生成时按主体找定妆照，「宝二爷」找不到（主主体直接报错 / 非主主体被静默剔除）→
 * 那一镜就没有身份锚定，人物对不上参考图。
 */
class PlanSubjectsAliasTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aliasNamedSubjectIsMergedIntoTheCanonicalOne() {
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(json("""
                {"subjects":[
                  {"name":"宝玉","aliases":["贾宝玉","宝二爷"],"enabled":true,
                   "refs":[],"portraitAssetId":"p-baoyu"},
                  {"name":"宝二爷","aliases":[],"enabled":true,
                   "refs":[{"assetId":"a1","checked":true}],"portraitAssetId":""}
                ]}
                """));

        assertEquals(1, subs.size(), "别称条目必须并进本名主体");
        PlanSubjects.Subject s = subs.get(0);
        assertEquals("宝玉", s.name(), "保留有定妆照的那个作为本名");
        assertEquals("p-baoyu", s.portraitAssetId());
        assertEquals(1, s.checkedRefs().size(), "被并进来的素材图必须保住");
        assertEquals("a1", s.checkedRefs().get(0).assetId());
        assertTrue(s.aliases().contains("宝二爷"), "原别称要保留可匹配性");
    }

    @Test
    void reverseDirectionAlsoMerges() {
        // 反过来：条目本名叫「贾宝玉」而另一个主体把它当别称 → 也要合成一个
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(json("""
                {"subjects":[
                  {"name":"宝玉","aliases":["贾宝玉"],"enabled":true,"refs":[],"portraitAssetId":"p1"},
                  {"name":"贾宝玉","aliases":[],"enabled":true,
                   "refs":[{"assetId":"a2","checked":true}],"portraitAssetId":""}
                ]}
                """));

        assertEquals(1, subs.size());
        assertEquals("宝玉", subs.get(0).name());
        assertEquals(1, subs.get(0).checkedRefs().size());
    }

    @Test
    void refsAreDeduplicatedByAssetAndCheckedIsOred() {
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(json("""
                {"subjects":[
                  {"name":"宝玉","aliases":["宝二爷"],"enabled":true,
                   "refs":[{"assetId":"a1","checked":false}],"portraitAssetId":"p1"},
                  {"name":"宝二爷","aliases":[],"enabled":true,
                   "refs":[{"assetId":"a1","checked":true},{"assetId":"a2","checked":true}]}
                ]}
                """));

        assertEquals(1, subs.size());
        List<PlanSubjects.Ref> refs = subs.get(0).refs();
        assertEquals(2, refs.size(), "同一 assetId 只能出现一次");
        assertTrue(refs.stream().anyMatch(r -> r.assetId().equals("a1") && r.checked()),
                "任一边勾选过就算勾选");
    }

    @Test
    void subjectsWithoutAliasLinksStaySeparate() {
        List<PlanSubjects.Subject> subs = PlanSubjects.parse(json("""
                {"subjects":[
                  {"name":"宝玉","aliases":["贾宝玉"],"enabled":true,"refs":[],"portraitAssetId":"p1"},
                  {"name":"可卿","aliases":["秦可卿"],"enabled":true,"refs":[],"portraitAssetId":"p2"}
                ]}
                """));

        assertEquals(2, subs.size(), "没有别称交集的角色不能互相吞并");
    }

    @Test
    void isSameSubjectAcceptsAliasesAndAbbreviations() {
        PlanSubjects.Subject s = new PlanSubjects.Subject("宝玉", "person",
                List.of("贾宝玉", "宝二爷"), true, false, List.of(), "p1", 1);
        assertTrue(PlanSubjects.isSameSubject(s, "宝玉"));
        assertTrue(PlanSubjects.isSameSubject(s, "宝二爷"));
        assertTrue(PlanSubjects.isSameSubject(s, "贾宝玉"));
        assertFalse(PlanSubjects.isSameSubject(s, "可卿"));
    }
}
