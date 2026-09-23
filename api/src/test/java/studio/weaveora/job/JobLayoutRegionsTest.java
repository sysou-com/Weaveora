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
 * P5 位置（三层）与「参考图 ↔ 主体」映射 / 语言一致性。
 *
 * <p>契约（2026-09-16 定稿）：
 * <ul>
 *   <li>位置三层优先级：帧级 {@code shots[].keyframes[j].layout} &gt; 镜级 {@code shots[].layout}
 *       &gt; 方案默认（{@code subjects[].region} → {@code referenceAssets[].region} / {@code refs[].region}）
 *       &gt; 预览图点选 {@code lipsync_targets}；</li>
 *   <li>**正词里只出现一处** {@code Picture N (imageN) = 主体名（方位/框）}（由 applyLayoutRegions 统一写）；
 *       {@code refAnchor} 只负责身份约束那一句 —— 两处都写会让模型更难分清谁对应哪张图；
 *       槽位名**同时写** {@code Picture N}（Qwen-Image-Edit 节点内部就是 `Picture {}`）与旧口径 `imageN`；</li>
 *   <li>位置清单必须带「以本清单为准」的覆盖句（镜文案里的方位词与用户设的框会矛盾 → 位置错位）；</li>
 *   <li>即使一个位置都没设，也照写点名（防「多角色串脸」）；无 subject 名的风格参考图不点名；</li>
 *   <li>系统追加的文字跟随镜文本语言（用户选中文时不得中英混杂）。</li>
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

    // ---------- 身份约束句（refAnchor）：只管身份，不再列 imageN ----------

    @Test
    void anchorCarriesOnlyTheIdentityConstraint() {
        String en = JobService.refAnchor("image1 = 宝玉", false);
        assertTrue(en.contains("do not share, blend or swap their faces"), en);
        assertFalse(en.contains("image1 ="), "点名清单不该在这里再写一遍（避免两处写法不一致）: " + en);

        String zh = JobService.refAnchor("image1 = 宝玉", true);
        assertTrue(zh.contains("禁止共用、混合或互换面容"), zh);
        assertFalse(zh.contains("Reference image"), zh);
    }

    // ---------- 位置三层 ----------

    @Test
    void noPositionStillNamesSubjectsButSetsNoRegions() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), -1, refs("宝玉", "可卿"));
        String pos = p.path("positive_prompt").asText();
        // 没位置也要点名（后端兜底，不依赖 LLM）
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿"), pos);
        assertFalse(pos.contains("x="), "没有位置时不应编造坐标: " + pos);
        // 没位置 → 不下发 referenceRegions（= 不启用区域条件）
        assertNull(p.get("referenceRegions"));
    }

    @Test
    void perShotLayoutWinsOverPlanRegionAndFacePick() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","region":{"x":0.05,"y":0.05,"w":0.4,"h":0.9}}],
                 "referenceAssets":[{"assetId":"a1","subject":"宝玉",
                   "region":{"x":0.04,"y":0.04,"w":0.4,"h":0.9}}]}
                """);
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.62,"y":0.10,"w":0.30,"h":0.60},
                           {"subject":"可卿","x":0.10,"y":0.20,"w":0.25,"h":0.55}],
                 "lipsync_targets":{"宝玉":{"x":0.10,"y":0.10}}}
                """);

        JobService.applyLayoutRegions(p, plan, shot, -1, refs("宝玉", "可卿"));

        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (position: x 0.62–0.92, right band, y 0.10–0.70)"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿 (position: x 0.10–0.35, left band, y 0.20–0.75)"), pos);
        // ★ 2026-09-21：显式从左到右顺序（只写各自区间时模型仍可能搞乱顺序）
        assertTrue(pos.contains("Left to right: 可卿 (image2) -> 宝玉 (image1)"), pos);

        JsonNode regions = p.get("referenceRegions");
        assertEquals(2, regions.size());
        assertEquals(0.62, regions.get(0).path("x").asDouble(), 1e-6);
        assertEquals(0.60, regions.get(0).path("h").asDouble(), 1e-6);
        assertEquals(0.10, regions.get(1).path("x").asDouble(), 1e-6);
    }

    @Test
    void frameLevelLayoutBeatsShotLevel() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.50,"y":0.50,"w":0.20,"h":0.20}],
                 "keyframes":[{"positive_prompt":"f1"},{"positive_prompt":"f2",
                   "layout":[{"subject":"宝玉","x":0.05,"y":0.05,"w":0.30,"h":0.40}]}]}
                """);

        // 第 2 帧（index=1）有自己的 layout → 帧级优先（★ 2026-09-21：站位用横向区间 + 带位，不再用会失真的方位词）
        JobService.applyLayoutRegions(p, json("{}"), shot, 1, refs("宝玉"));
        assertTrue(p.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (position: x 0.05–0.35, left band, y 0.05–0.45)"),
                p.path("positive_prompt").asText());
        assertEquals(0.05, p.get("referenceRegions").get(0).path("x").asDouble(), 1e-6);

        // 第 1 帧（index=0）没设 → 退回镜级
        ObjectNode p0 = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p0, json("{}"), shot, 0, refs("宝玉"));
        assertTrue(p0.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (position: x 0.50–0.70, middle band, y 0.50–0.70)"),
                p0.path("positive_prompt").asText());
        assertEquals(0.50, p0.get("referenceRegions").get(0).path("x").asDouble(), 1e-6);
    }

    @Test
    void subjectLevelPlanRegionWorksWithoutAnyMaterialRefs() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","refs":[],"portraitAssetId":"p1",
                  "region":{"x":0.10,"y":0.20,"w":0.30,"h":0.50}}]}
                """);
        JobService.applyLayoutRegions(p, plan, json("{}"), -1, refs("宝玉"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (position: x 0.10–0.40, left band, y 0.20–0.70)"), pos);
        assertEquals(0.10, p.get("referenceRegions").get(0).path("x").asDouble(), 1e-6);
    }

    @Test
    void planLevelRegionAlsoReadFromReferenceAssetsAndRefs() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode plan = json("""
                {"referenceAssets":[{"assetId":"a1","subject":"宝玉",
                   "region":{"x":0.05,"y":0.05,"w":0.4,"h":0.9}}],
                 "subjects":[{"name":"可卿","enabled":true,
                   "refs":[{"assetId":"a2","checked":true,"region":{"x":0.7,"y":0.1,"w":0.28,"h":0.8}}]}]}
                """);
        JobService.applyLayoutRegions(p, plan, json("{}"), -1, refs("宝玉", "可卿"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (position: x 0.05–0.45, left band, y 0.05–0.95)"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿 (position: x 0.70–0.98, right band, y 0.10–0.90)"), pos);
        assertEquals(0.40, p.get("referenceRegions").get(0).path("w").asDouble(), 1e-6);
    }

    @Test
    void facePickFallsBackToDefaultBoxCentredOnTheClick() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("{\"lipsync_targets\":{\"宝玉\":{\"x\":0.30,\"y\":0.40}}}");
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉"));
        // 点选只有坐标 → 以点为中心默认框 0.30×0.45 = x 0.15 / y 0.20（★ 2026-09-21：站位写横向区间，框值看 referenceRegions）
        assertTrue(p.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (position: x 0.15–0.45, left band, y 0.20–0.65)"),
                p.path("positive_prompt").asText());
        assertEquals(0.15, p.get("referenceRegions").get(0).path("x").asDouble(), 1e-6);
        assertEquals(0.45, p.get("referenceRegions").get(0).path("h").asDouble(), 1e-6);
    }

    // ---------- 语言一致性 ----------

    @Test
    void chinesePromptGetsChineseSentence() {
        ObjectNode p = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳，烟雾弥漫");
        JsonNode shot = json("{\"layout\":[{\"subject\":\"宝玉\",\"x\":0.05,\"y\":0.1,\"w\":0.4,\"h\":0.8}]}");
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("参考图映射（按送入顺序"), pos);
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉（位置：x 0.05–0.45 的左带，纵向 y 0.10–0.90）"), pos);
        assertTrue(pos.contains("不同 imageN 是**不同的人**"), pos);
        // 单主体不写「从左到右」顺序句（≥ 2 个会写；见 perShotLayoutWinsOverPlanRegionAndFacePick）
        assertFalse(pos.contains("画面从左到右依次为"), pos);
        assertFalse(pos.contains("Reference mapping"), pos);
    }

    // ---------- ★ 2026-09-18：motion(clip) 不推位置框（用「以关键帧为构图基准」代替） ----------

    /**
     * clip：不写框/坐标/槽位名、不下发 referenceRegions，但要保留身份 + 档案 + 构图基准 + 身高比例 + 动作方向。
     *
     * <p>为什么（用户实测第 4 镜）：关键帧已经把构图定死，再推 {@code 框 0.30x0.98 / y=0.02} 会让模型
     * 在首帧上「重新推拉/放大」——警幻被放成 y=0.02 后「从远处走上来」越走越高；而 motion 真正需要的
     * 是动作/朝向/方向/镜头运动。
     */
    @Test
    void motionClipDropsBoxesButKeepsIdentityProfilesAndCompositionAnchor() {
        ObjectNode p = payload("电影感镜头：宝玉回身望向警幻");
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","kind":"person","gender":"male","height":"165"},
                             {"name":"警幻","kind":"person","gender":"female","height":"162"}]}
                """);
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.38,"y":0.02,"w":0.30,"h":0.98},
                           {"subject":"警幻","x":0.05,"y":0.02,"w":0.32,"h":0.98}]}
                """);
        JobService.applyLayoutRegions(p, plan, shot, -1, refs("宝玉", "警幻"), true);
        String pos = p.path("positive_prompt").asText();

        // ① 不下发位置框（worker 视频通路本来也不读）
        assertNull(p.get("referenceRegions"));
        // ② 正词里不得出现框/坐标/方位或槽位名
        assertFalse(pos.contains("框 0.30x0.98"), pos);
        assertFalse(pos.contains("x="), pos);
        assertFalse(pos.contains("Picture 1"), "视频通路不送参考图，不该出现槽位名: " + pos);
        assertFalse(pos.contains("位置以本清单为准"), pos);
        // ③ ★ 2026-09-22（用户裁定）：motion 正词**不再带任何系统追加的人物信息**
        //   （主体清单/性别年龄档案/「面容发型服饰以参考图为准」等硬约束全部删除）——
        //   图生视频只写「运动 + 运镜」（官方公式：图像已确定主体/场景/风格），身份由首帧（关键帧）承担。
        //   注：**剧情文案本身**点名角色不算「人物信息」（那是导演写的动作描述，本用例的 payload 就含「宝玉/警幻」）。
        assertFalse(pos.contains("主体与人物设定"), "motion 不再写人物设定块: " + pos);
        assertFalse(pos.contains("[性别"), "motion 不再写性别/年龄硬约束: " + pos);
        assertFalse(pos.contains("面容/发型/服饰/体态一律以各自参考图"), "motion 不再写外观以参考图为准: " + pos);
        assertFalse(pos.contains("不得把男性画成女性"), "motion 不再写性别硬约束: " + pos);
        assertFalse(pos.contains("Subjects & profiles"), "motion 不得中英混杂: " + pos);
        // ④ 构图以关键帧为准；**不再**写「同框身高比例按档案」
        //    ★ 2026-09-22 产品决策：该行与「以关键帧为准」直接冲突，实测导致模型重设三人身高/体型
        //    （用户报「clip 里面部和体态都变了」）⇒ 整行删除。
        assertTrue(pos.contains("【以关键帧为构图基准】"), pos);
        assertFalse(pos.contains("同框身高比例"), "2026-09-22 起不再下发身高比例行: " + pos);
        // ⑤ 只表现动作/朝向/方向/镜头运动，禁止再放大/推近/越来越高
        assertTrue(pos.contains("只需表现动作、朝向、方向与镜头运动"), pos);
        assertTrue(pos.contains("不得改变人物之间的相对大小与身高比例"), pos);
        assertFalse(pos.contains("Reference mapping"), "中文镜不得中英混杂: " + pos);
    }

    /** 同一镜同一框：still 保留框 + referenceRegions，clip 全部拿掉（避免“一处改动顺手改坏关键帧”）。 */
    @Test
    void sameShotKeepsBoxesForStillButNotForMotion() {
        JsonNode shot = json("{\"layout\":[{\"subject\":\"宝玉\",\"x\":0.38,\"y\":0.02,\"w\":0.30,\"h\":0.98}]}");
        ObjectNode still = payload("cinematic still of a figure");
        ObjectNode clip = payload("cinematic still of a figure");
        JobService.applyLayoutRegions(still, json("{}"), shot, -1, refs("宝玉"));
        JobService.applyLayoutRegions(clip, json("{}"), shot, -1, refs("宝玉"), true);

        // ★ 2026-09-21 二次修正：正词写「横向区间 + 带位 + 是否占满画高」（仍不写 x=/y=/框）
        assertTrue(still.path("positive_prompt").asText().contains("Picture 1 (image1) = 宝玉 (position: x 0.38–0.68, middle band, full height)"), still.path("positive_prompt").asText());
        assertFalse(still.path("positive_prompt").asText().contains("x="), "不应再写归一化坐标（x= 形式）");
        assertTrue(still.path("positive_prompt").asText().contains("the story text wins"));
        assertEquals(1, still.get("referenceRegions").size());
        assertEquals(0.30, still.get("referenceRegions").get(0).path("w").asDouble(), 1e-6);

        assertFalse(clip.path("positive_prompt").asText().contains("box 0.30x0.98"));
        assertFalse(clip.path("positive_prompt").asText().contains("x="), "clip 也不得出现坐标");
        assertTrue(clip.path("positive_prompt").asText().contains("[Keyframe is authoritative for composition]"),
                clip.path("positive_prompt").asText());
        assertNull(clip.get("referenceRegions"));
    }

    /** 身高比例行已整体移除（2026-09-22 产品决策）；本用例保留为**回归防线**：任何情况下都不得再出现。 */
    @Test
    void motionOmitsHeightLineWhenProfilesLackHeights() {
        ObjectNode p = payload("电影感镜头：宝玉独自立于庭中");
        JsonNode shot = json("{\"layout\":[{\"subject\":\"宝玉\",\"x\":0.38,\"y\":0.02,\"w\":0.30,\"h\":0.98}]}");
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉"), true);
        String pos = p.path("positive_prompt").asText();
        assertFalse(pos.contains("同框身高比例"), pos);
        assertTrue(pos.contains("【以关键帧为构图基准】"), pos);
        assertTrue(pos.contains("宝玉"), pos);
    }

    // ---------- 边界 ----------

    @Test
    void unnamedStyleReferenceIsNotNamed() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), -1, refs("", "宝玉"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("Picture 2 (image2) = 宝玉"), pos);
        assertFalse(pos.contains("Picture 1"), "无名风格参考图不应被点名: " + pos);
    }

    /**
     * ★ 2026-09-21 产品决策变更：**方位以剧情句为准**，位置总控的区域框降级为兑底；
     * 两者矛盾时**只提示、不纠偏**。
     *
     * <p>本测试**取代** 2026-09-16 的 {@code positionListCarriesAuthoritativeOverrideClause}
     * （那条断言「一律以本清单为准」的强制覆盖）。为什么推翻：同一镜（第 4 镜）的文案
     * 「警幻居后景」与位置框 x=0.65 矛盾时，“以清单为准”会把用户的剧情意志改掉；
     * 用户 2026-09-21 裁定：按剧情执行 + 把矛盾提示给用户看。
     */
    @Test
    void storyTextWinsOverPositionList() {
        ObjectNode zh = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳");
        JsonNode shot = json("""
                {"shot_no":4,"layout":[{"subject":"宝玉","x":0.24,"y":0.08,"w":0.19,"h":0.76},
                           {"subject":"可卿","x":0.06,"y":0.06,"w":0.17,"h":0.78}]}
                """);
        JobService.applyLayoutRegions(zh, json("{}"), shot, -1, refs("宝玉", "可卿"));
        String pos = zh.path("positive_prompt").asText();
        assertTrue(pos.contains("以剧情句为准"), pos);
        assertTrue(pos.contains("兜底"), pos);
        assertFalse(pos.contains("一律以本清单为准"), pos);
        // 两个主体都点名了、无纵深词、无左右断言 → 不应有冲突提示
        assertTrue(zh.path("promptWarnings").isMissingNode(), zh.toString());

        ObjectNode en = payload("cinematic still of two figures standing together");
        JobService.applyLayoutRegions(en, json("{}"), shot, -1, refs("宝玉", "可卿"));
        assertTrue(en.path("positive_prompt").asText().contains("the story text wins"),
                en.path("positive_prompt").asText());
    }

    /**
     * ★ 2026-09-21 新增：复刻第 4 镜的真实形态（警幻只在 image3 里、剧情句没点名）
     * → payload 必须出现 {@code promptWarnings}（前端可展示）；**且不纠偏**：剧情原句原样保留。
     */
    @Test
    void conflictIsReportedNotFixed() {
        ObjectNode p = payload("电影感关键帧：宝玉回身问缘由，可卿在宝玉右侧，烛光摇曳");
        JsonNode shot = json("""
                {"shot_no":4,"layout":[{"subject":"宝玉","x":0.0,"y":0.0,"w":0.34,"h":1.0},
                           {"subject":"可卿","x":0.34,"y":0.0,"w":0.33,"h":1.0},
                           {"subject":"警幻","x":0.67,"y":0.0,"w":0.33,"h":1.0}]}
                """);
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉", "可卿", "警幻"));
        JsonNode warns = p.path("promptWarnings");
        assertTrue(warns.isArray() && warns.size() >= 1, p.toString());
        assertTrue(warns.toString().contains("警幻"), warns.toString());
        // 不纠偏：剧情原句仍在正词里（没有被改写或删除）
        assertTrue(p.path("positive_prompt").asText().contains("可卿在宝玉右侧"),
                p.path("positive_prompt").asText());
    }

    @Test
    void illegalBoxesAreIgnoredButNamingStays() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.1,"y":0.1,"w":0,"h":0.4},
                           {"subject":"可卿","x":-1,"y":0.1,"w":0.3,"h":0.4}]}
                """);
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉", "可卿"));
        assertNull(p.get("referenceRegions"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉") && pos.contains("Picture 2 (image2) = 可卿"), pos);
        assertFalse(pos.contains("x="), "非法框不应写进正词: " + pos);
    }

    @Test
    void noRefsLeavesEverythingUntouched() {
        ObjectNode p = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p, json("{}"), json("{}"), -1, JobService.RefCtx.empty());
        assertEquals("cinematic still of two figures", p.path("positive_prompt").asText());
        assertNull(p.get("referenceRegions"));
    }

    // ---------- P14 主体档案 / 设定年代 ----------

    /** 出图正词里的「Picture N = 主体」必须带上人物档案（性别/年龄/体态）——「宝玉被当女性」的解药。 */
    @Test
    void subjectTraitsAreWrittenIntoTheMapping() {
        ObjectNode p = payload("cinematic still of two figures standing together");
        JsonNode plan = json("""
                {"subjects":[{"name":"宝玉","kind":"person","gender":"male","age":"17",
                   "build":"清瘦","appearance":"大红箭袖"},
                 {"name":"可卿","kind":"person","gender":"female"}]}
                """);
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.05,"y":0.1,"w":0.3,"h":0.6},
                           {"subject":"可卿","x":0.6,"y":0.1,"w":0.3,"h":0.6}]}
                """);
        JobService.applyLayoutRegions(p, plan, shot, -1, refs("宝玉", "可卿"));
        String pos = p.path("positive_prompt").asText();
        // ★ 2026-09-21 简化：绑了参考图只写 性别/年龄（build/appearance 会与参考图抢话语权）
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉[gender male (男); age 17]"), pos);
        assertFalse(pos.contains("build 清瘦"), "参考图模式下不得再写体态：" + pos);
        assertFalse(pos.contains("appearance"), "参考图模式下不得再写外貌/服饰：" + pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿[gender female (女)]"), pos);
        // 档案句要求严格服从，且禁止把男性画成女性
        assertTrue(pos.contains("never render a male character as female"), pos);
        // 外貌/服饰/体态改为“以参考图为准”
        assertTrue(pos.contains("face/hair/costume/build always follow"), pos);
    }

    /** 没填属性的主体不能凭空造出方括号（否则模型会以为有约束）。 */
    @Test
    void subjectsWithoutTraitsGetNoBrackets() {
        ObjectNode p = payload("cinematic still of a figure");
        JobService.applyLayoutRegions(p, json("{\"subjects\":[{\"name\":\"宝玉\"}]}"), json("{}"), -1, refs("宝玉"));
        assertFalse(p.path("positive_prompt").asText().contains("[]"), p.path("positive_prompt").asText());
    }

    @Test
    void settingEraIsAppendedInPromptLanguageAndIsIdempotent() {
        ObjectNode zh = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳");
        JobService.applySetting(zh, json("{\"setting\":{\"era\":\"清代 · 康熙年间\"}}"));
        String z = zh.path("positive_prompt").asText();
        assertTrue(z.contains("【设定年代/世界观】清代 · 康熙年间"), z);
        assertTrue(zh.path("negative_prompt").asText().contains("现代服装"), zh.path("negative_prompt").asText());
        // 幂等：重复调用不再追加
        JobService.applySetting(zh, json("{\"setting\":{\"era\":\"清代 · 康熙年间\"}}"));
        assertEquals(z, zh.path("positive_prompt").asText());

        ObjectNode en = payload("cinematic still of a young man walking through a courtyard");
        JobService.applySetting(en, json("{\"setting\":{\"era\":\"Qing dynasty, Kangxi era\"}}"));
        assertTrue(en.path("positive_prompt").asText().contains("[Setting / era] Qing dynasty, Kangxi era"),
                en.path("positive_prompt").asText());
        assertTrue(en.path("negative_prompt").asText().contains("modern clothing"),
                en.path("negative_prompt").asText());
    }

    @Test
    void noSettingMeansPromptUntouched() {
        ObjectNode p = payload("cinematic still of a figure");
        JobService.applySetting(p, json("{}"));
        assertEquals("cinematic still of a figure", p.path("positive_prompt").asText());
        assertEquals("", p.path("negative_prompt").asText());
    }

    // ---------- 本镜主体（shots[].cast） ----------

    @Test
    void castDistinguishesUnsetFromExplicitEmptyShot() {
        assertNull(JobService.castOf(json("{}")));
        assertNull(JobService.castOf(null));
        assertEquals(List.of(), JobService.castOf(json("{\"cast\":[]}")));
        assertEquals(List.of("宝玉", "可卿"), JobService.castOf(json("{\"cast\":[\"宝玉\",\" 可卿 \",\"\"]}")));
        assertNull(JobService.castOf(json("{\"cast\":\"宝玉\"}")));
    }

    /**
     * ★ 2026-09-23 线上事故回归（用户报「第 4 镜关键帧又少了一个人，警幻的佛禅却穿在别人身上」）。
     *
     * <p>真因：**没有位置的主体被整个从「从左到右」清单里剔除** ⇒ 正词自述只有 N 个（有位置的）人，
     * 而尾句又写着「禁止合并或省掉任何一位」→ 模型按"只有两个人"画。
     * 实测第 4 镜 rev85（job …cc36da5b0020）的正词正是：
     * <pre>
     *   Picture 2 (image2) = 可卿                                   ← 没有位置
     *   画面从左到右依次为：宝玉(image1) → 警幻(image3)；              ← 只列了 2 个！
     * </pre>
     * 契约：**只要有名字的参考图主体，就必须出现在「从左到右」清单里**；
     * 没设位置的显式写「位置：未指定…但必须出现在画面中」，排序用槽位均分兜底。
     */
    @Test
    void 缺位置的主体不得被从左到右清单剔除() {
        ObjectNode p = payload("电影感关键帧：三人同框而立，烛影摇红");   // 中文正词 → 走中文措辞分支
        JsonNode plan = json("{\"subjects\":[{\"name\":\"宝玉\"},{\"name\":\"可卿\"},{\"name\":\"警幻\"}]}");
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.23,"y":0.18,"w":0.30,"h":0.45},
                           {"subject":"警幻","x":0.51,"y":0.07,"w":0.30,"h":0.45}]}
                """);

        JobService.applyLayoutRegions(p, plan, shot, -1, refs("宝玉", "可卿", "警幻"));
        String pos = p.path("positive_prompt").asText();

        // 三个主体都得在清单里（可卿曾经在这里消失）
        assertTrue(pos.contains("宝玉(image1)"), pos);
        assertTrue(pos.contains("可卿(image2)"), pos);
        assertTrue(pos.contains("警幻(image3)"), pos);
        // 顺序句必须同时点名三个人
        String line = pos.substring(pos.indexOf("画面从左到右依次为"));
        line = line.substring(0, line.indexOf('；'));
        assertTrue(line.contains("宝玉"), line);
        assertTrue(line.contains("可卿"), line);
        assertTrue(line.contains("警幻"), line);
        // 没位置的要显式说明，且仍然强调必须出现
        assertTrue(pos.contains("Picture 2 (image2) = 可卿（位置：未指定"), pos);
        assertTrue(pos.contains("必须出现在画面中"), pos);
        // 有位置的仍然写区间（没被这次改动破坏）
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉（位置：x 0.23–0.53"), pos);
    }
}
