package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景切换提示（**只提示不拦**，2026-09-17 用户口径）——纯函数，禁止 IO，单测覆盖。
 *
 * <p>背景：用户口径「一镜里动作密不是问题（浪花、尖叫、水花本来就在同一画面同时发生），
 * 真正会让观众出戏的是<strong>场景切换过多</strong>」。所以本类只检测两类问题，且**只返回提示文案**，
 * 不参与 {@link DirectorPlanValidator} 的硬校验（硬校验已有「镜头数 ≤ ceil(时长/1.5)」）。
 *
 * <ol>
 *   <li><b>一镜内换场景</b>：单镜文本里出现了 ≥2 个<strong>不同</strong>场景类别，
 *       <b>并且</b>带有明确的转场用语（{@code cut to} / {@code 切到} / {@code 转场}…）。
 *       要求「转场用语」是为了不误报——「海边悬崖 + 浪花 + 尖叫」属于同画面共存，不算换场景。</li>
 *   <li><b>切换偏密</b>：平均每镜时长 &lt; {@link #MIN_AVG_SHOT_SEC} 秒，或存在单镜 &lt; {@link #MIN_SHOT_SEC} 秒。
 *       合法但很跳（如 12s 挤 8 镜 = 1.5s/镜）。</li>
 * </ol>
 *
 * <p>运镜型镜头（{@code keyframes} ≥2 帧）**跳过**第 1 类检查：跨帧出现不同场景是它的设计意图
 * （一条相机路径穿过多个空间），由用户在多帧里分别确认。
 */
public final class SceneSwitchNotices {

    /** 平均每镜时长下限（秒）：低于它提示「切换偏密」。 */
    public static final BigDecimal MIN_AVG_SHOT_SEC = new BigDecimal("2.0");
    /** 单镜时长下限（秒）：低于它单独提示。 */
    public static final BigDecimal MIN_SHOT_SEC = new BigDecimal("1.5");

    /**
     * 场景类别 → 关键词（中英混合，粗粒度）。
     *
     * <p>刻意**粗**：同一大类内的共现（海 + 浪 + 水花）不算换场景，
     * 只有跨大类（水 ↔ 室内 / 野外地貌 ↔ 城市）才可能算。
     */
    private static final Map<String, List<String>> PLACES = Map.of(
            "室内", List.of("室内", "屋内", "房间", "卧房", "书房", "厅", "堂", "宫殿", "宫", "殿", "阁",
                    "船舱", "帐篷", "酒馆", "客栈", "地宫", "洞内", "走廊", "楼梯",
                    "indoor", "indoors", "room", "chamber", "hall", "palace", "cabin", "tent", "tavern",
                    "corridor", "hallway", "interior"),
            "水域", List.of("海", "海面", "海底", "江", "河", "湖", "溪", "浪", "潮", "水花", "水面", "水下",
                    "雨", "岸边", "河岸", "海滩", "沙滩", "码头",
                    "sea", "ocean", "seabed", "underwater", "river", "lake", "stream", "wave", "tide",
                    "splash", "waters", "shore", "beach", "dock", "pier"),
            "野外", List.of("山", "山脉", "森林", "树林", "林间", "草原", "沙漠", "荒野", "峡谷", "崖", "悬崖",
                    "岩", "礁", "荒野", "田野", "雪原", "冰原",
                    "mountain", "forest", "jungle", "woods", "desert", "wilderness", "canyon", "cliff",
                    "rock", "reef", "field", "snowfield", "glacier"),
            "城市", List.of("街", "街道", "城市", "城", "广场", "桥", "集市", "市场", "巷", "屋顶", "城墙",
                    "城门", "庭院", "院子",
                    "street", "city", "town", "square", "bridge", "market", "alley", "rooftop", "plaza",
                    "courtyard", "gate"));

    /**
     * 明确转场用语（只有叙事上"从这里到那里"的说法才算）。
     *
     * <p>刻意不收 {@code then} / {@code 接着} / {@code 忽然} 这类**时序/语气**词——
     * 它们在一镜内的连续动作里很常见，会把「浪花同时炸开」误判成换场景。
     */
    private static final List<String> SWITCH_MARKERS = List.of(
            "cut to", "cuts to", "cutting to", "switches to", "switch to", "transition to", "transitions to",
            "we now see", "now in the", "next scene", "scene changes to",
            "切到", "切换到", "切至", "转场", "镜头一转", "画面一转", "画面转到", "转到另一", "另一处");

    private SceneSwitchNotices() {
    }

    /** 返回可直接展示的中文提示（空 = 无需提示）。只对 video 方案生效。 */
    public static List<String> of(JsonNode plan) {
        List<String> out = new ArrayList<>();
        if (plan == null || !plan.isObject()) {
            return out;
        }
        if (!"video".equals(plan.path("mode").asText(""))) {
            return out;
        }
        JsonNode shots = plan.get("shots");
        if (shots == null || !shots.isArray() || shots.isEmpty()) {
            return out;
        }
        inShotSwitches(shots, out);
        pacing(shots, plan, out);
        return out;
    }

    /** 第 1 类：一镜内换场景（多关键帧镜头跳过）。 */
    private static void inShotSwitches(JsonNode shots, List<String> out) {
        for (JsonNode shot : shots) {
            if (shot == null || !shot.isObject()) {
                continue;
            }
            JsonNode kfs = shot.get("keyframes");
            boolean multiFrame = kfs != null && kfs.isArray() && kfs.size() >= 2;
            if (multiFrame) {
                continue; // 运镜型：跨帧不同场景是设计意图
            }
            String text = join(shot.path("action").asText(""), shot.path("positive_prompt").asText(""));
            if (text.isBlank()) {
                continue;
            }
            Set<String> cats = placesIn(text);
            if (cats.size() < 2 || !hasSwitchMarker(text)) {
                continue;
            }
            out.add("第 " + shot.path("shot_no").asInt(0) + " 镜疑似**一镜内换场景**（"
                    + String.join(" → ", cats) + "）：一镜里换场景会让观众出戏，建议拆成两镜，"
                    + "或把换场放在切点上。");
        }
    }

    /** 第 2 类：切换偏密 / 单镜过短。 */
    private static void pacing(JsonNode shots, JsonNode plan, List<String> out) {
        int n = shots.size();
        BigDecimal sum = BigDecimal.ZERO;
        List<String> tooShort = new ArrayList<>();
        for (JsonNode shot : shots) {
            BigDecimal d = decimal(shot, "duration_sec");
            if (d == null) {
                continue;
            }
            sum = sum.add(d);
            if (d.compareTo(MIN_SHOT_SEC) < 0) {
                tooShort.add("第 " + shot.path("shot_no").asInt(0) + " 镜 " + d.stripTrailingZeros().toPlainString() + "s");
            }
        }
        BigDecimal total = decimal(plan, "duration_sec");
        if (total == null || total.signum() <= 0) {
            total = sum;
        }
        if (sum.signum() > 0 && total.signum() > 0) {
            BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            if (avg.compareTo(MIN_AVG_SHOT_SEC) < 0) {
                out.add("镜头切换偏密：" + n + " 镜 / " + total.stripTrailingZeros().toPlainString()
                        + "s，平均 " + avg.stripTrailingZeros().toPlainString() + " 秒/镜（低于 "
                        + MIN_AVG_SHOT_SEC.stripTrailingZeros().toPlainString()
                        + " 秒观众来不及看清）：建议减少镜头数或加长成片时长。");
            }
        }
        if (!tooShort.isEmpty()) {
            out.add("有镜头过短：" + String.join("、", tooShort) + "（低于 "
                    + MIN_SHOT_SEC.stripTrailingZeros().toPlainString() + " 秒）：单镜太短会像闪帧，建议加长或合并。");
        }
    }

    /** 文本命中的场景类别（按 {@link #PLACES} 顺序稳定输出）。 */
    private static Set<String> placesIn(String text) {
        String low = text.toLowerCase();
        Set<String> hit = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : PLACES.entrySet()) {
            for (String kw : e.getValue()) {
                if (low.contains(kw.toLowerCase())) {
                    hit.add(e.getKey());
                    break;
                }
            }
        }
        return hit;
    }

    private static boolean hasSwitchMarker(String text) {
        String low = text.toLowerCase();
        for (String m : SWITCH_MARKERS) {
            if (low.contains(m.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.isEmpty() ? y : (y.isEmpty() ? x : x + " " + y);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        return v.isNumber() ? v.decimalValue() : null;
    }
}
