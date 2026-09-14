package studio.weaveora.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C：「极端表情（嘴大张）」判定 —— 决定对口型是否自动把底片从 motion 片段改成静帧。
 *
 * <p>背景（2026-09-14《那宝玉恍恍惚惚》用户实测）：第 5 镜 action「…抓住宝玉将他拖下溪去，
 * 宝玉失声惊叫」、正词 `his mouth open in a terrified scream` —— 底片用 motion 片段 →
 * LatentSync 先合上嘴再按配音重开，嘴部区域大幅形变 = 画面被破坏
 * （实测底片 mouth_open：静帧 1.232 / 片段 1.238；正常闭嘴 0.03~0.17）。
 * 第 6 镜「梦醒…失声喊叫」也同样命中（静帧 0.589 / 片段 0.776）。
 * 这里只做文字侧判定（判多一次的代价是多走一次静帧底片，判漏的代价是一段坏画面 + 十几分钟 GPU）。
 */
class JobExpressionRiskTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode shot(String action, String positive, String... lines) {
        ObjectNode s = mapper.createObjectNode();
        s.put("shot_no", 6);
        s.put("action", action);
        s.put("positive_prompt", positive);
        if (lines.length > 0) {
            ArrayNode arr = s.putArray("narrations");
            for (String t : lines) {
                ObjectNode n = arr.addObject();
                n.put("kind", "dialogue");
                n.put("text", t);
            }
        }
        return s;
    }

    @Test
    void screamingShotIsExpressionRisk() {
        // 线上实例原文（第 5 镜，用户反馈「配口型时画面被破坏」的那一镜）
        assertTrue(JobService.expressionRisk(shot(
                "迷津内水声如雷，浪花暴涨，许多夜叉海鬼自黑水中探出，抓住宝玉将他拖下溪去，宝玉失声惊叫。",
                "low angle wide shot, black river erupting with white foam, ... dragging him down, "
                        + "his mouth open in a terrified scream, ash-grey sky")));
    }

    @Test
    void secondShotAlsoHit() {
        // 第 6 镜（宝玉失声喊叫）：**文字口径**同样命中（保守走静帧底片）；
        // 但实测它底片 mouth_open 只有 0.59~0.78（正常区间），所以 B 的硬拦不会命中它。
        assertTrue(JobService.expressionRisk(shot(
                "梦醒，宝玉在床上失声喊叫，袭人等众丫鬟忙上前搂住安抚，一遍遍低声唤他莫怕。",
                "a young man ... sweat on his forehead, eyes wide in terror, several young maidservants ...")));
    }

    @Test
    void englishMouthWideAlsoDetected() {
        assertTrue(JobService.expressionRisk(shot("", "close-up of a man with mouth wide open, screaming")));
    }

    @Test
    void normalDialogueIsNotRisk() {
        assertFalse(JobService.expressionRisk(shot(
                "宝玉与可卿在纱帐内低语温存，烛影摇红。",
                "medium shot, two figures behind a silk curtain, warm candle light",
                "二爷，莫出声", "这黑溪无桥，如何过去？")));
    }

    @Test
    void screamingLineInNarrationIsRisk() {
        assertTrue(JobService.expressionRisk(shot("", "medium shot of a person", "救命——他尖叫道")));
    }

    @Test
    void emptyShotIsNotRisk() {
        assertFalse(JobService.expressionRisk(shot("", "")));
    }
}
