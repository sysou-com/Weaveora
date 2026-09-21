package studio.weaveora.job;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧情句 ↔ 位置总控（区域框）冲突检测：**只提示，不纠偏**。
 *
 * <p>★ 2026-09-21 产品决策（用户裁定）：**画面方位以剧情句为准**，位置总控的区域框降级为
 * 「剧情句没写方位时的兜底」。这**撤销**了 2026-09-16 那条「剧情句的方位若与本清单冲突，
 * 一律以本清单为准」的强制覆盖 —— 当时的真因是第 4 镜文案写「警幻居后景」而位置框是
 * x=0.65，两套方位直接矛盾、模型只能猜；现在改成「按剧情执行 + 把矛盾告诉用户」，
 * 由用户自己决定改文案还是改框。
 *
 * <p><b>为什么是提示而不是自动改写</b>：自动纠偏会把用户的剧情意志改掉（她本来就该在后面），
 * 而用户需要知道的是「模型为什么没按你的框画 / 为什么少了一个人」。所以这里只做**字面可解释**的
 * 检测，命中就给一句中文提示（写进 job payload 的 {@code promptWarnings} + WARN 日志）。
 *
 * <p>检测三类（全部字面匹配，不做语义推断，宁可多提示也不漏）：
 * <ol>
 *   <li><b>点名缺失</b>：参考图清单里有、剧情句里没点名的角色 —— 实测第 4 镜就是这种形态
 *       （警幻的定妆照在 image3、剧情也写了"自后追来"，但扩写后的剧情句主体是宝玉/可卿，
 *       模型于是只画两个人）；</li>
 *   <li><b>左右相反</b>：剧情句断言「A 在 B 的左侧/右侧」，而区域框的横向排序恰好相反；</li>
 *   <li><b>纵深 vs 占满画高</b>：剧情句把某角色写成「居后景/后方/远处…」（纵深关系），
 *       而该角色的区域框是「占满画高」（= 同平面等身）—— 按剧情执行意味着她会被画小、被挡,
 *       甚至不出现，这正是"少一个人"的机制。</li>
 * </ol>
 */
public final class PromptConflictDetector {

    private PromptConflictDetector() {
    }

    /** 一个参考图槽位：主体名 + 区域框（x, y, w, h 归一化）。 */
    public record Slot(String name, double[] box) {

        /** 横向中心（无框时 NaN）。 */
        double midX() {
            return box == null ? Double.NaN : box[0] + box[2] / 2.0;
        }

        /** 是否「占满画高」（顶到画底 ⇒ 与其他人同一平面、等身）。 */
        boolean fullHeight() {
            return box != null && box[1] <= 0.02 && (box[1] + box[3]) >= 0.98;
        }

        /** 横向区间文本，如 {@code 0.67–1.00}。 */
        String range() {
            return box == null ? "（未设）" : String.format("%.2f–%.2f", box[0], box[0] + box[2]);
        }
    }

    /**
     * 纵深/前后关系词：模型据此把角色画到后面、变小、被前景挡住。
     * **长词优先**（否则「居后景」会被「后景」抢先命中，提示里引用的词就不是用户原话）。
     */
    private static final String[] DEPTH_WORDS = longestFirst(new String[]{
            "居后景", "自后", "从后", "自后方", "在后景", "在远处", "走在前面", "落在后面",
            "后景", "前景", "背景", "后方", "身后", "居后", "远处", "远端",
            "背对", "背向", "越肩", "遮挡", "深处", "纵深", "在前面", "在后面",
            "over the shoulder", "in the distance", "in front of", "background", "foreground", "behind",
    });

    /** 按长度降序（同长度保持原顺序）——保证报告的是最长/最具体的那个词。 */
    private static String[] longestFirst(String[] words) {
        String[] out = words.clone();
        java.util.Arrays.sort(out, java.util.Comparator.comparingInt(String::length).reversed());
        return out;
    }

    /**
     * 检测冲突。
     *
     * @param plotText 剧情句原文（= 拼进正词时 {@code cur}，可能已含【设定年代】段，无妨）
     * @param slots    参考图槽位（主体名 + 区域框），顺序 = image1、image2…
     * @return 中文提示列表；无冲突返回空列表
     */
    public static List<String> detect(String plotText, List<Slot> slots) {
        List<String> out = new ArrayList<>();
        if (slots == null || slots.isEmpty()) {
            return out;
        }
        String text = flat(plotText);
        if (text.isEmpty()) {
            return out;
        }
        missingSubject(text, slots, out);
        leftRightConflict(text, slots, out);
        depthAgainstFullHeight(text, slots, out);
        return out;
    }

