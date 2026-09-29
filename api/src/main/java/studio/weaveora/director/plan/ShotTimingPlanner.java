package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性「镜头时长规划」（P0，2026-09-29）。
 *
 * <p><b>为什么要有它</b>：目标 UX 是「用户只关注剧本」——镜头时长必须由
 * <b>台词/旁白时长 + 当前引擎能力</b>确定性地推导，而不是交给 LLM 自由发挥
 * （LLM 算时间既不准、又会在校验失败时反复重试）。
 *
 * <p><b>口径（用户 2026-09-29 裁定）</b>：
 * <ol>
 *   <li><b>音频先行</b>：有台词的镜 = {@code 台词时长 + 尾余量}；无台词的镜沿用导演给的目标时长。</li>
 *   <li><b>引擎上限</b>：单镜不得超过当前出片引擎「单次生成上限」（LTX-2.5 = 5.04s / Wan2.2 = 7.56s，
 *       由 {@code motion-frames-max ÷ native-fps} 算出）。超过时按 {@code oversize_policy} 处理。</li>
 *   <li><b>stretch</b>（默认，用户裁定）：超上限只生成 1 次（按上限帧数），成片阶段本地重定时拉伸到
 *       镜头需要的时长 —— 不多花钱、不切段。见 {@code ConcatService} 的 {@code stretchFactor}。</li>
 *   <li><b>总长归一</b>：把与项目目标时长的差额**只补给无台词镜**（呼吸），绝不压缩台词；
 *       实在放不下才动台词镜并在 {@code notes} 里如实说明。</li>
 * </ol>
 *
 * <p>纯函数、无 IO、无 Spring 依赖 —— 便于单测（§27）。
 */
public final class ShotTimingPlanner {

    /** 超上限策略：本地重定时拉伸（默认，1 次调用）。 */
    public static final String POLICY_STRETCH = "stretch";
    /** 超上限策略：按上限生成，配音溢出到下一镜（不切段）。 */
    public static final String POLICY_OVERFLOW = "overflow";
    /** 超上限策略：切成 N 段分别生成（每多一段多一次推理）。 */
    public static final String POLICY_SEGMENT = "segment";

    /** 时间轴模式：镜长跟着配音走。 */
    public static final String MODE_AUDIO_FIRST = "audio_first";

    private static final double STEP = 0.1;
    private static final int MAX_STEPS = 400;

    private ShotTimingPlanner() {
    }

    /**
     * 引擎能力（只取规划用得到的部分）。
     *
     * @param maxShotSec 单次生成上限（秒）—— 来自 {@code motionFramesMax / nativeFps}
     * @param minShotSec 单镜最短（秒）
     * @param tailSec    镜头尾部呼吸余量（秒，默认 0.3）
     * @param fps        引擎原生帧率（用于最短分段折算）
     * @param minFrames  引擎最小帧数（默认 32）
     */
    public record Caps(double maxShotSec, double minShotSec, double tailSec, int fps, int minFrames) {

        public static Caps of(double maxShotSec, int nativeFps) {
            double max = maxShotSec > 0 ? maxShotSec : 5.0;
            int fps = Math.max(1, nativeFps);
            double min = Math.max(0.6, Math.round(32.0 / fps * 10) / 10.0);
            return new Caps(max, min, 0.3, fps, 32);
        }

        /** 最短分段时长（秒）——低于它就把末段并回上一段。 */
        public double minSegSec() {
            return Math.max(0.5, (double) minFrames / Math.max(1, fps));
        }
    }

    /** 一镜的规划输入：导演给的目标时长 + 该镜台词总时长（0 = 无台词）。 */
    public record ShotInput(int shotNo, double durationSec, double speechSec) {

        public boolean hasSpeech() {
            return speechSec > 0.05;
        }
    }

    public record Segment(int index, double startSec, double durationSec) {
    }

    public record ShotTiming(int shotNo, double fromSec, double toSec, double speechSec,
                             List<Segment> segments, boolean stretch, String warn) {
    }

    public record Result(List<ShotTiming> timings, double totalSec, double targetSec, List<String> notes) {
    }

