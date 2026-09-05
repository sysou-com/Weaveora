package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §10.3 校验器纯单测（§27：不依赖 DB/容器）。 */
class DirectorPlanValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode imagePlan() {
        ObjectNode p = mapper.createObjectNode();
        p.put("mode", "image");
        p.put("title", "Flooded Library");
        p.put("logline", "Moonlight over a drowned baroque library.");
        p.put("positive_prompt", "a flooded baroque library under moonlight, cinematic still, 35mm, no people");
        p.put("negative_prompt", "people, text, watermark");
        return p;
    }

    private ObjectNode videoPlan(int durationSec) {
        ObjectNode p = mapper.createObjectNode();
        p.put("mode", "video");
        p.put("title", "Paper Boat");
        p.put("logline", "A paper boat crosses a rainy city.");
        p.put("duration_sec", durationSec);
        p.put("aspect_ratio", "16:9");
        ObjectNode script = p.putObject("script");
        script.put("theme", "rainy city night");
        script.putArray("acts").addObject()
                .put("name", "setup").put("start_sec", 0).put("end_sec", durationSec).put("purpose", "x");
        p.putObject("audio").put("music_mood", "sparse piano");
        p.putObject("edit_plan").put("fps", 30);
        return p;
    }

    private void addShots(ObjectNode plan, ObjectNode[] shots) {
        for (ObjectNode s : shots) {
            plan.withArray("shots").add(s);
        }
    }

    private ObjectNode shot(int no, int dur, String pos) {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", no);
        s.put("duration_sec", dur);
        s.put("shot_size", "wide");
        s.put("camera_move", "dolly in");
        s.put("action", "paper boat drifts");
        s.put("positive_prompt", pos);
        s.put("negative_prompt", "people, text");
        s.put("seed_lock", true);
        return s;
    }

    @Test
    void imagePlanValid() {
        assertTrue(DirectorPlanValidator.validate(imagePlan(), "image", null).isEmpty());
    }

    @Test
    void imageMissingPositiveRejected() {
        ObjectNode p = imagePlan();
        p.remove("positive_prompt");
        assertFalse(DirectorPlanValidator.validate(p, "image", null).isEmpty());
    }

    @Test
    void modeMismatchRejected() {
        ObjectNode p = imagePlan();
        assertFalse(DirectorPlanValidator.validate(p, "video", new BigDecimal("12")).isEmpty());
    }

    @Test
    void videoDurationSumExactPasses() {
        ObjectNode p = videoPlan(12);
        addShots(p, new ObjectNode[]{
                shot(1, 3, "close up of a paper boat on dark water, cinematic"),
                shot(2, 3, "wide shot of the rainy city canal at dusk, cinematic"),
                shot(3, 3, "the boat passing neon reflections, low angle, cinematic"),
                shot(4, 3, "the boat drifting into darkness, final frame, cinematic"),
        });
        assertTrue(DirectorPlanValidator.validate(p, "video", new BigDecimal("12")).isEmpty());
    }

    @Test
    void videoDurationSumOffByMoreThanHalfRejected() {
        ObjectNode p = videoPlan(12);
        addShots(p, new ObjectNode[]{
                shot(1, 3, "close up of a paper boat on dark water, cinematic"),
                shot(2, 3, "wide shot of the rainy city canal at dusk, cinematic"),
                shot(3, 3, "the boat passing neon reflections, low angle, cinematic"),
                shot(4, 5, "the boat drifting into darkness, final frame, cinematic"), // 14 ≠ 12
        });
        List<String> problems = DirectorPlanValidator.validate(p, "video", new BigDecimal("12"));
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(x -> x.contains("总和") || x.contains("镜头")));
    }

    @Test
    void tooManyShotsRejected() {
        ObjectNode p = videoPlan(6);
        addShots(p, new ObjectNode[]{
                shot(1, 1, "a beat of rain on the window, cinematic texture, shallow"),
                shot(2, 1, "the paper boat set down on the water, cinematic detail"),
                shot(3, 1, "wide street in rain with passing umbrellas, cinematic"),
                shot(4, 1, "medium shot of the boat bobbing, gentle movement, cinematic"),
                shot(5, 1, "low angle under bridge, rain streaks, cinematic frame"),
                shot(6, 1, "the boat floats away, tail frame, long lens, cinematic"), // 6 > ceil(6/1.5)=4
        });
        List<String> problems = DirectorPlanValidator.validate(p, "video", new BigDecimal("6"));
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(x -> x.contains("上限")));
    }

    @Test
    void shotPositiveTooShortRejected() {
        ObjectNode p = videoPlan(12);
        addShots(p, new ObjectNode[]{
                shot(1, 3, "x"), // < 20 字
                shot(2, 3, "wide shot of the rainy city canal at dusk, cinematic"),
                shot(3, 3, "the boat passing neon reflections, low angle, cinematic"),
                shot(4, 3, "the boat drifting into darkness, final frame, cinematic"),
        });
        List<String> problems = DirectorPlanValidator.validate(p, "video", new BigDecimal("12"));
        assertTrue(problems.stream().anyMatch(x -> x.contains("越界") || x.contains("20")));
    }

    @Test
    void negativeMergeDedupeAndAppend() {
        String merged = DirectorPlanValidator.mergeNegative("people, watermark, blurry",
                DirectorPlanValidator.DEFAULT_NEGATIVE);
        assertEquals("people, watermark, blurry, text, logo, subtitle, caption, signature, lowres, "
                + "deformed, extra limbs, badly drawn, jpeg artifacts, ugly, nsfw", merged);
        // 已有默认词不再重复
        String again = DirectorPlanValidator.mergeNegative("nsfw", List.of("nsfw", "text"));
        assertEquals("nsfw, text", again);
    }

    @Test
    void aspectPixelsKnownRatios() {
        assertEquals(new AspectPixels.Dim(1344, 768), AspectPixels.forImage("16:9"));
        assertEquals(new AspectPixels.Dim(1024, 1024), AspectPixels.forImage("1:1"));
        assertEquals(new AspectPixels.Dim(768, 1344), AspectPixels.forImage("9:16"));
        assertEquals(new AspectPixels.Dim(1344, 768), AspectPixels.forImage("unknown")); // fallback
    }

    @Test
    void shotOver10sRejected() {
        ObjectNode p = videoPlan(24);
        addShots(p, new ObjectNode[]{
                shot(1, 3, "wide street in rain with passing umbrellas, cinematic texture, moody"),
                shot(2, 11, "long take under bridge with heavy rain, cinematic anamorphic feel"), // >10
                shot(3, 4, "the boat drifting into darkness, final frame, cinematic grade"),
                shot(4, 3, "medium shot of ripples on water surface, slow motion, cinematic"),
                shot(5, 3, "extreme close up of wet paper edge, texture detail, cinematic"),
        });
        List<String> problems = DirectorPlanValidator.validate(p, "video", new BigDecimal("24"));
        assertTrue(problems.stream().anyMatch(x -> x.contains("10s")));
    }

}