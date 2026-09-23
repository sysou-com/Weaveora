package studio.weaveora.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定妆图提示词的**单一真源**契约（2026-09-16 用户要求：生成定妆图也要弹正/负向提示词）。
 *
 * <p>为什么值得单独测：这套模板同时被
 * ① {@code JobService.createPortraitJob}（真正落库执行的那份）与
 * ② {@code GET /projects/{id}/portrait-prompt}（前端弹框预填的那份）
 * 使用；两边必须完全一致，否则会出现「界面上看到 A、实际用 B」（用户无法察觉）。
 */
class SubjectPromptsTest {

    @Test
    void personPromptIsACharacterSheetNotAGenericPortrait() {
        String zh = SubjectPrompts.portraitPrompt("宝玉", "person", 0);
        assertTrue(zh.contains("宝玉"), zh);
        assertTrue(zh.contains("标准角色设定图"), zh);
        // 2026-09-19（用户要求）：底色统一为**纯白色** —— 原为“纯色背景”，模型可自选暗色/暖色底，
        //   各版定妆照底色不一致 → 作参考时把关键帧的色调带偏（实测白底版归一化后脸宽 157px，
        //   带环境氛围版只剩 61px）。所以断言改成白底，并把“不要环境/场景元素”也纳入契约。
        assertTrue(zh.contains("纯白色背景"), zh);
        assertTrue(zh.contains("不要任何环境"), zh);
        // 必须显式禁止多人物/文字/边框 —— 定妆照会被当锚定图，画面里多一个人就整体串脸
        assertTrue(zh.contains("不要多人物"), zh);
        assertTrue(zh.contains("不要文字"), zh);
    }

    @Test
    void refCountIsAppendedOnlyWhenThereAreReferences() {
        assertFalse(SubjectPrompts.portraitPrompt("宝玉", "person", 0).contains("Reference image(s)"));
        assertTrue(SubjectPrompts.portraitPrompt("宝玉", "person", 2).contains("Reference image(s): 2"));
    }

    @Test
    void kindSwitchesTemplateButUnknownKindFallsBackToPerson() {
        String vehicle = SubjectPrompts.portraitPrompt("赤兔马", "vehicle", 1);
        assertTrue(vehicle.contains("赤兔马"), vehicle);
        assertTrue(vehicle.contains("三视图"), vehicle);
        String unknown = SubjectPrompts.portraitPrompt("宝玉", null, 0);
        assertTrue(unknown.contains("标准角色设定图"), unknown);
        assertNotEquals(vehicle, unknown);
    }

    @Test
    void negativePromptCoversTheTrapsWeActuallyHit() {
        String neg = SubjectPrompts.portraitNegativePrompt();
        assertTrue(neg.contains("multiple people"), neg);
        assertTrue(neg.contains("watermark"), neg);
        assertTrue(neg.contains("deformed"), neg);
        // 中文负面项也要有（用户选中文提示词时不得整条只有英文词）
        assertTrue(neg.contains("水印"), neg);
        assertFalse(neg.contains("white background"), "白底由 worker 按语言追加，不写进默认负词");
    }

    // ---------- ★ P14：定妆照必须体现主体设定（性别/年龄/体态） ----------

    @Test
    void traitsAreWrittenIntoThePortraitPromptAndGenderIsForbiddenExplicitly() {
        var traits = new studio.weaveora.director.plan.PlanSubjects.Traits(
                "male", "17", "178cm", "清瘦", "多情敏感", "大红箭袖");
        String p = SubjectPrompts.portraitPrompt("宝玉", "person", 2, traits);
        assertTrue(p.contains("角色设定（必须体现在画面里）：性别 男 male"), p);
        assertTrue(p.contains("年龄 17"), p);
        assertTrue(p.contains("体态 清瘦"), p);
        // 男角色 → 明确禁止画成女性（用户实测的「宝玉被当女性」）
        assertTrue(p.contains("严重禁止画成女性"), p);
        assertTrue(p.contains("Reference image(s): 2"), p);

        String female = SubjectPrompts.portraitPrompt("可卿", "person", 0,
                new studio.weaveora.director.plan.PlanSubjects.Traits("female", "", "", "", "", ""));
        assertTrue(female.contains("严重禁止画成男性"), female);

        // 未知性别（other）不能硬画成一个性别
        String other = SubjectPrompts.portraitPrompt("某人", "person", 0,
                new studio.weaveora.director.plan.PlanSubjects.Traits("other", "", "", "", "", ""));
        assertTrue(other.contains("如实表现性别特征"), other);
    }

