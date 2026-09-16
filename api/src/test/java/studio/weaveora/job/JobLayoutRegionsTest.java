package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5 位置与「参考图 → 主体」映射 / 语言一致性。
 *
 * <p>背景（2026-09-16 用户实测）：
 * <ul>
 *   <li>多主体同框时 Edit 模型拿到 image1/image2 不知道哪张脸对应谁 → 串脸；所以必须点名主体；</li>
 *   <li>位置三档（逐镜 layout &gt; 方案级 region &gt; 点选坐标）要各就各位；</li>
 *   <li>用户选「中文」时，系统**追加**的句子（锚定/位置/负面守卫）不能还是英文 —— 否则一半中文一半英文。</li>
 * </ul>
 */
class JobLayoutRegionsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode payload(String positive) {
        ObjectNode p = M.createObjectNode();
        p.put("positive_prompt", positive);
        return p;
    }

    private static JobService.RefCtx refs(String... subjects) {
        return new JobService.RefCtx(List.of(), List.of(), List.of(subjects), List.of(), "", "");
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 语言探测 ----------

    @Test
    void detectsChinesePromptsButNotEnglishWithChineseNames() {
        assertTrue(JobService.isZhText("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳，烟雾弥漫"));
        assertFalse(JobService.isZhText(
                "Cinematic film still, Baoyu (宝玉) walking through the wasteland, dramatic lighting"));
        assertFalse(JobService.isZhText(""));
        assertFalse(JobService.isZhText(null));
    }

    // ---------- 点名主体（锚定句，语言跟随镜文本） ----------

    @Test
    void anchorNamesEverySubjectInImageSlotsEnglish() {
        String a = JobService.refAnchor("image1 = 宝玉, image2 = 可卿", false);
        assertTrue(a.contains("Reference image mapping: image1 = 宝玉, image2 = 可卿"), a);
        assertTrue(a.contains("do not share, blend or swap their faces"), a);
    }

    @Test
    void anchorIsChineseWhenPromptIsChinese() {
        String a = JobService.refAnchor("image1 = 宝玉, image2 = 可卿", true);
        assertTrue(a.contains("参考图对应关系：image1 = 宝玉, image2 = 可卿"), a);
        assertTrue(a.contains("禁止共用、混合或互换面容"), a);
        // 整套中文时不得混英文句子
        assertFalse(a.contains("Reference image"), a);
    }

    @Test
    void anchorWithoutSubjectNameStaysGeneric() {
        assertTrue(JobService.refAnchor("", false).contains("must strictly follow the provided reference image"));
        assertTrue(JobService.refAnchor(null, true).contains("必须严格以给定参考图为准"));
    }

    // ---------- 本镜主体（shots[].cast） ----------

    @Test
    void castDistinguishesUnsetFromExplicitEmptyShot() {
        // 不设 cast = 按镜文本自动匹配
        assertNull(JobService.castOf(json("{}")));
        assertNull(JobService.castOf(null));
        // cast=[] = 明确的空镜（不注入任何人物参考图）
        assertEquals(List.of(), JobService.castOf(json("{\"cast\":[]}")));
        // 显式名单（去空、去空格）
        assertEquals(List.of("宝玉", "可卿"), JobService.castOf(json("{\"cast\":[\"宝玉\",\" 可卿 \",\"\"]}")));
        // 类型不对（老数据/手改）→ 当未指定，不炸
        assertNull(JobService.castOf(json("{\"cast\":\"宝玉\"}")));
    }

    // ---------- 位置三档优先级 ----------

    @Test
    void noPositionLeavesPromptUntouched() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), refs("宝玉", "可卿"));
        // 点名由 refs.anchor() 负责；这里没有位置 → 不该动正词、也不该下发 referenceRegions
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
        assertNull(p.get("referenceRegions"));
    }

    @Test
    void perShotLayoutWinsAndIsWrittenToPromptAndRegions() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode plan = json("""
                {"referenceAssets":[{"assetId":"a1","subject":"宝玉",
                  "region":{"x":0.05,"y":0.05,"w":0.4,"h":0.9}}]}
                """);
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.62,"y":0.10,"w":0.30,"h":0.60},
                           {"subject":"可卿","x":0.10,"y":0.20,"w":0.25,"h":0.55}],
                 "lipsync_targets":{"宝玉":{"x":0.10,"y":0.10}}}
                """);

        JobService.applyLayoutRegions(p, plan, shot, refs("宝玉", "可卿"));

        String pos = p.path("positive_prompt").asText();
        // ① 逐镜 layout 覆盖方案级 region（0.05 → 0.62），并覆盖点选坐标
        assertTrue(pos.contains("image1 = 宝玉 (upper-center, x=0.62, y=0.10, box 0.30x0.60)"), pos);
        assertTrue(pos.contains("image2 = 可卿 (upper-left, x=0.10, y=0.20, box 0.25x0.55)"), pos);

        JsonNode regions = p.get("referenceRegions");
        assertEquals(2, regions.size());
        assertEquals(0.62, regions.get(0).path("x").asDouble(), 1e-6);
        assertEquals(0.60, regions.get(0).path("h").asDouble(), 1e-6);
        assertEquals(0.10, regions.get(1).path("x").asDouble(), 1e-6);
    }

    @Test
    void planLevelRegionIsUsedWhenShotHasNoLayout() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode plan = json("""
                {"referenceAssets":[{"assetId":"a1","subject":"宝玉",
                  "region":{"x":0.05,"y":0.05,"w":0.4,"h":0.9}}]}
                """);

        JobService.applyLayoutRegions(p, plan, json("{}"), refs("宝玉"));

        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("image1 = 宝玉 (upper-left, x=0.05, y=0.05, box 0.40x0.90)"), pos);
        assertEquals(0.05, p.get("referenceRegions").get(0).path("x").asDouble(), 1e-6);
    }

    @Test
    void planLevelRegionAlsoReadFromSubjectsRefs() {
        ObjectNode p = payload("cinematic still of two figures");
        // 前端 syncReferenceAssets 会同时写 subjects[].refs[].region
        JsonNode plan = json("""
                {"subjects":[{"name":"可卿","enabled":true,
                  "refs":[{"assetId":"a2","checked":true,"region":{"x":0.7,"y":0.1,"w":0.28,"h":0.8}}]}]}
                """);

        JobService.applyLayoutRegions(p, plan, json("{}"), refs("可卿"));

        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("image1 = 可卿 (upper-right, x=0.70, y=0.10, box 0.28x0.80)"), pos);
    }

    @Test
    void facePickFallsBackToDefaultBoxCentredOnTheClick() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("{\"lipsync_targets\":{\"宝玉\":{\"x\":0.30,\"y\":0.40}}}");

        JobService.applyLayoutRegions(p, json("{}"), shot, refs("宝玉"));

        String pos = p.path("positive_prompt").asText();
        // 点选只有坐标 → 以点为中心默认框 0.30×0.45 = x 0.15 / y 0.20
        assertTrue(pos.contains("image1 = 宝玉 (upper-left, x=0.15, y=0.20, box 0.30x0.45)"), pos);
    }

    @Test
    void chinesePromptGetsChinesePlacementSentence() {
        ObjectNode p = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳，烟雾弥漫");
        JsonNode shot = json("{\"layout\":[{\"subject\":\"宝玉\",\"x\":0.05,\"y\":0.1,\"w\":0.4,\"h\":0.8}]}");

        JobService.applyLayoutRegions(p, json("{}"), shot, refs("宝玉"));

        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("主体位置（归一化画面坐标，原点在左上"), pos);
        assertTrue(pos.contains("image1 = 宝玉（左上，x=0.05，y=0.10，框 0.40x0.80）"), pos);
        assertFalse(pos.contains("Subject placement"), pos);
    }

    @Test
    void unnamedStyleReferenceIsNotPositioned() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), refs("", "宝玉"));
        // 没有位置 → 正词不动（点名由 anchor 负责）
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
    }

    @Test
    void noRefsLeavesEverythingUntouched() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), JobService.RefCtx.empty());
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
        assertNull(p.get("referenceRegions"));
    }

    @Test
    void illegalBoxesAreIgnored() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.1,"y":0.1,"w":0,"h":0.4},
                           {"subject":"可卿","x":-1,"y":0.1,"w":0.3,"h":0.4}]}
                """);
        JobService.applyLayoutRegions(p, json("{}"), shot, refs("宝玉", "可卿"));
        assertNull(p.get("referenceRegions"));
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
    }
}
