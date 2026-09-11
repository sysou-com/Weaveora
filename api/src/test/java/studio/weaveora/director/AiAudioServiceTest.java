package studio.weaveora.director;

import org.junit.jupiter.api.Test;
import studio.weaveora.director.plan.AudioPlan;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11 AI 音频助手的纯逻辑单测：台词铺排（确定性时长/语速）与配乐归一化。
 *
 * <p>关键口径：AI **只负责“谁说什么”**，时间由后端算；否则字数和镜头长度对不上、
 * 字幕与配音必然失步。
 */
class AiAudioServiceTest {

    private AiAudioService.AiLine line(String kind, String subject, String text) {
        return new AiAudioService.AiLine(text, kind, subject);
    }

    // ------------------------------------------------------------ 台词铺排

    @Test
    void laysOutDialogueSequentiallyWithinShot() {
        // 镜头 10s；两段对白共约 6.4s → 不需要提速
        var ai = List.of(line("dialogue", "关羽", "来者何人，报上名来！"),
                line("dialogue", "吕布", "吾乃吕布，字奉先！"));
        var fitted = AiAudioService.fitLines(ai, 10, Set.of("关羽", "吕布"));

        assertEquals(2, fitted.size());
        assertEquals(0.0, fitted.get(0).atSec(), 1e-9);
        assertEquals(1.0, fitted.get(0).speed(), 1e-9, "装得下就不提速");
        // 第二段在第一段之后（含间隙）
        assertTrue(fitted.get(1).atSec() > fitted.get(0).endSec(), "段间应留间隙");
        assertTrue(fitted.get(1).endSec() <= 10, "末段不应超出镜头");
    }

    @Test
    void overlongDialogueIsNotSpedUpAndOverflowsInstead() {
        // P12 口径：镜头 4s、台词约 6.2s —— 不再提速，保持自然语速 1.0×，允许溢出到下一镜
        var ai = List.of(line("dialogue", "关羽", "来者何人，报上名来，快快通名！"),
                line("dialogue", "吕布", "吾乃吕布，谁敢与我一战！"));
        var fit = AiAudioService.fitLinesDetailed(ai, 4, Set.of("关羽", "吕布"));
        var fitted = fit.lines();
        assertEquals(2, fitted.size());
        assertEquals(1.0, fitted.get(0).speed(), 1e-9, "默认不提速");
        assertEquals(1.0, fitted.get(1).speed(), 1e-9, "全镜自然语速");
        assertTrue(fitted.get(1).endSec() > 4, "装不下就溢出，实际 " + fitted.get(1).endSec() + "s");
        assertTrue(fit.notes().stream().anyMatch(n -> n.contains("溢出")), "要提示已溢出：" + fit.notes());
    }

    @Test
    void aiNarrationIsDroppedByDefault() {
        // P12：AI 台词只写对白 —— 它多写的旁白一律丢掉（旁白由用户手动新增）
        var ai = List.of(line("dialogue", "关羽", "看刀！"),
                line("narration", "", "刀光一闪，尘土飞扬，杀气瞬间笼罩了整个战场。"));
        var fit = AiAudioService.fitLinesDetailed(ai, 10, Set.of("关羽"));
        assertEquals(1, fit.lines().size(), "旁白要被丢掉：" + fit.lines());
        assertEquals("dialogue", fit.lines().get(0).kind());
        assertTrue(fit.notes().stream().anyMatch(n -> n.contains("旁白") && n.contains("丢")),
                "要说明丢了旁白：" + fit.notes());
    }

    @Test
    void narrationOnlyResultIsEmptyWithNote() {
        // 就算 AI 只给了旁白（例如它不听指令），也不能往镜头里塞旁白
        var ai = List.of(line("narration", "", "刀光一闪，尘土飞扬，杀气瞬间笼罩了整个战场。"));
        var fit = AiAudioService.fitLinesDetailed(ai, 3, Set.of());
        assertTrue(fit.lines().isEmpty(), "纯旁白结果应为空：" + fit.lines());
        assertTrue(fit.notes().stream().anyMatch(n -> n.contains("旁白")), "要告知为何空：" + fit.notes());
    }

    @Test
    void unboundSpeakerIsDroppedWithNote() {
        var ai = List.of(line("dialogue", "张飞", "大哥！"), line("dialogue", "关羽", "看刀！"));
        var fit = AiAudioService.fitLinesDetailed(ai, 5, Set.of("关羽"));
        assertEquals(1, fit.lines().size(), "未绑定说话人的段落要丢掉：" + fit.lines());
        assertEquals("关羽", fit.lines().get(0).subject());
        assertTrue(fit.notes().stream().anyMatch(n -> n.contains("未绑定")),
                "要说明丢了未绑定段落：" + fit.notes());
    }

    @Test
    void charBudgetLeavesHeadroom() {
        assertEquals(0, AiAudioService.charBudget(0));
        assertEquals(20, AiAudioService.charBudget(5), "5s × 4.5 字/秒 × 0.9 = 20 字");
        assertTrue(AiAudioService.charBudget(4) < 4 * AiAudioService.CHARS_PER_SEC, "预算必须小于理论上限");
    }

    @Test
    void speedIsOneWhenItFits() {
        var ai = List.of(line("dialogue", "关羽", "风起。"));
        assertEquals(1.0, AiAudioService.fitLines(ai, 8, Set.of("关羽")).get(0).speed(), 1e-9);
    }

    @Test
    void boundSpeakerKeepsDialogueAndSubject() {
        var fitted = AiAudioService.fitLines(List.of(line("dialogue", "关羽", "看刀！")), 5, Set.of("关羽"));
        assertEquals("dialogue", fitted.get(0).kind());
        assertEquals("关羽", fitted.get(0).subject());
    }

