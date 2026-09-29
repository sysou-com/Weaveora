package studio.weaveora.director.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐镜配乐策略 → 配乐段落（P1，2026-09-29）。
 *
 * <p><b>用户口径</b>：「根据剧情需要，**不一定每镜都要配乐**」。
 * 导演 LLM 逐镜给 {@code shots[].music}：
 * <ul>
 *   <li>{@code none} —— 该镜不铺配乐（对白密集、需要留白/静默的镜）</li>
 *   <li>{@code bed} —— 情绪底噪（默认）</li>
 *   <li>{@code hit} —— 转场/高潮的强调段（同一 mood，音量更高）</li>
 * </ul>
 *
 * <p>本类把**连续同策略**的镜头合成一段 cue；{@code none} 段不产 cue —— 于是混音里那段就是安静的。
 * 若方案里已有显式的 {@code audio.music[]}（用户手拉时间轴 / AI 一键配乐），**不要调用本类**。
 *
 * <p>纯函数、无 IO，单测覆盖。
 */
public final class MusicCuePlanner {

    public static final String NONE = "none";
    public static final String BED = "bed";
    public static final String HIT = "hit";

    /** 强调段（hit）相对底噪的音量增益（dB）——不另生成曲子，只是抬音量。 */
    public static final double HIT_GAIN_DB = -6.0;
    public static final double BED_GAIN_DB = -10.5;

    private MusicCuePlanner() {
    }

    /** 一镜的时间窗 + 曲策略。 */
    public record ShotMusic(int shotNo, double startSec, double endSec, String policy) {
    }

    public record Cue(double startSec, double endSec, String policy) {
        public double gainDb() {
            return HIT.equals(policy) ? HIT_GAIN_DB : BED_GAIN_DB;
        }
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return BED;
        }
        return switch (raw.trim().toLowerCase()) {
            case NONE, "silent", "silence", "无", "静音" -> NONE;
            case HIT, "stinger", "高潮", "强调" -> HIT;
            default -> BED;
        };
    }

    /**
     * 根据逐镜策略生成配乐段落。
     *
     * @param totalSec 成片总时长（截断用；≤0 不截断）
     * @return 只含 bed/hit 的段落（none 被跳过）
     */
    public static List<Cue> plan(List<ShotMusic> shots, double totalSec) {
        List<Cue> out = new ArrayList<>();
        String cur = null;
        double start = 0;
        double end = 0;
        for (ShotMusic s : shots == null ? List.<ShotMusic>of() : shots) {
            String p = normalize(s.policy());
            if (NONE.equals(p)) {
                if (cur != null) {
                    out.add(new Cue(start, end, cur));
                    cur = null;
                }
                continue;
            }
            if (cur != null && cur.equals(p)) {
                end = s.endSec();
                continue;
            }
            if (cur != null) {
                out.add(new Cue(start, end, cur));
            }
            cur = p;
            start = s.startSec();
            end = s.endSec();
        }
        if (cur != null) {
            out.add(new Cue(start, end, cur));
        }
        if (totalSec > 0) {
            List<Cue> clipped = new ArrayList<>();
            for (Cue c : out) {
                double s = Math.max(0, c.startSec());
                double e = Math.min(totalSec, c.endSec());
                if (e > s + 0.05) {
                    clipped.add(new Cue(s, e, c.policy()));
                }
            }
            return clipped;
        }
        return out;
    }
}
