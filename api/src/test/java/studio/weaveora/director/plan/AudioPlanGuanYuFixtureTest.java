package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用「虎牢关·关羽战吕布」样例（packages/fixtures/guan-yu-vs-lvbu.plan.json）做端到端解析验收：
 * 旁白 + 双人物对话 + 多段配乐（开场弱、追赶段转急促紧张）。
 */
class AudioPlanGuanYuFixtureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture() throws IOException {
        Path[] candidates = {
                Path.of("..", "packages", "fixtures", "guan-yu-vs-lvbu.plan.json"),
                Path.of("packages", "fixtures", "guan-yu-vs-lvbu.plan.json"),
                Path.of("..", "..", "packages", "fixtures", "guan-yu-vs-lvbu.plan.json"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return mapper.readTree(p.toFile());
            }
        }
        throw new IllegalStateException("找不到 fixture：packages/fixtures/guan-yu-vs-lvbu.plan.json");
    }

    @Test
    void fixturePassesRuntimeValidator() throws IOException {
        JsonNode plan = fixture();
        // 第三个参数 = 用户请求的目标时长（视频方案会拿它和 plan.duration_sec 对比）
        List<String> problems = DirectorPlanValidator.validate(plan, "video", new java.math.BigDecimal("24"));
        assertEquals(List.of(), problems, "样例应通过 §10.3 运行时校验");
    }

    @Test
    void fixtureParsesIntoExpectedVoiceLines() throws IOException {
        JsonNode plan = fixture();

        // 6 镜 24s，共 11 段语音（6 段旁白 + 关羽 3 + 吕布 2）
        assertEquals(24.0, plan.path("duration_sec").asDouble());
        assertEquals(6, plan.path("shots").size());
        assertEquals(11, AudioPlan.totalLines(plan));

        List<String> subjects = new ArrayList<>();
        Map<String, String> voiceOfSubject = new LinkedHashMap<>();
        for (JsonNode shot : plan.path("shots")) {
            for (AudioPlan.Line line : AudioPlan.lines(shot)) {
                String voice = AudioPlan.voiceFor(plan, line.subject(), line.voice());
                if (line.subject() == null) {
                    subjects.add("旁白");
                    assertEquals("中文女", voice, "旁白应走 audio.voice");
                    assertEquals("narration", line.kind());
                } else {
                    subjects.add(line.subject());
                    voiceOfSubject.put(line.subject(), voice);
                    assertEquals("dialogue", line.kind());
                }
            }
        }
        assertEquals(List.of("旁白", "旁白", "吕布", "旁白", "关羽", "旁白", "关羽", "旁白", "吕布", "旁白", "关羽"),
                subjects);
        // 角色→音色绑定生效
        assertEquals("中文男", voiceOfSubject.get("关羽"));
        assertEquals("日语男", voiceOfSubject.get("吕布"));
        assertEquals(List.of("关羽", "吕布"), List.copyOf(AudioPlan.subjects(plan)));
    }

    @Test
    void perLineSpeedOverridesBinding() throws IOException {
        JsonNode plan = fixture();
        // 第 2 镜：吕布台词显式 1.05；第 5 镜：吕布 1.15；关羽统一用绑定的 1.0
        assertEquals(1.05, AudioPlan.speedFor(plan, "吕布", AudioPlan.lines(plan.path("shots").get(1)).get(1).speed()));
        assertEquals(1.15, AudioPlan.speedFor(plan, "吕布", AudioPlan.lines(plan.path("shots").get(4)).get(1).speed()));
        assertEquals(1.0, AudioPlan.speedFor(plan, "关羽", AudioPlan.lines(plan.path("shots").get(2)).get(1).speed()));
    }

    @Test
    void narrationTimesStayInsideTheirShot() throws IOException {
        JsonNode plan = fixture();
        for (JsonNode shot : plan.path("shots")) {
            double dur = shot.path("duration_sec").asDouble();
            double last = -1;
            for (AudioPlan.Line line : AudioPlan.lines(shot)) {
                assertTrue(line.atSec() >= 0 && line.atSec() < dur,
                        "第 " + shot.path("shot_no").asInt() + " 镜的语音起点应在 [0," + dur + ") 内");
                assertTrue(line.atSec() >= last, "同一镜内应按 at_sec 升序");
                last = line.atSec();
            }
        }
    }

    @Test
    void musicHasOpeningHalfVolumeThenUrgentChase() throws IOException {
        JsonNode plan = fixture();
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);

        assertEquals(2, cues.size());
        AudioPlan.MusicCue open = cues.get(0);
        AudioPlan.MusicCue chase = cues.get(1);

        assertEquals("open", open.id());
        assertEquals(0.0, open.startSec());
        assertEquals(16.0, open.endSec());
        assertEquals("史诗磅礴", open.mood());
        // 开场“一半”：-16.5dB ≈ 线性 0.15，正好是默认 0.30 的一半
        assertEquals(-16.5, open.gainDb());
        assertEquals(2.0, open.fadeInSec());

        assertEquals("chase", chase.id());
        assertEquals(16.0, chase.startSec());
        assertEquals(24.0, chase.endSec());
        assertEquals("紧张悬疑", chase.mood());
        assertTrue(chase.gainDb() > open.gainDb(), "追赶段应比开场更响");
        assertEquals(2.0, chase.fadeOutSec());

        // 两段无缝衔接且覆盖全片
        assertEquals(open.endSec(), chase.startSec());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(plan.path("duration_sec").asDouble(), cues.get(cues.size() - 1).endSec());

        // 每个情绪只需生成一次曲子
        assertEquals(List.of("史诗磅礴", "紧张悬疑"), AudioPlan.distinctMoods(cues));
        assertEquals(16.0, AudioPlan.generateDurationFor(cues, "史诗磅礴"));
        assertEquals(8.0, AudioPlan.generateDurationFor(cues, "紧张悬疑"));
    }
}
