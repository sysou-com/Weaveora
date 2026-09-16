package studio.weaveora.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5 位置与「参考图 → 主体」映射。
 *
 * <p>背景（2026-09-16 用户要求）：多主体同框时，Qwen-Image-Edit 拿到 image1/image2 却不知道
 * 哪张脸对应哪个角色名，只能自己猜 → 串脸/换人。所以正词里**必须点名主体**，并把「谁在哪」
 * 一并写清；位置来源三档（逐镜 layout &gt; 方案级 region &gt; 点选坐标）要能各就各位。
 */
class JobLayoutRegionsTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 造一个带主体与参考图槽位的 payload。 */
    private static ObjectNode payload() {
        ObjectNode p = M.createObjectNode();
        p.put("positive_prompt", "cinematic still of two figures");
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

    @Test
    void namesEveryBoundSubjectEvenWithoutAnyPosition() {
        ObjectNode p = payload();
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), refs("宝玉", "可卿"));

        String pos = p.path("positive_prompt").asText();
        // 没位置也必须有映射（后端兜底，不依赖 LLM 是否点名）
        assertTrue(pos.contains("image1 = 宝玉"), pos);
        assertTrue(pos.contains("image2 = 可卿"), pos);
        // 无位置 → 不下发 referenceRegions（留空=不启用区域条件）
        assertNull(p.get("referenceRegions"), String.valueOf(p.get("referenceRegions")));
    }

    @Test
    void perShotLayoutWinsAndIsWrittenToPromptAndRegions() {
        ObjectNode p = payload();
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
        assertTrue(pos.contains("strictly follow its own reference image"), pos);

        JsonNode regions = p.get("referenceRegions");
        assertEquals(2, regions.size());
        assertEquals(0.62, regions.get(0).path("x").asDouble(), 1e-6);
        assertEquals(0.60, regions.get(0).path("h").asDouble(), 1e-6);
        assertEquals(0.10, regions.get(1).path("x").asDouble(), 1e-6);
    }

    @Test
    void planLevelRegionIsUsedWhenShotHasNoLayout() {
        ObjectNode p = payload();
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
        ObjectNode p = payload();
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
        ObjectNode p = payload();
        JsonNode shot = json("{\"lipsync_targets\":{\"宝玉\":{\"x\":0.30,\"y\":0.40}}}");

        JobService.applyLayoutRegions(p, json("{}"), shot, refs("宝玉"));

        String pos = p.path("positive_prompt").asText();
        // 点选只有坐标 → 以点为中心默认框 0.30×0.45 = x 0.15 / y 0.20
        assertTrue(pos.contains("image1 = 宝玉 (upper-left, x=0.15, y=0.20, box 0.30x0.45)"), pos);
    }

    @Test
    void unnamedStyleReferenceIsNotNamed() {
        ObjectNode p = payload();
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), refs("", "宝玉"));

        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("image2 = 宝玉"), pos);
        assertTrue(!pos.contains("image1 ="), pos);
    }

    @Test
    void noRefsLeavesPromptUntouched() {
        ObjectNode p = payload();
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), JobService.RefCtx.empty());
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
        assertNull(p.get("referenceRegions"));
    }

    @Test
    void illegalBoxesAreIgnored() {
        ObjectNode p = payload();
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.1,"y":0.1,"w":0,"h":0.4},
                           {"subject":"可卿","x":-1,"y":0.1,"w":0.3,"h":0.4}]}
                """);
        JobService.applyLayoutRegions(p, json("{}"), shot, refs("宝玉", "可卿"));
        // 非法框全部丢弃 → 退化为「只有点名、没有位置」
        assertNull(p.get("referenceRegions"));
        assertTrue(p.path("positive_prompt").asText().contains("image1 = 宝玉"));
    }
}