    /**
     * 规划全片镜头时长。
     *
     * @param shots    按 shot_no 升序的输入
     * @param targetSec 项目目标时长（≤0 = 不归一）
     * @param policy   超上限策略（{@link #POLICY_STRETCH} / {@link #POLICY_OVERFLOW} / {@link #POLICY_SEGMENT}）
     */
    public static Result plan(List<ShotInput> shots, double targetSec, Caps caps, String policy) {
        List<String> notes = new ArrayList<>();
        if (shots == null || shots.isEmpty()) {
            return new Result(List.of(), 0, Math.max(0, targetSec), List.of("方案里没有镜头"));
        }
        String pol = normalizePolicy(policy);
        int n = shots.size();
        double[] dur = new double[n];
        List<Segment>[] segs = new List[n];
        boolean[] stretch = new boolean[n];
        String[] warn = new String[n];

        for (int i = 0; i < n; i++) {
            ShotInput s = shots.get(i);
            double speech = Math.max(0, s.speechSec());
            double need = speech > 0 ? speech + caps.tailSec() : s.durationSec();
            need = Math.max(need, caps.minShotSec());
            need = round1(need);
            dur[i] = need;
            if (need <= caps.maxShotSec() + 0.05) {
                segs[i] = List.of(new Segment(0, 0, need));
                continue;
            }
            switch (pol) {
                case POLICY_OVERFLOW -> {
                    dur[i] = caps.maxShotSec();
                    segs[i] = List.of(new Segment(0, 0, dur[i]));
                    warn[i] = "超引擎上限 " + fmt(caps.maxShotSec()) + "s，按上限生成、配音顺排溢出到下一镜";
                }
                case POLICY_SEGMENT -> {
                    segs[i] = splitSegments(need, caps);
                    warn[i] = "超引擎上限，切成 " + segs[i].size() + " 段生成（每多一段多一次推理）";
                }
                default -> {
                    stretch[i] = true;
                    segs[i] = List.of(new Segment(0, 0, caps.maxShotSec()));
                    warn[i] = fmt(need) + "s 超引擎上限 " + fmt(caps.maxShotSec())
                            + "s → 只生成 1 次，成片阶段本地重定时拉伸";
                }
            }
        }

        normalizeToTarget(shots, dur, caps, targetSec, notes);

        List<ShotTiming> out = new ArrayList<>();
        double cursor = 0;
        double total = 0;
        for (int i = 0; i < n; i++) {
            double d = round1(dur[i]);
            out.add(new ShotTiming(shots.get(i).shotNo(), round1(cursor), round1(cursor + d),
                    round1(shots.get(i).speechSec()), segs[i] == null ? List.of(new Segment(0, 0, d)) : segs[i],
                    stretch[i], warn[i]));
            cursor += d;
            total += d;
        }
        if (targetSec > 0 && Math.abs(total - round1(targetSec)) > 0.5) {
            notes.add("镜头总长 " + fmt(total) + "s 与目标 " + fmt(targetSec)
                    + "s 偏差 >0.5s（台词时长本身已超/不足，已按可调范围尽量归位）");
        }
        return new Result(out, round1(total), round1(targetSec), notes);
    }

    /** 超上限切段：每段 ≤ 上限，末段过短并入上一段。 */
    static List<Segment> splitSegments(double need, Caps caps) {
        double max = caps.maxShotSec();
        int k = (int) Math.ceil(need / max - 1e-6);
        k = Math.max(2, k);
        List<Segment> out = new ArrayList<>();
        double cursor = 0;
        for (int i = 0; i < k; i++) {
            double remain = need - cursor;
            double d = (i == k - 1) ? remain : Math.min(max, remain);
            out.add(new Segment(i, round1(cursor), round1(d)));
            cursor += d;
        }
        if (out.size() > 1 && out.get(out.size() - 1).durationSec() < caps.minSegSec()) {
            Segment last = out.remove(out.size() - 1);
            Segment prev = out.remove(out.size() - 1);
            out.add(new Segment(prev.index(), prev.startSec(),
                    round1(prev.durationSec() + last.durationSec())));
        }
        return out;
    }

