package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.weaveora.director.plan.AudioPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8 配乐混音滤镜串单测（纯函数，不跑 ffmpeg）。
 *
 * <p>用「关羽战吕布」样例：开场 -16.5dB（一半）+ 淡入2s，追赶段 -8dB + 淡出2s，16s 处衔接。
 */
class ConcatMusicFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture() throws IOException {
        Path[] candidates = {
                Path.of("..", "packages", "fixtures", "guan-yu-vs-lvbu.plan.json"),
                Path.of("packages", "fixtures", "guan-yu-vs-lvbu.plan.json"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return mapper.readTree(p.toFile());
            }
        }
        throw new IllegalStateException("找不到 fixture");
    }

    @Test
    void openingCueGetsHalfGainAndFadeIn() throws IOException {
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(fixture());
        String f = ConcatService.bgmCueFilter(1, cues.get(0));
        assertEquals("[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                + "atrim=0:16.000,asetpts=PTS-STARTPTS,"
                + "volume=0.150,"                      // -16.5dB ≈ 0.15 = 默认 0.30 的一半
                + "afade=t=in:st=0:d=2.000,"
                + "afade=t=out:st=15.000:d=1.000,"
                + "adelay=0|0,apad[m1]", f);
    }

    @Test
    void chaseCueIsLouderAndFadesOutToTheEnd() throws IOException {
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(fixture());
        String f = ConcatService.bgmCueFilter(2, cues.get(1));
        assertEquals("[2:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                + "atrim=0:8.000,asetpts=PTS-STARTPTS,"
                + "volume=0.398,"                      // -8dB ≈ 0.398
                + "afade=t=in:st=0:d=0.500,"
                + "afade=t=out:st=6.000:d=2.000,"
                + "adelay=16000|16000,apad[m2]", f);
    }

    @Test
    void chaseIsAtLeastTwiceAsLoudAsOpening() throws IOException {
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(fixture());
        double open = Math.pow(10.0, cues.get(0).gainDb() / 20.0);
        double chase = Math.pow(10.0, cues.get(1).gainDb() / 20.0);
        assertTrue(chase > open * 2, "追赶段应明显比开场响（实际 " + chase + " vs " + open + "）");
    }

    @Test
    void legacyPlanProducesSingleWholeVideoCue() throws IOException {
        // 老方案：只有 music_mood，没有 music[]
        var plan = mapper.createObjectNode();
        plan.put("mode", "video");
        plan.put("duration_sec", 12);
        plan.putObject("audio").put("music_mood", "温暖治愈");
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
        assertEquals(1, cues.size());
        String f = ConcatService.bgmCueFilter(1, cues.get(0));
        // 默认 -10.5dB ≈ 0.299（与 P7 硬编码 volume=0.30 等价），无淡入淡出、从 0 起
        assertTrue(f.contains("atrim=0:12.000"), f);
        assertTrue(f.contains("adelay=0|0"), f);
        assertTrue(f.contains("volume=0.299"), f);
    }
}
