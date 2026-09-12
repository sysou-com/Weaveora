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

    // ------------------------------------------------------------ 结束点 end_sec

    @Test
    void endSecParsedAndWindowComputed() {
        ObjectNode s = shot(1, 8, null);
        var arr = s.putArray("narrations");
        arr.addObject().put("at_sec", 1).put("end_sec", 3.5).put("text", "一");
        arr.addObject().put("at_sec", 5).put("text", "二");          // 无结束点
        List<AudioPlan.Line> lines = AudioPlan.lines(s);

        assertTrue(lines.get(0).hasEnd());
        assertEquals(3.5, lines.get(0).endSec(), 1e-9);
        // 设了结束点 → 窗口就是 end-at（与下一段无关）
        assertEquals(2.5, lines.get(0).windowSec(8, 5.0), 1e-9);

        assertFalse(lines.get(1).hasEnd());
        // 未设 → 窗口到下一段起点；最后一段 → 到镜尾
        assertEquals(3.0, lines.get(1).windowSec(8, null), 1e-9);
    }

    @Test
    void illegalEndSecIgnored() {
        ObjectNode s = shot(1, 8, null);
        var arr = s.putArray("narrations");
        arr.addObject().put("at_sec", 4).put("end_sec", 2).put("text", "a");   // end < at → 忽略
        arr.addObject().put("at_sec", 6).put("end_sec", 6).put("text", "b");   // end == at → 忽略
        List<AudioPlan.Line> lines = AudioPlan.lines(s);
        assertFalse(lines.get(0).hasEnd());
        assertFalse(lines.get(1).hasEnd());
        assertEquals(2.0, lines.get(0).windowSec(8, 6.0), 1e-9);
    }

    @Test
    void endSecClampedToShotDurationInWindow() {
        ObjectNode s = shot(1, 4, null);
        s.putArray("narrations").addObject().put("at_sec", 2).put("end_sec", 99).put("text", "a");
        AudioPlan.Line l = AudioPlan.lines(s).get(0);
        assertEquals(2.0, l.windowSec(4, null), 1e-9);   // 窗口被镜头时长截断
    }

    @Test
    void legacyNarrationHasNoEnd() {
        ObjectNode plan = videoPlan(10);
        plan.withArray("shots").add(shot(1, 5, "旧旁白"));
        AudioPlan.Line l = AudioPlan.lines(plan.path("shots").get(0)).get(0);
        assertFalse(l.hasEnd());
        assertEquals(5.0, l.windowSec(5, null), 1e-9);
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

    // ------------------------------------------------------------ P9 克隆音色库

    private ObjectNode planWithPresets() {
        ObjectNode plan = videoPlan(10);
        plan.putObject("audio").putArray("voicePresets")
                .addObject().put("id", "guanyu").put("name", "关羽")
                .put("assetId", "asset-1").put("promptText", "希望你以后能够做的比我还好唷。")
                .put("durationSec", 12.4);
        return plan;
    }

    @Test
    void voicePresetsParsed() {
        List<AudioPlan.VoicePreset> ps = AudioPlan.voicePresets(planWithPresets());
        assertEquals(1, ps.size());
        assertEquals("guanyu", ps.get(0).id());
        assertEquals("关羽", ps.get(0).name());
        assertEquals("asset-1", ps.get(0).assetId());
        assertEquals(12.4, ps.get(0).durationSec(), 1e-9);
        assertTrue(ps.get(0).promptText().contains("希望你以后"));
    }

    @Test
    void missingAssetIdOrIdIsSkipped() {
        ObjectNode plan = videoPlan(10);
        var arr = plan.putObject("audio").putArray("voicePresets");
        arr.addObject().put("id", "a");            // 缺 assetId → 跳
        arr.addObject().put("assetId", "x");       // 缺 id → 跳
        assertTrue(AudioPlan.voicePresets(plan).isEmpty());
    }

    @Test
    void presetNameFallsBackToId() {
        ObjectNode plan = videoPlan(10);
        plan.putObject("audio").putArray("voicePresets")
                .addObject().put("id", "guanyu").put("assetId", "a1");
        assertEquals("guanyu", AudioPlan.voicePresets(plan).get(0).name());
    }

    @Test
    void clonePrefixDetection() {
        assertTrue(AudioPlan.isClone("clone:guanyu"));
        assertEquals("guanyu", AudioPlan.cloneId("clone:guanyu"));
        assertFalse(AudioPlan.isClone("中文女"));
        assertFalse(AudioPlan.isClone("/data/audio/ref.wav"));
        assertFalse(AudioPlan.isClone("clone:"), "只有前缀不算克隆，否则会当成空 id");
        assertFalse(AudioPlan.isClone(null));
    }

    @Test
    void presetByIdFindsAndMisses() {
        ObjectNode plan = planWithPresets();
        assertEquals("asset-1", AudioPlan.presetById(plan, "guanyu").assetId());
        assertNull(AudioPlan.presetById(plan, "lubu"), "不存在的 id 必须返回 null，不能静默回落默认音色");
    }

    @Test
    void cloneBindingResolvesThroughVoiceBindings() {
        ObjectNode plan = planWithPresets();
        plan.with("audio").putArray("voiceBindings")
                .addObject().put("subject", "关羽").put("voice", "clone:guanyu");
        assertEquals("clone:guanyu", AudioPlan.voiceFor(plan, "关羽", null));
        assertEquals("guanyu", AudioPlan.cloneId(AudioPlan.voiceFor(plan, "关羽", null)));
    }

    @Test
    void voiceBindingMatchesAlias() throws Exception {
        // 绑定表写别名「贾宝玉」，台词 subject 是「宝玉」→ 必须解析到它绑定的音色。
        // 踩过的坑：早期这里只做字符串相等 → 全部对不上 → 所有角色都用同一个默认音色。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"audio":{"voice":"中文女",
                  "voiceBindings":[{"subject":"贾宝玉","voice":"clone:baoyu"},{"subject":"警幻","voice":"英文女"}],
                  "voicePresets":[{"id":"baoyu","name":"宝玉","assetId":"11111111-1111-1111-1111-111111111111","promptText":"你好","durationSec":3}]},
                 "subjects":[{"name":"宝玉","aliases":["贾宝玉","宝二爷"]},{"name":"警幻","aliases":["警幻仙姑"]}]}
                """);
        assertEquals("clone:baoyu", AudioPlan.voiceFor(plan, "宝玉", null), "别名绑定要生效");
        assertEquals("clone:baoyu", AudioPlan.voiceFor(plan, "宝二爷", null), "别名本身也要生效");
        assertEquals("英文女", AudioPlan.voiceFor(plan, "警幻仙姑", null));
        assertEquals("中文女", AudioPlan.voiceFor(plan, "贾政", null), "不认识的角色回落默认音色");
    }
}
