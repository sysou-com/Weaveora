package studio.weaveora.script;

import org.junit.jupiter.api.Test;
import studio.weaveora.script.api.ScriptConflict;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptEpisode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剧本提示词的**单一真源**契约（{@link ScriptPrompts}）。
 *
 * <p>为什么值得单独测：这套组装同时决定
 * ① 字段生成/更新（新建页与详情页共用同一段 system+user）；
 * ② 下一集生成（必须把「精简的故事」与全部要素喂给模型，否则连续性就断了）；
 * ③ 一致性检查与改写（Q4：未确认不得改历史章节）。
 * 任何一处漏注入「精简的故事」或要素，都会导致 AI 写出与已写章节矛盾的内容，而用户极难察觉。
 */
class ScriptPromptsTest {

    private static Script script() {
        return Script.create(null, null, "雨夜纸船", "短剧",
                "林知远：旧书店店主，执拗。", "起因…发展…高潮…结局。", "人与环境的对抗。",
                "开端/发展/转折/高潮/结局", "冷峻克制的对白。", "【深夜 · 旧书店二楼】");
    }

    private static ScriptEpisode episode(int no, String title, String content) {
        return ScriptEpisode.create(null, null, no, title, content, "第 " + no + " 集摘要", true);
    }

    // ---------------------------------------------------------------- 字段

    @Test
    void fieldUserAlwaysCarriesTitleGenreAndLengthRequirement() {
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false);
        assertTrue(user.contains("雨夜纸船"), user);
        assertTrue(user.contains("短剧"), user);
        assertTrue(user.contains("剧本故事"), user);
        // Q2：AI 生成必须不少于 4000 字
        assertTrue(user.contains(String.valueOf(ScriptPrompts.FIELD_MIN_CHARS)), user);
    }

    @Test
    void fromContentInjectsElementsAndEpisodesButFromTitleDoesNot() {
        List<ScriptEpisode> eps = List.of(episode(1, "纸船", "第一集正文内容"));
        String fromTitle = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, false);
        assertFalse(fromTitle.contains("第一集正文内容"), fromTitle);

        String fromContent = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, true);
        assertTrue(fromContent.contains("第一集正文内容"), fromContent);
        assertTrue(fromContent.contains("人与环境的对抗"), fromContent);
        assertTrue(fromContent.contains("精简的故事"), fromContent);
    }

    @Test
    void fieldSystemPromisesJsonOnlyAndMinLength() {
        String sys = ScriptPrompts.fieldSystem();
        assertTrue(sys.contains("纯 JSON"), sys);
        assertTrue(sys.contains(String.valueOf(ScriptPrompts.FIELD_MIN_CHARS)), sys);
    }

    // ---------------------------------------------------------------- 下一集

    @Test
    void episodeUserCarriesCondensedStoryAndNextNo() {
        String user = ScriptPrompts.episodeUser(script(), List.of(episode(1, "纸船", "正文")), 2, "重逢", "留钩子");
        assertTrue(user.contains("精简的故事"), user);
        assertTrue(user.contains("第 2 集"), user);
        assertTrue(user.contains("重逢"), user);
        assertTrue(user.contains("留钩子"), user);
        // 摘要必须带、正文节选不必带（控上下文）
        assertTrue(user.contains("第 1 集摘要"), user);
    }

    @Test
    void episodeUserWithoutEpisodesSaysFirstEpisode() {
        String user = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null);
        assertTrue(user.contains("这是第一集"), user);
    }

    // ---------------------------------------------------------------- 精简故事

    @Test
    void condenseUserCarriesOldStoryAndAllEpisodesWithBody() {
        String user = ScriptPrompts.condenseUser(script(), List.of(episode(1, "纸船", "第一集正文内容")));
        assertTrue(user.contains("现有精简的故事"), user);
        assertTrue(user.contains("第一集正文内容"), user);
        assertTrue(user.contains(String.valueOf(ScriptPrompts.CONDENSED_MAX_CHARS)), user);
    }

    // ---------------------------------------------------------------- 一致性改写

    @Test
    void rewriteEpisodesUserListsTargetsAndTheirCurrentBody() {
        List<ScriptConflict> items = List.of(new ScriptConflict(1, "纸船", "人物动机断裂", "补一句犹豫"));
        String user = ScriptPrompts.rewriteEpisodesUser(script(), items,
                List.of(episode(1, "纸船", "第一集正文内容")));
        assertTrue(user.contains("第 1 集"), user);
        assertTrue(user.contains("人物动机断裂"), user);
        assertTrue(user.contains("补一句犹豫"), user);
        assertTrue(user.contains("第一集正文内容"), user);
        assertTrue(user.contains("\"episodes\""), user);
    }

    // ---------------------------------------------------------------- brief

    @Test
    void briefUserCarriesEpisodeBodyAndCapsLength() {
        String user = ScriptPrompts.briefUser(script(), episode(3, "重逢", "第三集正文内容"));
        assertTrue(user.contains("第三集正文内容"), user);
        assertTrue(user.contains("第 3 集"), user);
        assertTrue(user.contains(String.valueOf(ScriptPrompts.BRIEF_MAX_CHARS)), user);
        assertTrue(user.contains("\"brief\""), user);
    }

    // ---------------------------------------------------------------- 字段枚举

    @Test
    void fieldEnumIsTheSingleSourceForKeysAndLabels() {
        assertEquals(6, ScriptField.values().length);
        for (ScriptField f : ScriptField.values()) {
            assertNotNull(ScriptField.of(f.key()), f.key());
            assertNotNull(ScriptField.of(f.key().toUpperCase()), "大小写不敏感: " + f.key());
            assertFalse(f.label().isBlank(), f.key());
            // 字段说明就是用户在界面上看到的括注原文，必须非空且够具体
            assertTrue(f.help().length() > 40, f.key());
        }
        // 标题与类型不是可 AI 生成的要素（Q2/Q8）
        assertEquals(null, ScriptField.of("title"));
        assertEquals(null, ScriptField.of("genre"));
    }

    @Test
    void clipNeverExceedsLimit() {
        String s = "字".repeat(ScriptPrompts.FIELD_MAX_CHARS + 50);
        assertEquals(ScriptPrompts.FIELD_MAX_CHARS + 1, ScriptPrompts.clip(s, ScriptPrompts.FIELD_MAX_CHARS).length());
        assertEquals("短", ScriptPrompts.clip("短", 10));
        assertEquals("", ScriptPrompts.clip(null, 10));
    }
}
