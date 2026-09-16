package studio.weaveora.script;

import org.junit.jupiter.api.Test;
import studio.weaveora.script.api.ScriptConflict;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptEpisode;

import java.util.ArrayList;
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

    private static final ScriptPrompts.Outline NO_OUTLINE = ScriptPrompts.Outline.empty();
    private static final ScriptPrompts.Outline OUTLINE = new ScriptPrompts.Outline(
            List.of("1. 起 —— 引入场景；抛出冲突", "2. 承转 —— 推进冲突", "3. 合 —— 收束留钩"));

    // ---------------------------------------------------------------- 字段 · 分段

    @Test
    void fieldPassOneAsksForOneSegmentOnlyAndForbidsWrappingUp() {
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 1, "", 4000, OUTLINE);
        assertTrue(user.contains("雨夜纸船"), user);
        assertTrue(user.contains("短剧"), user);
        assertTrue(user.contains("剧本故事"), user);
        assertTrue(user.contains("第 1 段"), user);
        assertTrue(user.contains("约 2000 字"), user);   // 4000 → 2 段 × 2000
        assertTrue(user.contains(String.valueOf(ScriptPrompts.MAX_SEGMENT_CHARS)), user);
        // 关键：不能再要求整篇 4000 字（那就是当初被截断的原因）
        assertFalse(user.contains("不少于 4000"), user);
        assertTrue(user.contains("不要写总结"), user);
    }

    @Test
    void fieldContinuationCarriesPreviousTextAndForbidsRepeat() {
        String soFar = "前文第一段内容ABC。".repeat(30);
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 2, soFar, 4000, NO_OUTLINE);
        assertTrue(user.contains("第 2 段"), user);
        assertTrue(user.contains("勿重复"), user);
        assertTrue(user.contains("接着上文继续写"), user);
        // 必须能看到前文的尾巴，否则续写会脱节
        assertTrue(user.contains("ABC。"), user);
    }

    @Test
    void fieldContinuationTailIsClippedToKeepContextBounded() {
        String soFar = "头".repeat(5000) + "尾标记END";
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.CONFLICT, null, null, false, 3, soFar, 4000, NO_OUTLINE);
        assertTrue(user.contains("尾标记END"), user);
        assertFalse(user.contains("头".repeat(3000)), "只应带前文尾部，不应整篇塞进上下文");
    }

    @Test
    void fromContentInjectsElementsAndEpisodesButFromTitleDoesNot() {
        List<ScriptEpisode> eps = List.of(episode(1, "纸船", "第一集正文内容"));
        String fromTitle = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, false, 1, "", 4000, NO_OUTLINE);
        assertFalse(fromTitle.contains("第一集正文内容"), fromTitle);

        String fromContent = ScriptPrompts.fieldUser(script(), eps, ScriptField.CONFLICT, null, null, true, 1, "", 4000, NO_OUTLINE);
        assertTrue(fromContent.contains("第一集正文内容"), fromContent);
        assertTrue(fromContent.contains("人与环境的对抗"), fromContent);
        assertTrue(fromContent.contains("精简的故事"), fromContent);
    }

    @Test
    void fieldSystemRequiresJsonAndOneSegmentAtATime() {
        String sys = ScriptPrompts.fieldSystem();
        assertTrue(sys.contains("纯 JSON"), sys);
        assertTrue(sys.contains(String.valueOf(ScriptPrompts.MAX_SEGMENT_CHARS)), sys);
        assertTrue(sys.contains("不得重复"), sys);
    }

    // ---------------------------------------------------------------- 下一集 · 分段

    @Test
    void episodeUserCarriesCondensedStoryAndNextNo() {
        String user = ScriptPrompts.episodeUser(script(), List.of(episode(1, "纸船", "正文")), 2, "重逢", "留钩子", 1, "", OUTLINE);
        assertTrue(user.contains("精简的故事"), user);
        assertTrue(user.contains("第 2 集"), user);
        assertTrue(user.contains("重逢"), user);
        assertTrue(user.contains("留钩子"), user);
        assertTrue(user.contains("第 1 集摘要"), user);
        assertTrue(user.contains("第 1 段"), user);
    }

    @Test
    void episodeSegmentsHaveDistinctRolesAndContinuationRules() {
        String p1 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 1, "", OUTLINE);
        assertTrue(p1.contains("起"), p1);
        assertTrue(p1.contains("这是第一集"), p1);

        String p2 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 2, "前文内容XYZ", OUTLINE);
        assertTrue(p2.contains("承"), p2);
        assertTrue(p2.contains("勿重复"), p2);
        assertTrue(p2.contains("前文内容XYZ"), p2);

        String p3 = ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 3, "前文内容XYZ", OUTLINE);
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
    void multiPassBudgetCanReachTheTargetWithinSegmentCeiling() {
        // 关键不变式：每段 ≤ MAX_SEGMENT_CHARS（否则单次输出会被 max_tokens 截断）
        for (int target : new int[]{500, 1000, 2200, 4000, 6000, 8000}) {
            ScriptPrompts.PassPlan p = ScriptPrompts.planFor(target);
            assertTrue(p.perPass() <= ScriptPrompts.MAX_SEGMENT_CHARS,
                    "target=" + target + " perPass=" + p.perPass() + " 超过单段硬上限");
            assertTrue(p.perPass() * p.passes() >= Math.min(target, ScriptPrompts.FIELD_TARGET_MAX),
                    "target=" + target + " 的段数×每段应能覆盖目标");
            assertTrue(p.passes() >= 1 && p.passes() <= ScriptPrompts.MAX_PASSES,
                    "target=" + target + " passes=" + p.passes());
        }
    }

    @Test
    void planShrinksPassCountForSmallTargetsAndSplitsForLarge() {
        assertEquals(1, ScriptPrompts.planFor(1000).passes());
        assertEquals(2, ScriptPrompts.planFor(4000).passes());
        assertEquals(4, ScriptPrompts.planFor(8000).passes());
        assertEquals(2000, ScriptPrompts.planFor(8000).perPass());
    }

    @Test
    void targetIsClampedToUserAllowedRange() {
        // 用户 2026-09-17：最大值不超过 8000
        assertEquals(ScriptPrompts.FIELD_TARGET_MAX, ScriptPrompts.clampTarget(99999));
        assertEquals(ScriptPrompts.FIELD_TARGET_MIN, ScriptPrompts.clampTarget(10));
        assertEquals(ScriptPrompts.FIELD_TARGET_DEFAULT, ScriptPrompts.clampTarget(0));
        assertEquals(ScriptPrompts.FIELD_TARGET_DEFAULT, ScriptPrompts.clampTarget(-5));
        assertEquals(6000, ScriptPrompts.clampTarget(6000));
    }

    @Test
    void fieldUserHonoursUserTargetLength() {
        String small = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 1, "", 1000, NO_OUTLINE);
        assertTrue(small.contains("约 1000 字"), small);
        String big = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 1, "", 8000, NO_OUTLINE);
        assertTrue(big.contains("约 2000 字"), big);   // 8000 → 4 段 × 2000
        assertTrue(big.contains(String.valueOf(ScriptPrompts.MAX_SEGMENT_CHARS)), big);
    }

    // ---------------------------------------------------------------- 【A】上下文预算

    private static Script longScript() {
        String big = "长".repeat(ScriptPrompts.FIELD_TARGET_MAX);
        return Script.create(null, null, "超长剧本", "电视剧", big, big, big, big, big, big);
    }

    private static ScriptEpisode longEpisode(int no, String marker) {
        return ScriptEpisode.create(null, null, no, "第" + no + "集",
                marker + "标".repeat(3000), "摘要" + no, true);
    }

    @Test
    void contextBudgetBoundsInputAndRecordsTrims() {
        List<ScriptEpisode> eps = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            eps.add(longEpisode(i, "EP" + i + "-"));
        }
        ScriptPrompts.Block b = ScriptPrompts.context(longScript(), eps, "story", true);
        assertTrue(b.trimmed(), "应记录裁剪情况（不静默）");
        assertTrue(b.text().length() <= ScriptPrompts.CONTEXT_BUDGET_CHARS + 3000,
                "总上下文应受预算约束，实际=" + b.text().length());
        // 六要素各 8000 字（共 4.8 万）→ 必须被压到远小于原量
        assertTrue(b.text().length() < 20000, "实际=" + b.text().length());
        // 目标字段全文保留；其它要素只留头尾
        assertTrue(b.text().contains("长".repeat(2000)), "目标字段应为全文");
        assertTrue(b.text().contains("（中略）"), "非目标要素应被摘要");
    }

    @Test
    void contextKeepsAllSummariesButOnlyRecentEpisodeBodies() {
        List<ScriptEpisode> eps = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            eps.add(longEpisode(i, "MARK" + i + "-"));
        }
        ScriptPrompts.Block b = ScriptPrompts.context(script(), eps, null, true);
        for (int i = 1; i <= 6; i++) {
            assertTrue(b.text().contains("摘要" + i), "第 " + i + " 集摘要应保留（全量）");
        }
        assertTrue(b.text().contains("MARK6-"), "最近一集正文应保留");
        assertTrue(b.text().contains("MARK5-"), "最近 2 集正文应保留");
        assertFalse(b.text().contains("MARK1-"), "更早的集只给摘要（控上下文）");
    }

    @Test
    void digestKeepsHeadAndTail() {
        String raw = "头".repeat(500) + "中".repeat(500) + "尾".repeat(500);
        String d = ScriptPrompts.digest(raw, 300);
        assertTrue(d.contains("（中略）"), d);
        assertTrue(d.startsWith("头"), d);
        assertTrue(d.endsWith("尾"), d);
        assertEquals("短文本不动", ScriptPrompts.digest("短文本不动", 300));
    }

    @Test
    void condensedStoryIsAlwaysInjectedAsWholePlayMemory() {
        // 【C】无论哪条链路都带上「精简的故事」（空则显式写「尚无」，不让模型猜）
        assertTrue(ScriptPrompts.guideUser(script(), List.of()).contains("精简的故事"));
        assertTrue(ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 1, "", 4000, NO_OUTLINE)
                .contains("精简的故事"));
        assertTrue(ScriptPrompts.episodeUser(script(), List.of(), 1, null, null, 1, "", NO_OUTLINE)
                .contains("精简的故事"));
        assertTrue(ScriptPrompts.context(script(), List.of(), null, false).text().contains("尚无"));
    }

    // ---------------------------------------------------------------- 【B】提纲

    @Test
    void parseOutlineAcceptsObjectShapeAndPlainStrings() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        ScriptPrompts.Outline o = ScriptPrompts.parseOutline(om.readTree(
                "{\"segments\":[{\"index\":1,\"title\":\"起\",\"points\":[\"a\",\"b\"]},"
                        + "{\"index\":2,\"title\":\"承\",\"points\":[\"c\"]}]}"));
        assertEquals(2, o.segments().size());
        assertTrue(o.segments().get(0).contains("起"), o.segments().get(0));
        assertTrue(o.segments().get(0).contains("a；b"), o.segments().get(0));
        ScriptPrompts.Outline o2 = ScriptPrompts.parseOutline(om.readTree("{\"outline\":[\"甲\",\"乙\"]}"));
        assertEquals(2, o2.segments().size());
        assertTrue(o2.at(2).contains("乙"), o2.at(2));
        assertEquals("", o2.at(9));
        assertTrue(ScriptPrompts.parseOutline(om.readTree("{}")).isEmpty());
    }

    @Test
    void outlineIsInjectedSoEachPassKnowsItsOwnSlice() {
        String user = ScriptPrompts.fieldUser(script(), List.of(), ScriptField.STORY, null, null, false, 2, "前文", 4000, OUTLINE);
        assertTrue(user.contains("写作提纲"), user);
        assertTrue(user.contains("1. 起"), user);          // 全貌可见，避免越界写后面段落
        assertTrue(user.contains("2. 承转"), user);        // 本段要写的那一条
        assertTrue(user.contains("严格按提纲这一条写"), user);
    }

    @Test
    void clipNeverExceedsLimit() {
        String s = "字".repeat(ScriptPrompts.FIELD_TARGET_MAX + 50);
        assertEquals(ScriptPrompts.FIELD_TARGET_MAX + 1, ScriptPrompts.clip(s, ScriptPrompts.FIELD_TARGET_MAX).length());
        assertEquals("短", ScriptPrompts.clip("短", 10));
        assertEquals("", ScriptPrompts.clip(null, 10));
    }
}
