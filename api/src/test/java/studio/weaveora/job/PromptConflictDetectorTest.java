package studio.weaveora.job;

import org.junit.jupiter.api.Test;
import studio.weaveora.job.PromptConflictDetector.Slot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剧情优先（2026-09-21 产品决策）下的三类冲突提示：**只提示、不纠偏**。
 * 全部是字面规则，所以这些用例同时也是"规则说明书"。
 */
class PromptConflictDetectorTest {

    /** 占满画高（同平面等身）的槽位。 */
    private static Slot full(String name, double x, double w) {
        return new Slot(name, new double[]{x, 0.0, w, 1.0});
    }

    @Test
    void missingNamedSubjectIsReported() {
        List<String> w = PromptConflictDetector.detect("宝玉与可卿并肩而立",
                List.of(full("宝玉", 0.0, 0.34), full("可卿", 0.34, 0.33), full("警幻", 0.67, 0.33)));
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("警幻"), w.toString());
    }

    @Test
    void depthWordAgainstFullHeightIsReported() {
        List<String> w = PromptConflictDetector.detect("警幻身着罗衣、华美发簪，居后景自后方追来，挥袖急阻二人",
                List.of(full("警幻", 0.67, 0.33)));
        assertTrue(w.stream().anyMatch(s -> s.contains("居后景")), w.toString());
    }

    @Test
    void depthWordWithNonFullHeightBoxIsNotReported() {
        // 框本身就不是「占满画高」（y=0.3/h=0.4）→ 纵深描写与框并不矛盾，不该提示
        Slot s = new Slot("警幻", new double[]{0.67, 0.3, 0.33, 0.4});
        List<String> w = PromptConflictDetector.detect("警幻居后景自后方追来", List.of(s));
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void reversedLeftRightIsReported() {
        // 剧情说「可卿在宝玉左侧」，而框是 宝玉 左 / 可卿 右 → 相反
        List<String> w = PromptConflictDetector.detect("可卿在宝玉左侧",
                List.of(full("宝玉", 0.0, 0.34), full("可卿", 0.34, 0.33)));
        assertTrue(w.stream().anyMatch(s -> s.contains("左侧")), w.toString());
    }

    @Test
    void consistentLayoutProducesNoWarning() {
        List<String> w = PromptConflictDetector.detect("可卿在宝玉右侧；宝玉与可卿并肩而立",
                List.of(full("宝玉", 0.0, 0.34), full("可卿", 0.34, 0.33)));
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void subjectWithoutBoxStillCheckedForMissingName() {
        // 没设框的主体也必须参与「剧情里有没有点名」的判断（box=null）
        List<String> w = PromptConflictDetector.detect("宝玉与可卿并肩而立",
                List.of(full("宝玉", 0.0, 0.34), new Slot("警幻", null)));
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("警幻"), w.toString());
    }

    @Test
    void blankOrEmptyInputIsSafe() {
        assertTrue(PromptConflictDetector.detect("", List.of(full("宝玉", 0, 0.34))).isEmpty());
        assertTrue(PromptConflictDetector.detect(null, List.of(full("宝玉", 0, 0.34))).isEmpty());
        assertTrue(PromptConflictDetector.detect("宝玉", List.of()).isEmpty());
    }
}
