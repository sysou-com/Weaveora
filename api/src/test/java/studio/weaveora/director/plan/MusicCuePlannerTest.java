package studio.weaveora.director.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1 逐镜配乐策略单测。 */
class MusicCuePlannerTest {

    private static MusicCuePlanner.ShotMusic s(int no, double start, double end, String policy) {
        return new MusicCuePlanner.ShotMusic(no, start, end, policy);
    }

    @Test
    void allBedBecomesSingleCue() {
        var cues = MusicCuePlanner.plan(
                List.of(s(1, 0, 2, "bed"), s(2, 2, 4, "bed"), s(3, 4, 6, "bed")), 6);
        assertEquals(1, cues.size());
        assertEquals(0, cues.get(0).startSec(), 0.001);
        assertEquals(6, cues.get(0).endSec(), 0.001);
    }

    @Test
    void noneShotSplitsCuesAndLeavesGap() {
        var cues = MusicCuePlanner.plan(
                List.of(s(1, 0, 2, "bed"), s(2, 2, 4, "none"), s(3, 4, 6, "bed")), 6);
        assertEquals(2, cues.size());
        assertEquals(0, cues.get(0).startSec(), 0.001);
        assertEquals(2, cues.get(0).endSec(), 0.001);
        assertEquals(4, cues.get(1).startSec(), 0.001);
        assertEquals(6, cues.get(1).endSec(), 0.001);
    }

    @Test
    void hitKeepsSameMoodButLouderGain() {
        var cues = MusicCuePlanner.plan(
                List.of(s(1, 0, 3, "bed"), s(2, 3, 5, "hit")), 5);
        assertEquals(2, cues.size());
        assertEquals(MusicCuePlanner.BED_GAIN_DB, cues.get(0).gainDb(), 0.001);
        assertEquals(MusicCuePlanner.HIT_GAIN_DB, cues.get(1).gainDb(), 0.001);
        assertTrue(cues.get(1).gainDb() > cues.get(0).gainDb());
    }

    @Test
    void missingPolicyDefaultsToBed() {
        var cues = MusicCuePlanner.plan(List.of(s(1, 0, 3, "")), 3);
        assertEquals(1, cues.size());
        assertEquals(MusicCuePlanner.BED, cues.get(0).policy());
    }

    @Test
    void allNoneYieldsNoCues() {
        var cues = MusicCuePlanner.plan(
                List.of(s(1, 0, 3, "none"), s(2, 3, 6, "无")), 6);
        assertTrue(cues.isEmpty(), "全片无配乐时不应有 cue");
    }

    @Test
    void normalizeRecognizesAliases() {
        assertEquals(MusicCuePlanner.NONE, MusicCuePlanner.normalize("静音"));
        assertEquals(MusicCuePlanner.HIT, MusicCuePlanner.normalize("高潮"));
        assertEquals(MusicCuePlanner.BED, MusicCuePlanner.normalize(null));
        assertEquals(MusicCuePlanner.BED, MusicCuePlanner.normalize("Bed"));
    }

    @Test
    void totalTruncatesCues() {
        var cues = MusicCuePlanner.plan(List.of(s(1, 0, 4, "bed"), s(2, 4, 8, "bed")), 6);
        assertEquals(1, cues.size());
        assertEquals(6, cues.get(0).endSec(), 0.001);
    }
}