    @Test
    void subjectImpliesDialogueWhenKindMissing() {
        var fitted = AiAudioService.fitLines(List.of(line("", "吕布", "且退！")), 5, Set.of("吕布"));
        assertEquals("dialogue", fitted.get(0).kind());
    }

    @Test
    void emptyBoundSetDropsEverything() {
        // 没有绑定角色 = AI 没任何人可说 → 全部丢掉（上层会提前短路并提示去绑角色）
        var fit = AiAudioService.fitLinesDetailed(List.of(line("dialogue", "关羽", "看刀！")), 5, Set.of());
        assertTrue(fit.lines().isEmpty(), "无绑定角色时不该产出：" + fit.lines());
        assertTrue(fit.notes().stream().anyMatch(n -> n.contains("未绑定")), "要告知原因：" + fit.notes());
    }

    @Test
    void everyLineHasPositiveWindowAndEndAfterStart() {
        var ai = List.of(line("dialogue", "关羽", "一"), line("dialogue", "吕布", "二"),
                line("dialogue", "关羽", "三"));
        for (var f : AiAudioService.fitLines(ai, 3, Set.of("关羽", "吕布"))) {
            assertTrue(f.endSec() > f.atSec(), "每段时长必须为正");
            assertTrue(f.endSec() - f.atSec() >= 0.8 - 1e-9, "单段不小于 0.8s");
        }
    }

    @Test
    void estimateUsesChineseReadingSpeed() {
        assertEquals(5 / 4.5, AiAudioService.estimate("一二三四五"), 1e-9, "5 字 ≈ 1.11s");
        assertEquals(10 / 4.5, AiAudioService.estimate("一二三四五六七八九十"), 1e-9, "10 字 ≈ 2.22s");
        assertEquals(2.0, AiAudioService.estimate("一二三四五六七八九"), 1e-9, "空格不计入字数");
        assertEquals(0.8, AiAudioService.estimate("短"), 1e-9, "过短走 0.8s 下限");
    }

    @Test
    void parseLinesToleratesFencesAndJunk() {
        String raw = "```json\n{\"lines\":[{\"kind\":\"dialogue\",\"subject\":\"关羽\",\"text\":\"看刀！\"},"
                + "{\"text\":\"  \"}]}\n```";
        var lines = AiAudioService.parseLines(raw);
        assertEquals(1, lines.size(), "空文本要被丢掉");
        assertEquals("关羽", lines.get(0).subject());
    }

    @Test
    void parseLinesReturnsEmptyOnGarbage() {
        assertTrue(AiAudioService.parseLines("not json").isEmpty());
        assertTrue(AiAudioService.parseLines("").isEmpty());
        assertTrue(AiAudioService.parseLines(null).isEmpty());
    }

    // ------------------------------------------------------------ 配乐归一化

    @Test
    void musicCuesNormalizedToCoverWholeFilm() {
        String raw = "{\"music\":["
                + "{\"start_sec\":16,\"end_sec\":24,\"mood\":\"紧张悬疑\",\"gain_db\":-8},"
                + "{\"start_sec\":0,\"end_sec\":16,\"mood\":\"空灵神秘\",\"gain_db\":-16.5}]}";
        var cues = AiAudioService.parseMusic(raw, 24);
        assertEquals(2, cues.size());
        assertEquals(0.0, cues.get(0).startSec(), 1e-9, "排序后首段从 0 起");
        assertEquals("空灵神秘", cues.get(0).mood());
        assertEquals(-16.5, cues.get(0).gainDb(), 1e-9);
        assertEquals(16.0, cues.get(0).endSec(), 1e-9);
        assertEquals(16.0, cues.get(1).startSec(), 1e-9, "段间不应留空隙");
        assertEquals(24.0, cues.get(1).endSec(), 1e-9, "末段应覆盖到成片结束");
    }

    @Test
    void musicGapsAreFilledAndEndClamped() {
        // 只给了 5~12 一段，且末尾超出总长 → 归一化成 0~12（补前面）并裁到 20
        String raw = "{\"music\":[{\"start_sec\":5,\"end_sec\":30,\"mood\":\"史诗磅礴\",\"gain_db\":-10}]}";
        var cues = AiAudioService.parseMusic(raw, 20);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).startSec(), 1e-9, "首段应补到 0");
        assertEquals(20.0, cues.get(0).endSec(), 1e-9, "应裁到总长");
    }

    @Test
    void invalidMusicSegmentsDropped() {
        String raw = "{\"music\":[{\"start_sec\":5,\"end_sec\":5,\"mood\":\"x\"},"
                + "{\"start_sec\":0,\"end_sec\":8,\"mood\":\"\"},"
                + "{\"start_sec\":0,\"end_sec\":8,\"mood\":\"温暖治愈\"}]}";
        var cues = AiAudioService.parseMusic(raw, 20);
        assertEquals(1, cues.size(), "零长与空情绪应被丢弃");
        assertEquals("温暖治愈", cues.get(0).mood());
    }

    @Test
    void musicDefaultsAreLoopDuckAndGainWhenMissing() {
        String raw = "{\"music\":[{\"start_sec\":0,\"end_sec\":10,\"mood\":\"轻快活泼\"}]}";
        var cues = AiAudioService.parseMusic(raw, 10);
        assertEquals(AudioPlan.DEFAULT_MUSIC_GAIN_DB, cues.get(0).gainDb(), 1e-9);
        assertTrue(cues.get(0).loop());
        assertTrue(cues.get(0).duck());
    }

    @Test
    void musicParseHandlesGarbage() {
        assertTrue(AiAudioService.parseMusic("nope", 10).isEmpty());
        assertTrue(AiAudioService.parseMusic("{\"music\":[]}", 10).isEmpty());
    }
}
