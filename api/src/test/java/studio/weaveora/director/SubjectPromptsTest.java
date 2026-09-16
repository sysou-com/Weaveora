package studio.weaveora.director;

import org.junit.jupiter.api.Test;

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
        assertTrue(zh.contains("纯色背景"), zh);
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
}
