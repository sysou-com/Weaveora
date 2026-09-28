package studio.weaveora.job;

import org.junit.jupiter.api.Test;
import studio.weaveora.job.api.KeyframeConfirm;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15（2026-09-25 用户裁定）：多主体关键帧的「人脸数量 &gt; 2」闸门。
 *
 * <p>需求原话：「在每一镜的关键帧的时候检查剧情主体数量大于 2 个则提示关键图中人脸数量大于 2，
 * 建议使用补上 over_the_shoulder（过肩）/ medium_close（中近景）/ close_up（近景）等，
 * 在弹框让用户选择场景和要显示人脸的主体，确定以后更新提示词并继续进行关键帧出图。」
 *
 * <p>本测试锁三件事：
 * <ol>
 *   <li>景别词典 → 提示词写法（中/英）；</li>
 *   <li>把正词里**已有的景别词替换掉** —— 实测正词里留着 “medium shot” 时，即使追写“过肩”
 *       也仍被往宽景推（一句里两个景别互相打架）；</li>
 *   <li>确认句的内容：景别 + 只让指定主体露脸 + 主脸占帧高（粗比例）+ 场景补充。</li>
 * </ol>
 */
class JobKeyframeFaceCheckTest {

    // ---------- 1. 景别词典 ----------

    @Test
    void shotSizePhraseMapsDictionary() {
        assertEquals("过肩镜头", JobService.shotSizePhrase("over_the_shoulder", true));
        assertEquals("over-the-shoulder shot", JobService.shotSizePhrase("over_the_shoulder", false));
        assertEquals("中近景", JobService.shotSizePhrase("medium_close", true));
        assertEquals("medium close-up shot", JobService.shotSizePhrase("medium_close", false));
        assertEquals("近景", JobService.shotSizePhrase("close_up", true));
        assertEquals("close-up shot", JobService.shotSizePhrase("close_up", false));
        // 连字符写法也认；未知值不动手
        assertEquals("over-the-shoulder shot", JobService.shotSizePhrase("over-the-shoulder", false));
        assertNull(JobService.shotSizePhrase("wide", true));
        assertNull(JobService.shotSizePhrase(null, true));
        assertNull(JobService.shotSizePhrase("", false));
    }

    // ---------- 2. 替换正词里已有的景别词 ----------

    @Test
    void replacesExistingShotSizeWordsInEnglish() {
        String raw = "Cinematic film still, medium shot, eye-level, slow push-in; Baoyu turns back.";
        String out = JobService.replaceShotSizeWords(raw, "over-the-shoulder shot");
        assertTrue(out.contains("over-the-shoulder shot"), out);
        assertFalse(out.toLowerCase().contains("medium shot"), "旧景别必须被替换掉：" + out);
        assertTrue(out.contains("eye-level, slow push-in; Baoyu turns back."), "其余文字一字不动：" + out);
    }

    @Test
    void replacesExistingShotSizeWordsInChinese() {
        String raw = "电影感静帧，中景，平拍；宝玉回身望向警幻。";
        String out = JobService.replaceShotSizeWords(raw, "过肩镜头");
        assertTrue(out.startsWith("电影感静帧，过肩镜头，平拍；"), out);
        assertFalse(out.contains("，中景，"), "旧景别必须被替换掉：" + out);
    }

    @Test
    void replaceIsNoopWhenPhraseMissing() {
        String raw = "medium shot, eye-level";
        assertEquals(raw, JobService.replaceShotSizeWords(raw, null));
        assertEquals(raw, JobService.replaceShotSizeWords(raw, ""));
        assertEquals("", JobService.replaceShotSizeWords("", "过肩镜头"));
    }

    // ---------- 3. 弹框里那句话 ----------

    @Test
    void messageCountsFacesAndSuggestsFraming() {
        String zh = JobService.keyframeFaceMessage(true, 3, List.of("宝玉", "可卿", "警幻"));
        assertTrue(zh.contains("剧情主体 3 个"), zh);
        assertTrue(zh.contains("3 张脸"), zh);
        assertTrue(zh.contains("超过 2 张"), zh);
        assertTrue(zh.contains("过肩") && zh.contains("中近景") && zh.contains("近景"), zh);
        assertTrue(zh.contains("宝玉、可卿、警幻"), zh);

        String en = JobService.keyframeFaceMessage(false, 3, List.of("Baoyu", "Keqing", "Jinghuan"));
        assertTrue(en.contains("3 story subjects"), en);
        assertTrue(en.contains("over-the-shoulder"), en);
        assertTrue(en.contains("Baoyu, Keqing, Jinghuan"), en);
    }

    // ---------- 4. 确认句 ----------

