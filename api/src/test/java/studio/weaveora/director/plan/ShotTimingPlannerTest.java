package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 镜头时长规划器单测（纯函数）。 */
class ShotTimingPlannerTest {

    private static final ShotTimingPlanner.Caps LTX = ShotTimingPlanner.Caps.of(5.04, 24);

    @Test
    void speechDrivesShotLength() {
        // 5.0s 台词 + 0.3 余量 = 5.3s，未超上限
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 6.0, 5.0)),
                5.3, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        assertEquals(1, r.timings().size());
        assertEquals(5.3, r.timings().get(0).toSec() - r.timings().get(0).fromSec(), 0.001);
        assertFalse(r.timings().get(0).stretch());
        assertEquals(1, r.timings().get(0).segments().size());
    }

    @Test
    void silentShotKeepsDirectorDuration() {
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 3.4, 0)),
                3.4, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        assertEquals(3.4, r.totalSec(), 0.001);
        assertEquals(3.4, r.timings().get(0).segments().get(0).durationSec(), 0.001);
    }

    @Test
    void overCapStretchByDefault() {
        // 8s 台词 → 8.3s need，超 LTX 5.04s → stretch：1 段（5.04s），stretch=true，镜头 8.3s
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 4.0, 8.0)),
                8.3, LTX, ShotTimingPlanner.POLICY_STRETCH);
        var t = r.timings().get(0);
        assertEquals(8.3, t.toSec() - t.fromSec(), 0.001);
        assertTrue(t.stretch());
        assertEquals(1, t.segments().size());
        assertEquals(5.04, t.segments().get(0).durationSec(), 0.001);
    }

    @Test
    void overCapSegmentPolicySplits() {
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 4.0, 8.0)),
                8.3, LTX, ShotTimingPlanner.POLICY_SEGMENT);
        var t = r.timings().get(0);
        assertFalse(t.stretch());
        assertEquals(2, t.segments().size());
        double sum = t.segments().stream().mapToDouble(ShotTimingPlanner.Segment::durationSec).sum();
        assertEquals(8.3, sum, 0.05);
        assertTrue(t.segments().stream().allMatch(s -> s.durationSec() <= LTX.maxShotSec() + 1e-6));
    }

    @Test
    void normalizationGivesSlackToSilentShotsNotSpeech() {
        // 2 镜：镜1 有台词 2s（→2.3s），镜2 无台词 2s；目标 8s → 余量 3.7s 全给镜2
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 2.0, 2.0),
                        new ShotTimingPlanner.ShotInput(2, 2.0, 0)),
                8.0, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        assertEquals(8.0, r.totalSec(), 0.001);
        var s1 = r.timings().get(0);
        var s2 = r.timings().get(1);
        assertEquals(2.3, s1.toSec() - s1.fromSec(), 0.001, "台词镜不得被压缩");
        assertEquals(5.7, s2.toSec() - s2.fromSec(), 0.001, "余量应补给无台词镜");
    }

    @Test
    void speechShotNeverShrinksBelowSpeech() {
        // 目标比台词总长还短 → 只能如实提示，不压台词
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 3.0, 6.0)),
                3.0, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        assertEquals(6.3, r.timings().get(0).toSec() - r.timings().get(0).fromSec(), 0.001);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("无法全部归位")),
                "应如实提示目标可达不到：" + r.notes());
    }

    /**
     * 回归守卫：归一化必须「削最长的」等水位，而不是“从第一镜开始削”。
     *
     * <p>2026-09-29 线上验证：若用“从第一镜削”，3/6/9/12/15（合计 45s，目标 15s）会被削成
     * 1/1/1/1/11（一镜独大、其余碎成 1s）。
     */
    @Test
    void normalizationLevelsLongestFirstInsteadOfStarvingFirstShots() {
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 3, 0),
                        new ShotTimingPlanner.ShotInput(2, 6, 0),
                        new ShotTimingPlanner.ShotInput(3, 9, 0),
                        new ShotTimingPlanner.ShotInput(4, 12, 0),
                        new ShotTimingPlanner.ShotInput(5, 15, 0)),
                15, ShotTimingPlanner.Caps.of(20, 24), ShotTimingPlanner.POLICY_STRETCH);
        assertEquals(15.0, r.totalSec(), 0.2);
        for (var t : r.timings()) {
            double d = t.toSec() - t.fromSec();
            assertTrue(d >= 1.0 - 1e-6 && d <= 8.0, "不应出现独大的长镜或碎成 1s 的短镜：" + d);
        }
    }

    @Test
    void applyToWritesDurationsSegmentsAndEditPlan() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode plan = (ObjectNode) m.readTree("""
                {"mode":"video","duration_sec":8.3,"edit_plan":{"fps":24},
                 "shots":[{"shot_no":1,"duration_sec":4.0},{"shot_no":2,"duration_sec":4.3}]}
                """);
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 4.0, 8.0),
                        new ShotTimingPlanner.ShotInput(2, 4.3, 0)),
                8.3, LTX, ShotTimingPlanner.POLICY_STRETCH);
        ShotTimingPlanner.applyTo(plan, r, LTX, ShotTimingPlanner.POLICY_STRETCH);
        assertEquals("audio_first", plan.path("edit_plan").path("timing_mode").asText());
        assertEquals("stretch", plan.path("edit_plan").path("oversize_policy").asText());
        assertEquals(5.04, plan.path("edit_plan").path("video_model_max_sec").asDouble(), 0.001);
        assertEquals(8.3, plan.path("shots").get(0).path("duration_sec").asDouble(), 0.001);
        assertTrue(plan.path("shots").get(0).path("stretch").asBoolean());
        assertEquals(1, plan.path("shots").get(0).path("segments").size());
        assertEquals(5.04, plan.path("shots").get(0).path("segments").get(0).path("duration_sec").asDouble(), 0.001);
    }

    /**
     * 回归守卫（2026-09-29 线上验证抓到的 bug）：`applyTo` 必须写**本镜时长**，
     * 不能写时间轴上的**绝对终点**（旧实现写成终点 → 各镜时长变累加值 3/6/9/12/15、总长 ×3）。
     */
    @Test
    void applyToWritesPerShotDurationNotCumulativeEnd() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode plan = (ObjectNode) m.readTree("""
                {"mode":"video","duration_sec":12,
                 "shots":[{"shot_no":1,"duration_sec":4},{"shot_no":2,"duration_sec":4},
                          {"shot_no":3,"duration_sec":4}]}
                """);
        var r = ShotTimingPlanner.plan(
                List.of(new ShotTimingPlanner.ShotInput(1, 4, 0),
                        new ShotTimingPlanner.ShotInput(2, 4, 0),
                        new ShotTimingPlanner.ShotInput(3, 4, 0)),
                12, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        ShotTimingPlanner.applyTo(plan, r, ShotTimingPlanner.Caps.of(10, 24), ShotTimingPlanner.POLICY_STRETCH);
        for (int i = 0; i < 3; i++) {
            assertEquals(4.0, plan.path("shots").get(i).path("duration_sec").asDouble(), 0.001,
                    "第 " + (i + 1) + " 镜时长必须是 4.0（不是累加的终值）");
        }
        double total = 0;
        for (int i = 0; i < 3; i++) {
            total += plan.path("shots").get(i).path("duration_sec").asDouble();
        }
        assertEquals(12.0, total, 0.001, "总长必须等于目标 12s");
    }
}