    /**
     * 把差额只补给**无台词镜**；无台词镜到边界后再动台词镜（并在 notes 里说明）。
     */
    private static void normalizeToTarget(List<ShotInput> shots, double[] dur, Caps caps,
                                          double targetSec, List<String> notes) {
        if (targetSec <= 0) {
            return;
        }
        double sum = 0;
        for (double d : dur) {
            sum += d;
        }
        double delta = round1(targetSec) - round1(sum);
        if (Math.abs(delta) < 0.05) {
            return;
        }
        int n = shots.size();
        // 候选顺序：无台词镜优先（呼吸余量），其次台词镜
        List<Integer> silent = new ArrayList<>();
        List<Integer> talking = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            (shots.get(i).hasSpeech() ? talking : silent).add(i);
        }
        List<Integer> order = new ArrayList<>(silent);
        order.addAll(talking);
        double sign = delta > 0 ? 1 : -1;
        int steps = (int) Math.round(Math.abs(delta) / STEP);
        int applied = 0;
        for (int step = 0; step < steps && step < MAX_STEPS; step++) {
            boolean moved = false;
            for (int idx : order) {
                double lo = lowerBound(shots.get(idx), caps);
                double hi = upperBound(shots.get(idx), dur[idx], caps);
                double next = round1(dur[idx] + sign * STEP);
                if (next >= lo - 1e-6 && next <= hi + 1e-6) {
                    dur[idx] = next;
                    moved = true;
                    applied++;
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        int left = steps - applied;
        if (left > 0) {
            notes.add("目标时长差 " + fmt(Math.abs(delta)) + "s 无法全部归位（还有 " + fmt(left * STEP)
                    + "s）：台词总时长与目标严重不匹配，请调整目标时长或增删台词");
        }
    }

    private static double lowerBound(ShotInput s, Caps caps) {
        return s.hasSpeech() ? round1(s.speechSec() + caps.tailSec()) : caps.minShotSec();
    }

    private static double upperBound(ShotInput s, double cur, Caps caps) {
        // 已超上限（stretch）的镜可以继续长（拉伸倍率变大）；否则最多到引擎上限
        return Math.max(cur, caps.maxShotSec());
    }

    public static String normalizePolicy(String policy) {
        if (POLICY_OVERFLOW.equals(policy)) {
            return POLICY_OVERFLOW;
        }
        if (POLICY_SEGMENT.equals(policy)) {
            return POLICY_SEGMENT;
        }
        return POLICY_STRETCH;
    }

    /**
     * 把规划结果写回方案 JSON（就地保存用）：{@code shots[].duration_sec / segments / stretch}
     * 与 {@code edit_plan.timing_mode / oversize_policy / video_model_max_sec / tail_sec}。
     */
    public static void applyTo(ObjectNode plan, Result result, Caps caps, String policy) {
        if (plan == null || result == null) {
            return;
        }
        ObjectNode edit = plan.hasNonNull("edit_plan") && plan.get("edit_plan").isObject()
                ? (ObjectNode) plan.get("edit_plan") : plan.putObject("edit_plan");
        edit.put("timing_mode", MODE_AUDIO_FIRST);
        edit.put("oversize_policy", normalizePolicy(policy));
        edit.put("video_model_max_sec", round2(caps.maxShotSec()));
        edit.put("tail_sec", round1(caps.tailSec()));
        Map<Integer, ShotTiming> byNo = new LinkedHashMap<>();
        for (ShotTiming t : result.timings()) {
            byNo.put(t.shotNo(), t);
        }
        JsonNode arr = plan.get("shots");
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode shot : arr) {
            if (shot == null || !shot.isObject()) {
                continue;
            }
            ShotTiming t = byNo.get(shot.path("shot_no").asInt(Integer.MIN_VALUE));
            if (t == null) {
                continue;
            }
            ObjectNode s = (ObjectNode) shot;
            if (!s.hasNonNull("target_sec")) {
                s.put("target_sec", round1(s.path("duration_sec").asDouble(t.toSec())));
            }
            s.put("duration_sec", round1(t.toSec()));
            ArrayNode segs = s.putArray("segments");
            for (Segment seg : t.segments()) {
                ObjectNode o = segs.addObject();
                o.put("index", seg.index());
                o.put("start_sec", round2(seg.startSec()));
                o.put("duration_sec", round2(seg.durationSec()));
            }
            s.put("stretch", t.stretch());
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
