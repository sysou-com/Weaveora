package studio.weaveora.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P10 字幕排布单测：字幕区间跟**配音实际时长**走，并能**跨镜续显**。
 *
 * <p>旧实现只看 plan 里的占位时间（end_sec / 下一段起点 / 镜尾），与配音实际长度无关，
 * 导致「配音比镜头长」时字幕被硬切在镜尾、与声音对不上。
 */
class ConcatSubtitleSpanTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode shot(double dur, ObjectNode... lines) {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", 1);
        s.put("duration_sec", dur);
        var arr = s.putArray("narrations");
        for (ObjectNode l : lines) {
            arr.add(l);
        }
        return s;
    }

    private ObjectNode line(double at, String text) {
        ObjectNode l = mapper.createObjectNode();
        l.put("at_sec", at);
        l.put("text", text);
        return l;
    }

    @Test
    void usesActualAudioDurationWhenNoManualEnd() {
        // 镜头 4s，台词从 1s 起，配音实际 2.4s → 字幕 1.0 → 3.4（旧实现会切到 4.0 镜尾）
        var s = shot(4, line(1.0, "一句话"));
        Map<Integer, Double> actual = new LinkedHashMap<>();
        actual.put(0, 2.4);

        List<ConcatService.LineSpan> spans = ConcatService.lineSpans(s, 4, 0, actual);
        assertEquals(1, spans.size());
        assertEquals(1.0, spans.get(0).startSec(), 1e-9);
        assertEquals(3.4, spans.get(0).endSec(), 1e-9);
    }

    @Test
    void manualEndWinsOverActualDuration() {
        var l = line(1.0, "一句话");
        l.put("end_sec", 2.5);                       // 手动放到 2.5
        var s = shot(4, l);
        Map<Integer, Double> actual = new LinkedHashMap<>();
        actual.put(0, 3.5);                          // 实际 3.5s，但手动优先

        List<ConcatService.LineSpan> spans = ConcatService.lineSpans(s, 4, 0, actual);
        assertEquals(2.5, spans.get(0).endSec(), 1e-9);
    }

    @Test
    void fallsBackToNextLineThenShotEnd() {
        var s = shot(6, line(0.5, "一"), line(3.0, "二"));
        List<ConcatService.LineSpan> spans = ConcatService.lineSpans(s, 6, 0, null);
        assertEquals(3.0, spans.get(0).endSec(), 1e-9, "无实际时长 → 到下一段起点");
        assertEquals(6.0, spans.get(1).endSec(), 1e-9, "最后一段 → 到镜尾");
    }

    @Test
    void overlengthLineSpillsIntoNextShot() {
        // 镜头 3s，台词从 1.5s 起、配音实际 3.0s → 全局 1.5 → 4.5，超出本镜到 4.5
        var s1 = shot(3, line(1.5, "很长的台词"));
        Map<Integer, Double> actual = new LinkedHashMap<>();
        actual.put(0, 3.0);
        List<ConcatService.LineSpan> spans = ConcatService.lineSpans(s1, 3, 0, actual);

        // 本镜 [0,3)：字幕 1.5 → 3.0（截到镜尾）
        List<ConcatService.SubCue> subs1 = ConcatService.sliceSpans(spans, 0, 3);
        assertEquals(1, subs1.size());
        assertEquals(1.5, subs1.get(0).startSec(), 1e-9);
        assertEquals(3.0, subs1.get(0).endSec(), 1e-9);

        // 下一镜 [3,6)：同一条字幕继续显示 0.0 → 1.5（跨镜续显，这正是本次要做的）
        List<ConcatService.SubCue> subs2 = ConcatService.sliceSpans(spans, 3, 6);
        assertEquals(1, subs2.size(), "超长配音的字幕应续到下一镜");
        assertEquals(0.0, subs2.get(0).startSec(), 1e-9);
        assertEquals(1.5, subs2.get(0).endSec(), 1e-9);
        assertEquals("很长的台词", subs2.get(0).text());
    }

    @Test
    void spanOutsideShotIsDropped() {
        var spans = List.of(new ConcatService.LineSpan(10.0, 12.0, "很后面"));
        assertTrue(ConcatService.sliceSpans(spans, 0, 5).isEmpty());
        // 结束早于镜起点的也丢
        var early = List.of(new ConcatService.LineSpan(0.0, 1.0, "很早"));
        assertTrue(ConcatService.sliceSpans(early, 5, 9).isEmpty());
    }

    @Test
    void tinyOverlapIsDropped() {
        // 只重叠 0.03s（<0.05 阈值）→ 不生成字幕，避免闪一下
        var spans = List.of(new ConcatService.LineSpan(2.0, 5.03, "x"));
        assertTrue(ConcatService.sliceSpans(spans, 5.0, 8.0).isEmpty());
    }

    @Test
    void cursorOffsetsIntoGlobalTimeline() {
        // 第 2 镜（全局起点 4s）里 at=1.0 的台词 → 全局 5.0
        var s = shot(4, line(1.0, "第二镜"));
        List<ConcatService.LineSpan> spans = ConcatService.lineSpans(s, 4, 4, null);
        assertEquals(5.0, spans.get(0).startSec(), 1e-9);
    }

    @Test
    void blankTextSkipped() {
        var s = shot(4, line(0.0, "  "), line(1.0, "有字"));
        assertEquals(1, ConcatService.lineSpans(s, 4, 0, null).size());
    }
}
