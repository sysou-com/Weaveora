package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导演方案规则校验（§10.3）——纯函数，禁止 IO / 依赖注入，单测覆盖。
 * 校验不通过只返回问题清单，不改动 plan。
 */
public final class DirectorPlanValidator {

    /** §32.1 系统级默认负面词（模板/LLM 可覆盖追加，不删除）。 */
    public static final List<String> DEFAULT_NEGATIVE = List.of(
            "text", "watermark", "logo", "subtitle", "caption", "signature",
            "blurry", "lowres", "deformed", "extra limbs", "badly drawn",
            "jpeg artifacts", "ugly", "nsfw");

    /** 单镜正向提示词长度约束（§10.3）。 */
    public static final int SHOT_POSITIVE_MIN = 20;
    public static final int SHOT_POSITIVE_MAX = 1200;

    private DirectorPlanValidator() {
    }

    /** 校验导演方案；返回问题列表（空 = 通过）。mode 为目标模式，durationSec 为目标时长（视频必填）。 */
    public static List<String> validate(JsonNode plan, String mode, BigDecimal durationSec) {
        List<String> problems = new ArrayList<>();
        if (plan == null || !plan.isObject()) {
            problems.add("plan 必须是 JSON 对象");
            return problems;
        }
        String planMode = text(plan, "mode");
        if (planMode != null && !planMode.equals(mode)) {
            problems.add("plan.mode(" + planMode + ") 与目标模式(" + mode + ")不一致");
        }
        if (isBlank(text(plan, "title"))) {
            problems.add("缺少 title");
        }
        if (isBlank(text(plan, "logline"))) {
            problems.add("缺少 logline（一句话画面/主题）");
        }

        if ("video".equals(mode)) {
            validateVideo(plan, durationSec, problems);
        } else {
            if (isBlank(text(plan, "positive_prompt"))) {
                problems.add("缺少 positive_prompt");
            } else {
                int len = text(plan, "positive_prompt").length();
                if (len < SHOT_POSITIVE_MIN) {
                    problems.add("positive_prompt 过短（" + len + " < " + SHOT_POSITIVE_MIN + "）");
                }
            }
            if (isBlank(text(plan, "negative_prompt"))) {
                problems.add("缺少 negative_prompt");
            }
        }
        return problems;
    }

    private static void validateVideo(JsonNode plan, BigDecimal targetDuration, List<String> problems) {
        if (targetDuration == null) {
            problems.add("视频项目缺少目标时长 durationSec");
        }
        BigDecimal planDuration = decimal(plan, "duration_sec");
        if (planDuration == null) {
            problems.add("缺少 duration_sec");
        } else if (targetDuration != null && diff(planDuration, targetDuration).compareTo(new BigDecimal("0.5")) > 0) {
            problems.add("duration_sec(" + planDuration + ")与目标(" + targetDuration + ")偏差超过 0.5s");
        }
        if (!plan.hasNonNull("aspect_ratio") || plan.get("aspect_ratio").asText().isBlank()) {
            problems.add("缺少 aspect_ratio");
        }
        if (!plan.hasNonNull("script") || !plan.get("script").isObject()) {
            problems.add("缺少 script");
        }
        if (!plan.hasNonNull("audio") || !plan.get("audio").isObject()) {
            problems.add("缺少 audio");
        }
        if (!plan.hasNonNull("edit_plan") || !plan.get("edit_plan").isObject()) {
            problems.add("缺少 edit_plan");
        }

        JsonNode shots = plan.get("shots");
        if (shots == null || !shots.isArray() || shots.isEmpty()) {
            problems.add("视频方案缺少 shots");
            return;
        }
        int n = shots.size();
        if (planDuration != null) {
            // 禁止 shot 数量 > ceil(duration_sec / 1.5)（§10.3）
            int maxShots = planDuration.divide(new BigDecimal("1.5"), 0, RoundingMode.CEILING).intValueExact();
            if (n > maxShots) {
                problems.add("镜头数(" + n + ")超过上限 " + maxShots + "（ceil(duration/1.5)）");
            }
        }
        Set<Integer> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            JsonNode shot = shots.get(i);
            if (shot == null || !shot.isObject()) {
                problems.add("shots[" + i + "] 不是对象");
                continue;
            }
            if (!shot.hasNonNull("shot_no") || !seen.add(shot.get("shot_no").asInt())) {
                problems.add("shots[" + i + "] 缺少唯一 shot_no");
            }
            BigDecimal d = decimal(shot, "duration_sec");
            if (d == null) {
                problems.add("shots[" + i + "] 缺少 duration_sec");
            } else {
                sum = sum.add(d);
                if (d.compareTo(new BigDecimal("10")) > 0) {
                    problems.add("shots[" + i + "] 单镜 " + d + "s 超过 10s（§30 #25 单镜上限）");
                }
            }
            String pos = text(shot, "positive_prompt");
            if (isBlank(pos)) {
                problems.add("shots[" + i + "] 缺少 positive_prompt");
            } else if (pos.length() < SHOT_POSITIVE_MIN || pos.length() > SHOT_POSITIVE_MAX) {
                problems.add("shots[" + i + "].positive_prompt 长度 " + pos.length()
                        + " 越界（" + SHOT_POSITIVE_MIN + "–" + SHOT_POSITIVE_MAX + "）");
            }
            if (isBlank(text(shot, "negative_prompt"))) {
                problems.add("shots[" + i + "] 缺少 negative_prompt");
            }
        }
        if (planDuration != null && diff(sum, planDuration).compareTo(new BigDecimal("0.5")) > 0) {
            problems.add("镜头时长总和(" + sum + ")≠duration_sec(" + planDuration + ")，偏差 >0.5s");
        }
        if (targetDuration != null && diff(sum, targetDuration).compareTo(new BigDecimal("0.5")) > 0) {
            problems.add("镜头时长总和(" + sum + ")与项目目标(" + targetDuration + ")偏差 >0.5s");
        }
    }

    /** 把额外词合并进现有负面词（按逗号/空格分词去重，保持既有顺序）。 */
    public static String mergeNegative(String existing, List<String> extra) {
        Set<String> seen = new LinkedHashSet<>();
        if (existing != null) {
            for (String t : existing.split("[,，]")) {
                String s = t.trim();
                if (!s.isEmpty()) seen.add(s);
            }
        }
        if (extra != null) {
            for (String t : extra) {
                if (t != null && !t.isBlank() && !seen.contains(t.trim())) {
                    seen.add(t.trim());
                }
            }
        }
        return String.join(", ", seen);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.isNumber() ? v.decimalValue() : null;
    }

    private static BigDecimal diff(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs();
    }
}
