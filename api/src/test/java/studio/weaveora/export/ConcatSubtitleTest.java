package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8 逐段字幕定时单测：确认 ASS 时间戳格式与「每条语音一个时间段」。
 *
 * <p>原来整镜只生成一句话幕（0.4s → 镜尾），一镜多段时两句话会同时压在屏幕上。
 */
class ConcatSubtitleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void assTimestampFormat() {
        assertEquals("0:00:00.00", ConcatService.assTs(0));
        assertEquals("0:00:00.40", ConcatService.assTs(0.4));
        assertEquals("0:00:02.50", ConcatService.assTs(2.5));
        assertEquals("0:00:16.00", ConcatService.assTs(16.0));
        assertEquals("0:01:00.00", ConcatService.assTs(60.0));
        assertEquals("1:00:00.00", ConcatService.assTs(3600.0));
        // 四舍五入到 100cs 时不能出现 .100
        assertEquals("0:00:01.00", ConcatService.assTs(0.999));
        // 负数钳到 0
        assertEquals("0:00:00.00", ConcatService.assTs(-5));
    }

    @Test
    void dialogueLineCarriesItsOwnWindow() {
        String s = ConcatService.assDialogue(1280, 720, 40, 2.0, 3.6, "谁敢与我决一死战！");
        assertTrue(s.startsWith("Dialogue: 0,0:00:02.00,0:00:03.60,Sub,"), s);
        assertTrue(s.endsWith("谁敢与我决一死战！\n"), s);
    }

    @Test
    void endMustBeAfterStart() {
        // 结束早于/等于开始时给 0.2s 下限，避免 libass 拒绝零长事件
        String s = ConcatService.assDialogue(1280, 720, 40, 5.0, 5.0, "x");
        assertTrue(s.contains("0:00:05.00,0:00:05.20"), s);
    }

    @Test
    void specialCharsEscaped() {
        String s = ConcatService.assDialogue(1280, 720, 40, 0, 1, "a{b}c\\d");
        // ASS 里 { } 是样式覆盖语法，必须转义；反斜杠也要转义
        assertTrue(s.contains("a\\{b\\}c\\\\d"), s);
    }

    @Test
    void fixtureProducesOneSubtitlePerNarrationLine() throws IOException {
        JsonNode plan = fixture();
        // 关羽样例：6 镜共 11 段语音 → 11 条字幕（原来只有 6 条，且每镜一句拼起来的）
        int total = 0;
        for (JsonNode shot : plan.path("shots")) {
            List<studio.weaveora.director.plan.AudioPlan.Line> lines =
                    studio.weaveora.director.plan.AudioPlan.lines(shot);
            total += lines.size();
            // 每段都能算出合法窗口（起点 < 终点，且不超镜长）
            double shotDur = shot.path("duration_sec").asDouble();
            for (int i = 0; i < lines.size(); i++) {
                var l = lines.get(i);
                Double next = (i + 1 < lines.size()) ? lines.get(i + 1).atSec() : null;
                double w = l.windowSec(shotDur, next);
                assertTrue(w > 0, "第 " + shot.path("shot_no").asInt() + " 镜第 " + i + " 段窗口应 > 0");
                assertTrue(l.atSec() + w <= shotDur + 1e-6,
                        "第 " + shot.path("shot_no").asInt() + " 镜第 " + i + " 段窗口不应超出镜长");
            }
        }
        assertEquals(11, total);
    }

    @Test
    void explicitEndWinsOverNextLine() throws IOException {
        JsonNode plan = fixture();
        // 第 2 镜：吕布台词 at=2.0 end=3.6（显式）→ 窗口 1.6s，而不是到镜尾的 2.0s
        var lines = studio.weaveora.director.plan.AudioPlan.lines(plan.path("shots").get(1));
        var lv = lines.get(1);
        assertTrue(lv.hasEnd());
        assertEquals(3.6, lv.endSec(), 1e-9);
        assertEquals(1.6, lv.windowSec(4.0, null), 1e-9);
    }

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
}
