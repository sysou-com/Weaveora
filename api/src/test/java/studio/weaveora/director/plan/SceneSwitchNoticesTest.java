package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景切换提示（只提示不拦）单测。
 *
 * <p>核心口径（用户 2026-09-17 明确纠正）：**同一画面里同时发生的多个动作不算"场景切换"**
 * —— 浪花、尖叫、水花、挣扎同属一个镜头的一个瞬间。只有「换地方」才提示。
 */
class SceneSwitchNoticesTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void 健康方案_无提示() {
        ObjectNode plan = video(12, 3);
        shot(plan, 1, 4, "Baoyu slowly raises the cup", "Baoyu raises the cup, warm candle light, indoor chamber");
        shot(plan, 2, 4, "she turns her head", "Keqing turns her head, silk curtain moves, indoor chamber");
        shot(plan, 3, 4, "he steps back", "Baoyu steps back, lantern flickers, indoor chamber");
        assertThat(SceneSwitchNotices.of(plan)).isEmpty();
    }

    @Test
    void 同画面共存的多个动作不算场景切换() {
        // 用户点名的反例：海边悬崖 + 浪花 + 尖叫 = 同一画面同时发生（水域+野外 共存），不得提示
        ObjectNode plan = video(5, 1);
        shot(plan, 1, 5,
                "the man struggles, screaming, splashing water everywhere",
                "a man on a cliff by the sea, waves splashing, rocks, screaming, underwater ghosts grabbing him");
        assertThat(SceneSwitchNotices.of(plan)).isEmpty();
    }

    @Test
    void 一镜内换场景_必须带转场用语才提示() {
        ObjectNode plan = video(10, 2);
        shot(plan, 1, 5, "he sinks",
                "underwater rocks and seabed, a man sinking slowly, bubbles");
        shot(plan, 2, 5, "cut to the ship",
                "cut to the deck of the ship on the sea, then we now see the indoor cabin, sailors shouting");
        List<String> notices = SceneSwitchNotices.of(plan);
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0)).contains("第 2 镜").contains("一镜内换场景");
    }

    @Test
    void 运镜多帧镜头跳过一镜内检查() {
        // 一条相机路径穿过多个空间是设计意图（多关键帧），不该提示
        ObjectNode plan = video(8, 1);
        ObjectNode s = shot(plan, 1, 8, "camera pushes through",
                "camera pushes through the doorway, cut to the indoor hall, palace interior");
        ArrayNode kfs = M.createArrayNode();
        for (int i = 0; i < 2; i++) {
            ObjectNode kf = M.createObjectNode();
            kf.put("positive_prompt", i == 0 ? "camera behind the man, street outside, city market"
                    : "cut to the indoor palace hall, throne room");
            kfs.add(kf);
        }
        s.set("keyframes", kfs);
        assertThat(SceneSwitchNotices.of(plan)).isEmpty();
    }

    @Test
    void 切换偏密_平均低于两秒() {
        ObjectNode plan = video(12, 8);
        for (int i = 1; i <= 8; i++) {
            shot(plan, i, 1.5, "Baoyu walks", "Baoyu walks forward, indoor chamber, candle light");
        }
        List<String> notices = SceneSwitchNotices.of(plan);
        assertThat(notices).anyMatch(n -> n.contains("切换偏密"));
        // 1.5s 恰好不算「过短」（阈值是 < 1.5）
        assertThat(notices).noneMatch(n -> n.contains("过短"));
    }

    @Test
    void 单镜过短_单独提示() {
        ObjectNode plan = video(12, 4);
        shot(plan, 1, 1.2, "flash", "a sudden light flashes, indoor chamber");
        shot(plan, 2, 3.6, "he walks", "Baoyu walks forward, indoor chamber");
        shot(plan, 3, 3.6, "she waits", "Keqing waits, indoor chamber");
        shot(plan, 4, 3.6, "they meet", "they meet, indoor chamber");
        List<String> notices = SceneSwitchNotices.of(plan);
        assertThat(notices).anyMatch(n -> n.contains("过短") && n.contains("第 1 镜"));
    }

    @Test
    void 图片方案不提示() {
        ObjectNode plan = M.createObjectNode();
        plan.put("mode", "image");
        plan.put("duration_sec", 12);
        assertThat(SceneSwitchNotices.of(plan)).isEmpty();
        assertThat(SceneSwitchNotices.of(null)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ObjectNode video(double durationSec, int shotCount) {
        ObjectNode plan = M.createObjectNode();
        plan.put("mode", "video");
        plan.put("duration_sec", durationSec);
        plan.set("shots", M.createArrayNode());
        plan.put("shot_count_hint", shotCount); // 仅记录，检测不依赖它
        return plan;
    }

    private static ObjectNode shot(ObjectNode plan, int no, double dur, String action, String positive) {
        ObjectNode s = M.createObjectNode();
        s.put("shot_no", no);
        s.put("duration_sec", dur);
        s.put("action", action);
        s.put("positive_prompt", positive);
        s.put("negative_prompt", "static, motionless");
        ((ArrayNode) plan.get("shots")).add(s);
        return s;
    }
}
