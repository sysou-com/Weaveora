package studio.weaveora.director;

import org.junit.jupiter.api.Test;
import studio.weaveora.director.plan.AudioPlan;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void laysOutLinesSequentiallyWithinShot() {
        // 镜头 10s；两段共约 4.9s（22 字/4.5 ≈ 4.9）→ 不需要提速
        var ai = List.of(line("dialogue", "关羽", "来者何人，报上名来！"),
                line("narration", "", "刀光一闪，尘土飞扬。"));
        var fitted = AiAudioService.fitLines(ai, 10, Set.of("关羽"));

        assertEquals(2, fitted.size());
        assertEquals(0.0, fitted.get(0).atSec(), 1e-9);
        assertEquals(1.0, fitted.get(0).speed(), 1e-9, "装得下就不提速");
        // 第二段在第一段之后（含间隙）
        assertTrue(fitted.get(1).atSec() > fitted.get(0).endSec(), "段间应留间隙");
        assertTrue(fitted.get(1).endSec() <= 10, "末段不应超出镜头");
    }

    @Test
    void raisesSpeedWhenTotalOverflows() {
        // 镜头 4s；两段约 6.2s（含标点）→ 按比例提速（不写死具体值，断言行为）
        var ai = List.of(line("dialogue", "关羽", "来者何人，报上名来，快快通名！"),
                line("dialogue", "吕布", "吾乃吕布，谁敢与我一战！"));
        var fitted = AiAudioService.fitLines(ai, 4, Set.of("关羽", "吕布"));
        double spd = fitted.get(0).speed();
        assertTrue(spd > 1.4 && spd <= 2.0, "装不下应按比例提速，实际 " + spd);
        assertEquals(spd, fitted.get(1).speed(), 1e-9, "全镜统一语速");
        // 提速后总长应能进镜头（留 10% 余量，因为间隙/下限会带来误差）
        double end = fitted.get(fitted.size() - 1).endSec();
        assertTrue(end <= 4.4, "提速后不应明显溢出，实际 " + end + "s");
    }

    @Test
    void speedCappedAtTwo() {
        // 镜头 2s，同样两段 → 需要约 3× → 被 2.0× 封顶，剩余溢出交由界面告警
        var ai = List.of(line("dialogue", "关羽", "来者何人，报上名来，快快通名！"),
                line("dialogue", "吕布", "吾乃吕布，谁敢与我一战！"));
        assertEquals(2.0, AiAudioService.fitLines(ai, 2, Set.of("关羽", "吕布")).get(0).speed(), 1e-9);
    }

    @Test
    void speedIsOneWhenItFits() {
        var ai = List.of(line("narration", "", "风起。"));
        assertEquals(1.0, AiAudioService.fitLines(ai, 8, Set.of()).get(0).speed(), 1e-9);
    }

    @Test
    void speakerNotBoundDegradesToNarration() {
        // AI 编了一个没绑定的角色 → 必须降级为旁白，否则会指向不存在的角色
        var ai = List.of(line("dialogue", "张飞", "大哥！"));
        var fitted = AiAudioService.fitLines(ai, 5, Set.of("关羽", "吕布"));
        assertEquals("narration", fitted.get(0).kind());
        assertNull(fitted.get(0).subject(), "未绑定的说话人要被清空");
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
    void emptyBoundSetMeansAllNarration() {
        var fitted = AiAudioService.fitLines(List.of(line("dialogue", "关羽", "看刀！")), 5, Set.of());
        assertEquals("narration", fitted.get(0).kind());
        assertNull(fitted.get(0).subject());
    }

    @Test
    void everyLineHasPositiveWindowAndEndAfterStart() {
        var ai = List.of(line("dialogue", "关羽", "一"), line("narration", "", "二"), line("dialogue", "吕布", "三"));
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
