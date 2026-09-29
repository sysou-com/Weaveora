package studio.weaveora.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中英混排检测（2026-09-29 用户裁定「最终提示词混排要弹框 + 一键 AI 优化」）。
 *
 * <p>判据故意保守：{@code image 1} / {@code CGI} / 中文角色名这类**专有名词与槽位标记**不算混排；
 * 只有「一整段英文 + 一整段中文」才算。用例里那条 MIXED 取自线上第 4 镜真实正词。
 */
class PromptLangTest {

    /** 线上第 4 镜真实正词（英文风格前缀 + 中文正文）——必须命中，且主导语言是中文。 */
    @Test
    void detectsRealProductionMixedPrompt() {
        String p = "Cinematic film still, shallow depth of field, 35mm film, 清代太虚幻境，云雾缭绕，朱栏玉砌，"
                + "纱帐帷幔；过肩镜头群像，平拍，缓慢推近；雾气氤氲；警幻（image 3）急追而至，挥袖阻拦，"
                + "宝玉(image 1)回身发问，可卿（image 2）贴近宝玉；警幻惶急摇头，告知迷津黑水翻涌、无舟可渡、"
                + "凶险异常；写实摄影，细腻质感，紧张诡谲。【构图硬约束（本镜已由用户确认）】本镜为过肩镜头；"
                + "画面里只让「警幻」露出清晰人脸，其余人物不入画，或只作前景遮挡/背影；主脸约占画面高度的五分之一。"
                + "【本镜不露脸的角色】宝玉、可卿：本镜看不到它们的正脸（背影/侧脸），但它们各自的头型、发型、"
                + "头饰与服装仍然严格取自各自的参考图。【设定年代/世界观】清代 · 康熙年间。";
        assertTrue(PromptLang.isMixed(p), PromptLang.describe(p));
        assertEquals("zh", PromptLang.dominant(p), PromptLang.describe(p));
    }

    /** 全中文（含 image 1 槽位标记）——不算混排。 */
    @Test
    void chineseWithSlotMarkersIsNotMixed() {
        String p = "清代太虚幻境，云雾缭绕，朱栏玉砌，纱帐帷幔；警幻（image 3）急追而至，宝玉(image 1)回身发问，"
                + "可卿（image 2）贴近宝玉；写实摄影，细腻质感。";
        assertFalse(PromptLang.isMixed(p), PromptLang.describe(p));
        assertEquals("zh", PromptLang.dominant(p));
    }

    /** 全英文（含中文角色名/槽位）——不算混排。 */
    @Test
    void englishWithChineseNamesIsNotMixed() {
        String p = "Cinematic film still, shallow depth of field, 35mm film. The Grand Illusory Realm of the "
                + "Qing dynasty; Jinghuan (image 3) rushes up from behind; Baoyu (宝玉, image 1) turns away; "
                + "Keqing (image 2) draws close. Realistic photography, delicate texture.";
        assertFalse(PromptLang.isMixed(p), PromptLang.describe(p));
        assertEquals("en", PromptLang.dominant(p));
    }

    /** 英文正文 + 后附一整个中文自动拼装块 ⇒ 混排（正是"中文正文 + 英文前缀"的镜像场景）。 */
    @Test
    void englishBodyWithChineseAppendedBlockIsMixed() {
        String p = "Cinematic film still, shallow depth of field, 35mm film. Jinghuan rushes up from behind "
                + "and blocks the way with a sweep of her sleeve, then shakes her head in alarm as she tells "
                + "them that the black waters of the Ford of Illusion are churning. Realistic photography, "
                + "delicate texture, tense and uncanny. "
                + "【构图硬约束（本镜已由用户确认）】本镜为过肩镜头；画面里只让「警幻」露出清晰人脸，"
                + "其余人物不入画，或只作前景遮挡/背影；主脸约占画面高度的五分之一。";
        assertTrue(PromptLang.isMixed(p), PromptLang.describe(p));
        assertEquals("en", PromptLang.dominant(p), "英文侧更长 ⇒ 默认优化目标语言应为英文");
    }

    @Test
    void degenerateInputs() {
        assertFalse(PromptLang.isMixed(null));
        assertFalse(PromptLang.isMixed(""));
        assertEquals("", PromptLang.dominant(null));
        assertEquals("", PromptLang.dominant("12345 ！？"));
        assertEquals(0, PromptLang.cjkCount(null));
        assertEquals(0, PromptLang.latinLetterCount(null));
    }
}