    @Test
    void portraitPromptWithoutTraitsStaysAsBefore() {
        String p = SubjectPrompts.portraitPrompt("宝玉", "person", 0,
                studio.weaveora.director.plan.PlanSubjects.Traits.EMPTY);
        assertFalse(p.contains("角色设定（必须体现在画面里）"), p);
        assertTrue(p.contains("标准角色设定图"), p);
    }

    // ---------- ★ 2026-09-23（用户要求）：定妆照弹框也要能选**提示词语言**（默认中文） ----------

    @Test
    void languageDefaultsToChineseWhenNotGiven() {
        assertEquals(SubjectPrompts.portraitPrompt("宝玉", "person", 0),
                SubjectPrompts.portraitPrompt("宝玉", "person", 0, null, "zh"));
        // 认不出的语言值一律当中文（防前端传来 null/空/大写/乱码）
        assertEquals(SubjectPrompts.portraitPrompt("宝玉", "person", 0),
                SubjectPrompts.portraitPrompt("宝玉", "person", 0, null, ""));
        assertEquals(SubjectPrompts.portraitPrompt("宝玉", "person", 0),
                SubjectPrompts.portraitPrompt("宝玉", "person", 0, null, "cn"));
        assertEquals(SubjectPrompts.portraitPrompt("宝玉", "person", 0),
                SubjectPrompts.portraitPrompt("宝玉", "person", 0, null, null));
    }

    @Test
    void englishTemplateKeepsTheSameGuardRails() {
        String en = SubjectPrompts.portraitPrompt("宝玉", "person", 2, null, "en");
        assertTrue(en.contains("宝玉"), en);
        assertTrue(en.contains("Standard character sheet"), en);
        assertTrue(en.contains("pure white background"), en);
        // 与中文模板同一套硬规则：单主体 / 无文字 / 无环境
        assertTrue(en.contains("no extra people"), en);
        assertTrue(en.contains("no text"), en);
        assertTrue(en.contains("no environment"), en);
        // 参考图计数句两种语言都用英文（模型对这句更敏感，且旧行为即如此）
        assertTrue(en.contains("Reference image(s): 2"), en);
        assertFalse(en.contains("标准角色设定图"), en);
        // 大小写不敏感（前端可能传 EN）
        assertTrue(SubjectPrompts.portraitPrompt("宝玉", "person", 0, null, "EN").contains("Standard character sheet"));
    }

    @Test
    void englishTraitsAndGenderBanAreTranslated() {
        var traits = new studio.weaveora.director.plan.PlanSubjects.Traits(
                "male", "17", "178cm", "清瘦", "多情敏感", "大红箭袖");
        String en = SubjectPrompts.portraitPrompt("宝玉", "person", 0, traits, "en");
        assertTrue(en.contains("Character specs (must be visible in the image): gender male (男)"), en);
        assertTrue(en.contains("age 17"), en);
        assertTrue(en.contains("This character is MALE"), en);
        assertTrue(en.contains("forbidden to depict a female"), en);

        String female = SubjectPrompts.portraitPrompt("可卿", "person", 0,
                new studio.weaveora.director.plan.PlanSubjects.Traits("female", "", "", "", "", ""), "en");
        assertTrue(female.contains("forbidden to depict a male"), female);

        String other = SubjectPrompts.portraitPrompt("某人", "person", 0,
                new studio.weaveora.director.plan.PlanSubjects.Traits("other", "", "", "", "", ""), "en");
        assertTrue(other.contains("depict the gender faithfully"), other);
    }

    @Test
    void englishKindTemplatesAndUnknownKindFallback() {
        String vehicle = SubjectPrompts.portraitPrompt("赤兔马", "vehicle", 0, null, "en");
        assertTrue(vehicle.contains("three-view"), vehicle);
        String object = SubjectPrompts.portraitPrompt("通灵宝玉", "object", 0, null, "en");
        assertTrue(object.contains("Standard prop sheet"), object);
        String scene = SubjectPrompts.portraitPrompt("太虚幻境", "scene", 0, null, "en");
        assertTrue(scene.contains("Standard environment sheet"), scene);
        String unknown = SubjectPrompts.portraitPrompt("宝玉", "???", 0, null, "en");
        assertTrue(unknown.contains("Standard character sheet"), unknown);
        assertNotEquals(vehicle, unknown);
    }

    @Test
    void negativePromptIsBilingualSoItWorksForBothLanguages() {
        // 负词是单一真源、中英双写 → 选 en 时不必也另写一份（否则两份会漂移）
        String neg = SubjectPrompts.portraitNegativePrompt();
        assertTrue(neg.contains("watermark"), neg);
        assertTrue(neg.contains("水印"), neg);
    }
}
