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
 * <p>为什么值得单独测：
 * <ol>
 *   <li><b>分段续写</b>（2026-09-17 实测修复）：一次要「≥4000 字」会被 `max_tokens=8000` 截断 →
 *       {@code finish_reason=length} → 422。所以**任何一段都不能再要求整篇长度**，必须只要求单段（~2200 字），
 *       且续写段必须带「已有前文」+「不得重复」+「不要总结」。</li>
 *   <li>下一集生成必须注入「精简的故事」与全部要素，否则连续性断裂而用户极难察觉。</li>
 *   <li>一致性改写（Q4）必须把待改集与它们的**当前正文**一起给模型。</li>
 * </ol>
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

    // ---------------------------------------------------------------- 字段 · 分段

    @Test
    void fieldPassOneAsksForOneSegmentOnlyAndForbidsWrappingUp() {
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 1, "");
        assertTrue(user.contains("雨夜纸船"), user);
        assertTrue(user.contains("短剧"), user);
        assertTrue(user.contains("剧本故事"), user);
        assertTrue(user.contains("第 1 段"), user);
        assertTrue(user.contains(String.valueOf(ScriptPrompts.FIELD_PASS_CHARS)), user);
        // 关键：不能再要求整篇 4000 字（那就是当初被截断的原因）
        assertFalse(user.contains("不少于 4000"), user);
        assertTrue(user.contains("不要写总结"), user);
    }

    @Test
    void fieldContinuationCarriesPreviousTextAndForbidsRepeat() {
        String soFar = "前文第一段内容ABC。".repeat(30);
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 2, soFar);
        assertTrue(user.contains("第 2 段"), user);
        assertTrue(user.contains("勿重复"), user);
        assertTrue(user.contains("接着上文继续写"), user);
        // 必须能看到前文的尾巴，否则续写会脱节
        assertTrue(user.contains("ABC。"), user);
    }

    @Test
    void fieldContinuationTailIsClippedToKeepContextBounded() {
        String soFar = "头".repeat(5000) + "尾标记END";
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.CONFLICT, null, null, false, 3, soFar);
        assertTrue(user.contains("尾标记END"), user);
        assertFalse(user.contains("头".repeat(3000)), "只应带前文尾部，不应整篇塞进上下文");
    }

    @Test
    void fromContentInjectsElementsAndEpisodesButFromTitleDoesNot() {
        List<ScriptEpisode> eps = List.of(episode(1, "纸船", "第一集正文内容"));
        String fromTitle = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, false, 1, "");
        assertFalse(fromTitle.contains("第一集正文内容"), fromTitle);

        String fromContent = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, true, 1, "");
        assertTrue(fromContent.contains("第一集正文内容"), fromContent);
        assertTrue(fromContent.contains("人与环境的对抗"), fromContent);
        assertTrue(fromContent.contains("精简的故事"), fromContent);
    }

    @Test
    void fieldSystemRequiresJsonAndOneSegmentAtATime() {
        String sys = ScriptPrompts.fieldSystem();
        assertTrue(sys.contains("纯 JSON"), sys);
        assertTrue(sys.contains(String.valueOf(ScriptPrompts.FIELD_PASS_CHARS)), sys);
        assertTrue(sys.contains("不得重复"), sys);
    }

    // ---------------------------------------------------------------- 下一集 · 分段

    @Test
    void episodeUserCarriesCondensedStoryAndNextNo() {
        String user = ScriptPrompts.episodeUser(script(), List.of(episode(1, "纸船", "正文")), 2, "重逢", "留钩子", 1, "");
        assertTrue(user.contains("精简的故事"), user);
        assertTrue(user.contains("第 2 集"), user);
        assertTrue(user.contains("重逢"), user);
        assertTrue(user.contains("留钩子"), user);
        assertTrue(user.contains("第 1 集摘要"), user);
        assertTrue(user.contains("第 1 段"), user);
    }

    @Test
    void episodeSegmentsHaveDistinctRolesAndContinuationRules() {
        String p1 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 1, "");
        assertTrue(p1.contains("起"), p1);
        assertTrue(p1.contains("这是第一集"), p1);

        String p2 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 2, "前文内容XYZ");
        assertTrue(p2.contains("承"), p2);
        assertTrue(p2.contains("勿重复"), p2);
        assertTrue(p2.contains("前文内容XYZ"), p2);

        String p3 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 3, "前文内容XYZ");
        assertTrue(p3.contains("钩子"), p3);
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
            assertTrue(f.help().length() > 40, f.key());
        }
        assertEquals(null, ScriptField.of("title"));
        assertEquals(null, ScriptField.of("genre"));
    }

    @Test
    void multiPassBudgetCanReachTheHardMinimum() {
        // 分段预算必须够：否则「≥4000 字」的硬要求永远达不到
        assertTrue(ScriptPrompts.FIELD_PASS_CHARS * 2 >= ScriptPrompts.FIELD_MIN_CHARS,
                "两段就应达到硬下限（保证常规只需 2 次调用）");
        assertTrue(ScriptPrompts.EPISODE_PASS_CHARS * 2 >= ScriptPrompts.EPISODE_MIN_CHARS,
                "两段就应达到硬下限");
        assertTrue(ScriptPrompts.MAX_PASSES >= 2);
    }

    @Test
    void clipNeverExceedsLimit() {
        String s = "字".repeat(ScriptPrompts.FIELD_MAX_CHARS + 50);
        assertEquals(ScriptPrompts.FIELD_MAX_CHARS + 1, ScriptPrompts.clip(s, ScriptPrompts.FIELD_MAX_CHARS).length());
        assertEquals("短", ScriptPrompts.clip("短", 10));
        assertEquals("", ScriptPrompts.clip(null, 10));
    }
}