    /** ① 参考图清单里有、剧情句里没点名 → 很可能不画（"少一个人"的实测机制）。 */
    private static void missingSubject(String text, List<Slot> slots, List<String> out) {
        List<String> missing = new ArrayList<>();
        List<String> slotsText = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String name = slots.get(i).name();
            if (blank(name) || text.contains(flat(name))) {
                continue;
            }
            missing.add(name);
            slotsText.add("image" + (i + 1));
        }
        if (!missing.isEmpty()) {
            out.add("剧情句里没有点名 " + String.join("、", missing) + "（但参考图清单里有 " + String.join("、", slotsText)
                    + "）——模型很可能不画他/她，实测「第 4 镜只出现 2 个人」就是这么来的。"
                    + "本次以剧情为准、**不会自动补人**：要么在剧情里点名，要么去掉该参考图。");
        }
    }

    /** ② 剧情句断言的左右关系与区域框排序相反。 */
    private static void leftRightConflict(String text, List<Slot> slots, List<String> out) {
        Set<String> done = new LinkedHashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            for (int j = 0; j < slots.size(); j++) {
                if (i == j) {
                    continue;
                }
                Slot a = slots.get(i);
                Slot b = slots.get(j);
                if (blank(a.name()) || blank(b.name())) {
                    continue;
                }
                Boolean aLeftOfB = claim(text, a.name(), b.name());
                if (aLeftOfB == null) {
                    continue;
                }
                double ma = a.midX();
                double mb = b.midX();
                if (Double.isNaN(ma) || Double.isNaN(mb)) {
                    continue;
                }
                boolean actuallyLeft = ma < mb;
                if (aLeftOfB == actuallyLeft) {
                    continue;
                }
                if (!done.add(a.name() + ">" + b.name())) {
                    continue;
                }
                out.add("剧情句说「" + a.name() + " 在 " + b.name() + " 的" + (aLeftOfB ? "左" : "右") + "侧」，"
                        + "而位置总控里 " + a.name() + " 在" + (actuallyLeft ? "左" : "右") + "边（x "
                        + a.range() + " vs " + b.range() + "）。本次**以剧情为准**（区域框只作兜底）——"
                        + "要按位置总控执行，请改剧情文案或改框。");
            }
        }
    }

    /** ③ 剧情句的纵深描写 vs 区域框的「占满画高」（同平面等身）。 */
    private static void depthAgainstFullHeight(String text, List<Slot> slots, List<String> out) {
        Set<String> seen = new LinkedHashSet<>();
        for (String[] hit : depthHits(text, slots)) {
            Slot s = byName(slots, hit[0]);
            if (s == null || !s.fullHeight() || !seen.add(hit[0])) {
                continue;
            }
            out.add("剧情句把「" + s.name() + "」写成「" + hit[1] + "」（纵深/前后关系），"
                    + "而位置总控要其「同平面、占满画高」（x " + s.range() + "）。本次**以剧情为准**："
                    + "他/她会被画到更远处、更小，甚至被前景挡住或不出现 —— 若要其与其他人等身并列，"
                    + "请改剧情文案（去掉纵深描述）。");
        }
    }

    /**
     * 剧情句是否断言「x 在 y 的左侧」。无断言返回 {@code null}。
     * 只认最朴素的两种语序（{@code x…y…左/右} 与 {@code y…x…左/右}），不跨句推断。
     */
    static Boolean claim(String text, String x, String y) {
        String px = Pattern.quote(flat(x));
        String py = Pattern.quote(flat(y));
        Matcher m = Pattern.compile(px + ".{0,8}?" + py + "的?(左|右)").matcher(text);
        if (m.find()) {
            return "左".equals(m.group(1));
        }
        Matcher r = Pattern.compile(py + ".{0,8}?" + px + "的?(左|右)").matcher(text);
        if (r.find()) {
            return !"左".equals(r.group(1));   // y 在 x 左侧 ⇒ x 在 y 右侧
        }
        return null;
    }

    /**
     * 剧情句里出现的纵深词，归给「它前面最近的那个主体」。
     * 返回 {@code [主体名, 词]} 列表（同一词出现多次会重复，调用方自行去重）。
     */
    static List<String[]> depthHits(String text, List<Slot> slots) {
        List<String[]> out = new ArrayList<>();
        for (String w : DEPTH_WORDS) {
            int from = 0;
            while (true) {
                int at = text.indexOf(w, from);
                if (at < 0) {
                    break;
                }
                String owner = null;
                int best = -1;
                for (Slot s : slots) {
                    if (blank(s.name())) {
                        continue;
                    }
                    int p = text.lastIndexOf(flat(s.name()), at);
                    if (p > best) {
                        best = p;
                        owner = s.name();
                    }
                }
                if (owner != null && at - best <= 40) {
                    out.add(new String[]{owner, w});
                }
                from = at + w.length();
            }
        }
        return out;
    }

    private static Slot byName(List<Slot> slots, String name) {
        for (Slot s : slots) {
            if (s.name() != null && s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 去掉所有空白（中文文案里常混全角空格/换行，字面匹配前先抹平）。 */
    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
