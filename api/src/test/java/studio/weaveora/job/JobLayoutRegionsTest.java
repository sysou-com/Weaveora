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
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (upper-center, x=0.62, y=0.10, box 0.30x0.60)"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿 (upper-left, x=0.10, y=0.20, box 0.25x0.55)"), pos);

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

        // 第 2 帧（index=1）有自己的 layout → 帧级优先
        JobService.applyLayoutRegions(p, json("{}"), shot, 1, refs("宝玉"));
        assertTrue(p.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (upper-left, x=0.05, y=0.05, box 0.30x0.40)"),
                p.path("positive_prompt").asText());

        // 第 1 帧（index=0）没设 → 退回镜级
        ObjectNode p0 = payload("cinematic still of two figures");
        JobService.applyLayoutRegions(p0, json("{}"), shot, 0, refs("宝玉"));
        assertTrue(p0.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (middle-center, x=0.50, y=0.50, box 0.20x0.20)"),
                p0.path("positive_prompt").asText());
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
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (upper-left, x=0.10, y=0.20, box 0.30x0.50)"), pos);
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
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉 (upper-left, x=0.05, y=0.05, box 0.40x0.90)"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿 (upper-right, x=0.70, y=0.10, box 0.28x0.80)"), pos);
    }

    @Test
    void facePickFallsBackToDefaultBoxCentredOnTheClick() {
        ObjectNode p = payload("cinematic still of two figures");
        JsonNode shot = json("{\"lipsync_targets\":{\"宝玉\":{\"x\":0.30,\"y\":0.40}}}");
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉"));
        // 点选只有坐标 → 以点为中心默认框 0.30×0.45 = x 0.15 / y 0.20
        assertTrue(p.path("positive_prompt").asText()
                .contains("Picture 1 (image1) = 宝玉 (upper-left, x=0.15, y=0.20, box 0.30x0.45)"),
                p.path("positive_prompt").asText());
    }

    // ---------- 语言一致性 ----------

    @Test
    void chinesePromptGetsChineseSentence() {
        ObjectNode p = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳，烟雾弥漫");
        JsonNode shot = json("{\"layout\":[{\"subject\":\"宝玉\",\"x\":0.05,\"y\":0.1,\"w\":0.4,\"h\":0.8}]}");
        JobService.applyLayoutRegions(p, json("{}"), shot, -1, refs("宝玉"));
        String pos = p.path("positive_prompt").asText();
        assertTrue(pos.contains("参考图与主体对应（按送入顺序）"), pos);
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉（左上，x=0.05，y=0.10，框 0.40x0.80）"), pos);
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
        // ③ 身份与档案保留（性别/身高是「同框比例」的判据）
        assertTrue(pos.contains("宝玉[性别 男 male；身高 165]"), pos);
        assertTrue(pos.contains("警幻[性别 女 female；身高 162]"), pos);
        // ④ 构图以关键帧为准 + 同框身高比例
        assertTrue(pos.contains("【以关键帧为构图基准】"), pos);
        assertTrue(pos.contains("同框身高比例按档案：宝玉 165、警幻 162"), pos);
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

        assertTrue(still.path("positive_prompt").asText().contains("box 0.30x0.98"), still.path("positive_prompt").asText());
        assertTrue(still.path("positive_prompt").asText().contains("positions and box sizes given here always win"));
        assertEquals(1, still.get("referenceRegions").size());

        assertFalse(clip.path("positive_prompt").asText().contains("box 0.30x0.98"));
        assertTrue(clip.path("positive_prompt").asText().contains("[Keyframe is authoritative for composition]"),
                clip.path("positive_prompt").asText());
        assertNull(clip.get("referenceRegions"));
    }

    /** 没填身高（或只有一个主体）时不得编造身高句，但构图基准句照写。 */
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
     * ★ 2026-09-16 新增：位置清单必须带「以本清单为准」的覆盖句。
     *
     * <p>为什么（用户实测第 4 镜反复“位置错位”）：镜文案由 LLM 写成「宝玉在前景中央…可卿在宝玉右侧…
     * 警幻居后景」，而用户在「位置总控」里设的框是 x=0.24 / x=0.06 / x=0.65 —— 两套方位直接矛盾，
     * 模型只能猜。现在明确告知：方位以区域清单为最终裁定。
     */
    @Test
    void positionListCarriesAuthoritativeOverrideClause() {
        ObjectNode zh = payload("电影感关键帧：宝玉与可卿并肩而立，烛光摇曳");
        JsonNode shot = json("""
                {"layout":[{"subject":"宝玉","x":0.24,"y":0.08,"w":0.19,"h":0.76},
                           {"subject":"可卿","x":0.06,"y":0.06,"w":0.17,"h":0.78}]}
                """);
        JobService.applyLayoutRegions(zh, json("{}"), shot, -1, refs("宝玉", "可卿"));
        assertTrue(zh.path("positive_prompt").asText().contains("位置以本清单为准"),
                zh.path("positive_prompt").asText());

        ObjectNode en = payload("cinematic still of two figures standing together");
        JobService.applyLayoutRegions(en, json("{}"), shot, -1, refs("宝玉", "可卿"));
        assertTrue(en.path("positive_prompt").asText().contains("[Authoritative placement]"),
                en.path("positive_prompt").asText());
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
        assertTrue(pos.contains("Picture 1 (image1) = 宝玉[gender male (男)"), pos);
        assertTrue(pos.contains("age 17"), pos);
        assertTrue(pos.contains("build 清瘦"), pos);
        assertTrue(pos.contains("Picture 2 (image2) = 可卿[gender female (女)]"), pos);
        // 档案句要求严格服从，且禁止把男性画成女性
        assertTrue(pos.contains("never render a male character as female"), pos);
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
}
