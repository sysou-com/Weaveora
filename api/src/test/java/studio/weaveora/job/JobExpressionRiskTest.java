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
 * <p>背景（2026-09-14《那宝玉恍恍惚惚》第 6 镜，用户实测）：action「梦醒，宝玉失声喊叫」、
 * 正词「eyes wide in terror」，底片用的是 motion 片段 → LatentSync 先合上嘴再按配音重开，
 * 嘴部区域大幅形变 = 画面被破坏。这里只做文字侧判定（判多一次的代价是多走一次静帧底片，
 * 判漏的代价是一段坏画面 + 十几分钟 GPU）。
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
        // 线上实例原文（第 6 镜）
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