    @Test
    void constraintCarriesFramingSubjectsAndSceneNote() {
        KeyframeConfirm c = new KeyframeConfirm(4, null, "over_the_shoulder",
                List.of("宝玉", "可卿"), "太虚幻境庭院，冷月色");
        String zh = JobService.keyframeConstraint(c, true);
        assertTrue(zh.contains("过肩镜头"), zh);
        assertTrue(zh.contains("「宝玉」「可卿」"), zh);
        assertTrue(zh.contains("只让"), zh);
        assertTrue(zh.contains("五分之一"), zh);
        assertTrue(zh.contains("场景补充：太虚幻境庭院，冷月色"), zh);

        String en = JobService.keyframeConstraint(c, false);
        assertTrue(en.contains("Over-the-shoulder shot"), en);
        assertTrue(en.contains("宝玉 and 可卿"), en);
        assertTrue(en.contains("one fifth"), en);
        assertTrue(en.contains("Scene note: 太虚幻境庭院，冷月色"), en);
    }

    @Test
    void emptyConfirmProducesNothing() {
        assertTrue(JobService.keyframeConstraint(new KeyframeConfirm(4, null, "", List.of(), ""), true).isEmpty());
        assertTrue(JobService.keyframeConstraint(null, true).isEmpty());
        String raw = "medium shot, eye-level";
        assertEquals(raw, JobService.applyKeyframeConfirm(raw, null), "没有确认时正词一字不动");
        assertEquals(raw, JobService.applyKeyframeConfirm(raw,
                new KeyframeConfirm(4, null, "", List.of(), "")), "空确认时正词一字不动");
    }

    // ---------- 5. 落到正词上（端到端，含换景别） ----------

    @Test
    void applyConfirmReplacesFramingAndAppendsConstraint() {
        String raw = "Cinematic film still, medium shot, eye-level, slow push-in; "
                + "a Qing-dynasty courtyard, cold blue moonlight.";
        KeyframeConfirm c = new KeyframeConfirm(4, 0, "medium_close",
                List.of("Keqing", "Baoyu"), null);
        String out = JobService.applyKeyframeConfirm(raw, c);
        assertTrue(out.contains("medium close-up shot"), out);
        assertFalse(out.toLowerCase().contains("medium shot,"), "旧景别被替换：" + out);
        assertTrue(out.contains("[Framing constraint, confirmed by the user]"), out);
        assertTrue(out.contains("only Keqing and Baoyu show a clear face"), out);
        assertTrue(out.contains("one fifth of the frame height"), out);
    }

    @Test
    void confirmCoversWholeShotOrSingleKeyframe() {
        KeyframeConfirm all = new KeyframeConfirm(4, null, "close_up", List.of("宝玉"), null);
        assertTrue(all.coversKeyframe(0));
        assertTrue(all.coversKeyframe(5));
        KeyframeConfirm one = new KeyframeConfirm(4, 2, "close_up", List.of("宝玉"), null);
        assertFalse(one.coversKeyframe(0));
        assertTrue(one.coversKeyframe(2));
        assertTrue(one.isEmpty() == false);
    }

    // ---------- 6. 主体去重 ----------

    @Test
    void distinctSubjectsDedupesAndDropsBlanks() {
        JobService.RefCtx refs = new JobService.RefCtx(
                List.of("id1", "id2", "id3", "id4"),
                List.of("k1", "k2", "k3", "k4"),
                List.of("宝玉", "可卿", "宝玉", "  "),
                List.of(),
                "anchor", "宝玉");
        assertEquals(List.of("宝玉", "可卿"), JobService.distinctSubjects(refs));
        assertTrue(JobService.distinctSubjects(null).isEmpty());
        assertTrue(JobService.distinctSubjects(
                new JobService.RefCtx(List.of(), List.of(), List.of(), List.of(), "", "")).isEmpty());
    }

    // ---------- 7. 剧情主体数（帧级 cast > 镜级 cast > 参考图绑定） ----------

    private final com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private JobService.RefCtx refsOf(String... subjects) {
        return new JobService.RefCtx(List.of(), List.of(), List.of(subjects), List.of(), "", "");
    }

    @Test
    void storySubjectsPreferFrameCastThenShotCastThenRefs() {
        // 镜级 cast 写 3 人 → 3 个（与参考图无关）
        var shot = om.createObjectNode();
        shot.putArray("cast").add("宝玉").add("可卿").add("警幻");
        assertEquals(3, JobService.keyframeSubjects(shot, refsOf("宝玉")).size());

        // 帧级 cast 覆盖镜级（第 2 帧只有 2 人 → 取主体最多的那一帧 = 镜级 3 人）
        var kfs = shot.putArray("keyframes");
        kfs.addObject().put("positive_prompt", "p");
        kfs.addObject().putArray("cast").add("宝玉").add("可卿");
        assertEquals(3, JobService.keyframeSubjects(shot, refsOf("宝玉")).size());

        // 都没写 cast → 退回参考图绑定的主体
        var bare = om.createObjectNode();
        assertEquals(List.of("宝玉", "可卿", "警幻"),
                JobService.keyframeSubjects(bare, refsOf("宝玉", "可卿", "警幻")));

        // 明确的空镜 cast=[] → 0 个（**不能**退回参考图主体）
        var empty = om.createObjectNode();
        empty.putArray("cast");
        assertTrue(JobService.keyframeSubjects(empty, refsOf("宝玉", "可卿", "警幻")).isEmpty());
    }
}
