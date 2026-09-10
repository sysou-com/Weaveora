package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P8 音频计划解析纯单测（不依赖 DB/容器）。 */
class AudioPlanTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode videoPlan(double durationSec) {
        ObjectNode p = mapper.createObjectNode();
        p.put("mode", "video");
        p.put("title", "关羽战吕布");
        p.put("logline", "虎牢关前，双雄相搏。");
        p.put("duration_sec", durationSec);
        return p;
    }

    private ObjectNode shot(int no, double dur, String narration) {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", no);
        s.put("duration_sec", dur);
        s.put("positive_prompt", "x".repeat(30));
        s.put("negative_prompt", "text");
        s.put("seed_lock", true);
        if (narration != null) {
            s.put("narration", narration);
        }
        return s;
    }

    // ------------------------------------------------------------ 向后兼容

    @Test
    void legacySingleNarrationBecomesOneLineAtZero() {
        ObjectNode plan = videoPlan(10);
        plan.withArray("shots").add(shot(1, 4, "  虎牢关前，尘土蔽日。  "));
        JsonNode shot = plan.path("shots").get(0);

        List<AudioPlan.Line> lines = AudioPlan.lines(shot);
        assertEquals(1, lines.size());
        assertEquals(0.0, lines.get(0).atSec());
        assertEquals("虎牢关前，尘土蔽日。", lines.get(0).text());
        assertEquals("narration", lines.get(0).kind());
        assertNull(lines.get(0).subject());
        assertNull(lines.get(0).voice());
    }

    @Test
    void blankNarrationYieldsNoLine() {
        ObjectNode plan = videoPlan(10);
        plan.withArray("shots").add(shot(1, 4, "   "));
        assertTrue(AudioPlan.lines(plan.path("shots").get(0)).isEmpty());
        plan.withArray("shots").add(shot(2, 4, null));
        assertTrue(AudioPlan.lines(plan.path("shots").get(1)).isEmpty());
    }

    @Test
    void legacyMusicMoodSpansWholeVideo() {
        ObjectNode plan = videoPlan(20);
        plan.putObject("audio").put("music_mood", "温暖治愈");
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(20.0, cues.get(0).endSec());
        assertEquals("温暖治愈", cues.get(0).mood());
        assertEquals(AudioPlan.DEFAULT_MUSIC_GAIN_DB, cues.get(0).gainDb());
        assertTrue(cues.get(0).loop());
        assertTrue(cues.get(0).duck());
    }

    @Test
    void noDurationMeansNoLegacyCue() {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("mode", "video");
        plan.putObject("audio").put("music_mood", "x");
        assertTrue(AudioPlan.musicCues(plan).isEmpty());
    }

    // ------------------------------------------------------------ 多段语音

    @Test
    void narrationsOverrideSingleNarrationAndSortByAtSec() {
        ObjectNode plan = videoPlan(12);
        ObjectNode s = shot(1, 6, "旧的单段旁白（应被忽略）");
        var arr = s.putArray("narrations");
        arr.addObject().put("at_sec", 2.5).put("text", "吕布挺戟而出。").put("subject", "吕布");
        arr.addObject().put("at_sec", 0).put("text", "虎牢关前，双雄对峙。");
        plan.withArray("shots").add(s);

        List<AudioPlan.Line> lines = AudioPlan.lines(plan.path("shots").get(0));
        assertEquals(2, lines.size());
        // 已按 at_sec 排序
        assertEquals(0.0, lines.get(0).atSec());
        assertEquals("虎牢关前，双雄对峙。", lines.get(0).text());
        assertEquals("narration", lines.get(0).kind());
        assertEquals(2.5, lines.get(1).atSec());
        assertEquals("吕布", lines.get(1).subject());
        // 有 subject 且未显式给 kind → 判为台词
        assertEquals("dialogue", lines.get(1).kind());
        assertTrue(lines.get(1).dialogue());
    }

    @Test
    void explicitKindWinsAndBlankTextDropped() {
        ObjectNode s = shot(1, 6, null);
        var arr = s.putArray("narrations");
        arr.addObject().put("at_sec", 0).put("text", "   ");
        arr.addObject().put("at_sec", 1).put("text", "哪怕千万人，吾往矣。")
                .put("subject", "关羽").put("kind", "narration");
        List<AudioPlan.Line> lines = AudioPlan.lines(s);
        assertEquals(1, lines.size());
        assertEquals("narration", lines.get(0).kind());   // 显式 kind 覆盖 subject 推断
        assertEquals("关羽", lines.get(0).subject());
    }

    @Test
    void negativeAtSecClampedToZeroAndSpeedOutOfRangeIgnored() {
        ObjectNode s = shot(1, 6, null);
        var arr = s.putArray("narrations");
        arr.addObject().put("at_sec", -3).put("text", "a").put("speed", 9);
        List<AudioPlan.Line> lines = AudioPlan.lines(s);
        assertEquals(0.0, lines.get(0).atSec());
        assertEquals(0.0, lines.get(0).speed());   // 越界 → 0 = 用默认
    }

    @Test
    void totalLinesCountsAcrossShots() {
        ObjectNode plan = videoPlan(20);
        plan.withArray("shots").add(shot(1, 5, "一"));
        ObjectNode s2 = shot(2, 5, null);
        var arr = s2.putArray("narrations");
        arr.addObject().put("text", "二").put("at_sec", 0);
        arr.addObject().put("text", "三").put("at_sec", 2).put("subject", "关羽");
        plan.withArray("shots").add(s2);
        assertEquals(3, AudioPlan.totalLines(plan));
    }

    // ------------------------------------------------------------ 音色/语速解析

    @Test
    void voiceResolutionPriority() {
        ObjectNode plan = videoPlan(10);
        ObjectNode audio = plan.putObject("audio");
        audio.put("voice", "中文女");
        audio.putArray("voiceBindings")
                .addObject().put("subject", "关羽").put("voice", "中文男").put("speed", 0.9);

        // 1) 段内 voice 最高
        assertEquals("粤语女", AudioPlan.voiceFor(plan, "关羽", "粤语女"));
        // 2) 绑定表次之
        assertEquals("中文男", AudioPlan.voiceFor(plan, "关羽", null));
        // 3) 无绑定的主体 → audio.voice
        assertEquals("中文女", AudioPlan.voiceFor(plan, "路人", null));
        // 4) 无主体 → audio.voice
        assertEquals("中文女", AudioPlan.voiceFor(plan, null, "  "));
    }

    @Test
    void voiceFallsBackToDefaultWhenNothingSet() {
        ObjectNode plan = videoPlan(10);
        assertEquals(AudioPlan.DEFAULT_VOICE, AudioPlan.voiceFor(plan, null, null));
    }

    @Test
    void speedResolutionPriority() {
        ObjectNode plan = videoPlan(10);
        plan.putObject("audio").putArray("voiceBindings")
                .addObject().put("subject", "关羽").put("voice", "中文男").put("speed", 0.9);
        assertEquals(1.4, AudioPlan.speedFor(plan, "关羽", 1.4));   // 段内优先
        assertEquals(0.9, AudioPlan.speedFor(plan, "关羽", 0));     // 绑定表
        assertEquals(1.0, AudioPlan.speedFor(plan, "路人", 0));     // 默认
        assertEquals(1.0, AudioPlan.speedFor(plan, null, 5));       // 越界回默认
    }

    @Test
    void subjectsUnionsBindingsAndReferenceAssets() {
        ObjectNode plan = videoPlan(10);
        plan.putObject("audio").putArray("voiceBindings")
                .addObject().put("subject", "关羽").put("voice", "中文男");
        var refs = plan.putArray("referenceAssets");
        refs.addObject().put("assetId", "a1").put("subject", "吕布");
        refs.addObject().put("assetId", "a2").put("subject", "关羽");   // 与绑定表重复 → 去重
        assertEquals(List.of("关羽", "吕布"), List.copyOf(AudioPlan.subjects(plan)));
    }

    // ------------------------------------------------------------ 配乐段落

    @Test
    void musicCuesSortedFilteredAndClamped() {
        ObjectNode plan = videoPlan(20);
        ObjectNode audio = plan.putObject("audio");
        audio.put("music_mood", "温暖治愈");
        var arr = audio.putArray("music");
        arr.addObject().put("start_sec", 10).put("end_sec", 30)      // 超出 → 截断到 20
                .put("mood", "紧张悬疑").put("gain_db", -8).put("fade_out_sec", 1.5).put("id", "chase");
        arr.addObject().put("start_sec", 0).put("end_sec", 10)
                .put("gain_db", -16.5).put("fade_in_sec", 2).put("duck", false).put("id", "open");
        arr.addObject().put("start_sec", 5).put("end_sec", 5);        // 非法 → 跳过
        arr.addObject().put("start_sec", 25).put("end_sec", 30);      // 起点越界 → 跳过

        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
        assertEquals(2, cues.size());
        assertEquals("open", cues.get(0).id());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(10.0, cues.get(0).endSec());
        assertEquals(-16.5, cues.get(0).gainDb());
        assertEquals(2.0, cues.get(0).fadeInSec());
        assertFalse(cues.get(0).duck());
        assertEquals("chase", cues.get(1).id());
        assertEquals(20.0, cues.get(1).endSec());          // 已截断到成片时长
        assertEquals(-8.0, cues.get(1).gainDb());
        assertEquals("紧张悬疑", cues.get(1).mood());
    }

    @Test
    void cueMoodFallsBackToGlobalMusicMood() {
        ObjectNode plan = videoPlan(20);
        ObjectNode audio = plan.putObject("audio");
        audio.put("music_mood", "空灵神秘");
        audio.putArray("music").addObject().put("start_sec", 0).put("end_sec", 5);
        assertEquals("空灵神秘", AudioPlan.musicCues(plan).get(0).mood());
    }

    @Test
    void distinctMoodsKeepFirstSeenOrderAndLongestDuration() {
        ObjectNode plan = videoPlan(30);
        ObjectNode audio = plan.putObject("audio");
        var arr = audio.putArray("music");
        arr.addObject().put("start_sec", 0).put("end_sec", 8).put("mood", "空灵神秘");
        arr.addObject().put("start_sec", 8).put("end_sec", 22).put("mood", "紧张悬疑");
        arr.addObject().put("start_sec", 22).put("end_sec", 26).put("mood", "空灵神秘");

        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
        assertEquals(List.of("空灵神秘", "紧张悬疑"), AudioPlan.distinctMoods(cues));
        // 空灵神秘出现两段(8s / 4s) → 取最长 8s；紧张悬疑 14s
        assertEquals(8.0, AudioPlan.generateDurationFor(cues, "空灵神秘"));
        assertEquals(14.0, AudioPlan.generateDurationFor(cues, "紧张悬疑"));
        assertEquals(0.0, AudioPlan.generateDurationFor(cues, "不存在的情绪"));
    }
}
