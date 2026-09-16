package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运镜关键帧的**帧级剧情主体**（2026-09-16 夜）。
 *
 * <p>用户报的原问题：「第三镜有 2 帧，在运镜关键帧里面根本没有剧情主体，所以关键帧都是随意生成的」。
 * 根因是只有镜级 cast（`shots[].cast`）→ 第 2..N 帧换了视角/换了主体也只能“继承 + 猜”。
 *
 * <p>语义（与 `shots[].cast` 一致）：`undefined` = 继承镜级；`[]` = 该帧空镜；非空 = 该帧就这几个主体。
 */
class JobKeyframeCastTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 造一个带 N 帧的镜；frames = 每帧的 cast（null 表示该帧不写 cast 字段） */
    private ObjectNode shot(String[] shotCast, String[][] frames) {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", 3);
        if (shotCast != null) {
            ArrayNode c = s.putArray("cast");
            for (String n : shotCast) c.add(n);
        }
        ArrayNode kfs = s.putArray("keyframes");
        for (String[] f : frames) {
            ObjectNode kf = kfs.addObject();
            kf.put("label", "帧");
            kf.put("positive_prompt", "prompt");
            if (f != null) {
                ArrayNode c = kf.putArray("cast");
                for (String n : f) c.add(n);
            }
        }
        return s;
    }

    @Test
    void frameCastWinsOverShotCast() {
        ObjectNode s = shot(new String[]{"宝玉", "袭人"}, new String[][]{{"宝玉"}, {"袭人"}});
        assertEquals(List.of("宝玉"), JobService.castOf(s, 0), "第 1 帧用帧级 cast");
        assertEquals(List.of("袭人"), JobService.castOf(s, 1), "第 2 帧必须能换成别的主体（原问题）");
    }

    @Test
    void missingFrameCastInheritsShotCast() {
        ObjectNode s = shot(new String[]{"宝玉", "可卿"}, new String[][]{null, {"可卿"}});
        assertEquals(List.of("宝玉", "可卿"), JobService.castOf(s, 0), "帧级没写 → 继承镜级（不破坏老方案）");
        assertEquals(List.of("可卿"), JobService.castOf(s, 1));
    }

    @Test
    void emptyFrameCastMeansEmptyFrame() {
        ObjectNode s = shot(new String[]{"宝玉"}, new String[][]{{}, null});
        List<String> empty = JobService.castOf(s, 0);
        assertTrue(empty != null && empty.isEmpty(), "帧级 [] = 该帧是空镜（不注入人物参考图），不能退化成继承");
        assertEquals(List.of("宝玉"), JobService.castOf(s, 1));
    }

    @Test
    void noKeyframesOrOutOfRangeFallsBackToShot() {
        ObjectNode s = shot(new String[]{"宝玉"}, new String[][]{{"可卿"}});
        assertEquals(List.of("宝玉"), JobService.castOf(s, 5), "帧号越界 → 回退镜级");
        assertEquals(List.of("宝玉"), JobService.castOf(s, -1), "非逐帧调用 → 镜级");
        ObjectNode noKf = mapper.createObjectNode();
        noKf.put("shot_no", 1);
        noKf.putArray("cast").add("可卿");
        assertEquals(List.of("可卿"), JobService.castOf(noKf, 0), "没有 keyframes → 镜级");
    }

    @Test
    void unsetCastMeansAuto() {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", 1);
        assertNull(JobService.castOf(s, 0), "都没写 = null（按文本自动匹配）");
    }

    @Test
    void keyframeOfReturnsTheRightFrame() {
        ObjectNode s = shot(null, new String[][]{{"宝玉"}, {"袭人"}});
        JsonNode kf1 = JobService.keyframeOf(s, 1);
        assertEquals("袭人", kf1.path("cast").get(0).asText());
        assertNull(JobService.keyframeOf(s, 9), "越界返回 null");
        assertNull(JobService.keyframeOf(s, -1));
    }
}
