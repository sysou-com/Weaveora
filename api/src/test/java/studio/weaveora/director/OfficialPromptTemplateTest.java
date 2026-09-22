package studio.weaveora.director;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 「使用模板」（2026-09-22 用户要求）回归防线：官方口径的提示词模板必须真的随请求带给 LLM。
 *
 * <p>背景：官方口径明确（图生视频 = <b>运动 + 运镜</b>；图像编辑 = 1–3 张图 + 一条指令 +
 * 「图N」按送入顺序指代 + 短句不冗余 + 冲突拆步），但 LLM 每次自由发挥都会漂回老路
 * （把静态构图/外观/人物档案也写进视频正词）。这两份模板文件是本次的「写法硬约束」载体，
 * 所以文件被改名/被删/被清空时必须立刻红。
 */
class OfficialPromptTemplateTest {

    @Test
    void imageScopeCarriesStillTemplateOnly() {
        String t = DirectorService.officialTemplateBlock("image", null);
        assertTrue(t.contains("出图正词"), "image 范围必须带出图模板: " + t);
        assertTrue(t.contains("prompt_template_still.md"), t);
        assertFalse(t.contains("prompt_template_motion.md"), "image 范围不该带图生视频模板: " + t);
        assertFalse(t.contains("图生视频正词"), t);
    }

    @Test
    void shotScopeCarriesBothTemplates() {
        String t = DirectorService.officialTemplateBlock("shot", null);
        assertTrue(t.contains("prompt_template_motion.md"), "shot 范围必须带图生视频模板: " + t);
        assertTrue(t.contains("prompt_template_still.md"), "shot 范围必须带出图模板（keyframes）: " + t);
        assertTrue(t.contains("图生视频正词"), t);
        assertTrue(t.contains("positive_prompt"), t);
        assertTrue(t.contains("keyframes[].positive_prompt"), t);
    }

    @Test
    void scopeIsOptionalAndDefaultsToShot() {
        assertTrue(DirectorService.officialTemplateBlock(null, null).contains("prompt_template_motion.md"));
        assertTrue(DirectorService.officialTemplateBlock("  ", null).contains("prompt_template_motion.md"));
        // 大小写不敏感
        assertFalse(DirectorService.officialTemplateBlock("IMAGE", null).contains("图生视频正词"));
    }

    @Test
    void templatesStateTheOfficialFormulas() {
        // ★ 2026-09-22 根治：必须带上「字段↔模板对应」规则 —— 分镜级 positive_prompt 被
        //   「出图」与「图生视频」两种用途共用；单帧镜必须按出图口径写（否则关键帧没有场景可依附，
        //   用户实测：生成出两张并排的上身像、完全不体现剧情）。
        String motion = DirectorService.officialTemplateBlock("shot", null);
        assertTrue(motion.contains("字段与模板的对应"), motion);
        assertTrue(motion.contains("单帧镜"), "必须写明单帧镜按出图口径写: " + motion);
        assertTrue(motion.contains("运镜镜"), motion);
        // 官方原话：图生视频公式 = 运动 + 运镜
        assertTrue(motion.contains("运动 + 运镜"), "图生视频模板必须写明官方公式: " + motion);
        assertTrue(motion.contains("固定镜头"), "官方要求：不想镜头变化就写「固定镜头」");
        String still = DirectorService.officialTemplateBlock("image", null);
        assertTrue(still.contains("图1"), "出图模板必须写明「图N」指代口径");
        assertTrue(still.contains("一条"), "出图模板必须写明「一条编辑指令」");
        // 两份模板都不得改变输出语言（语言只由 lang 决定）
        assertTrue(motion.contains("不改变输出语言"), motion);
        assertTrue(still.contains("不改变输出语言"), still);
    }
}
