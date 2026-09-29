package studio.weaveora.shared;

/**
 * 提示词语言纯度：检测「中英混排」。
 *
 * <p>为什么需要（2026-09-29 用户裁定 + 实测）：FLUX.2 的文本编码器是 Mistral-3（英文向），
 * 中英混排会让模型两头听；线上第 4 镜关键帧正是「英文风格前缀 + 中文正文 + 中文自动拼装块」，
 * 同 seed 下中文臂出「2 个宝玉」，纯英文臂（错位修正后）出了唯一一张过线样张。
 * 用户要求：**自动拼装部分跟随提示词语言；最终提示词若中英混排要提示 + 一键 AI 优化 + 可保存回分镜**。
 *
 * <p>判据（有意做得保守，避免误报）：
 * <ul>
 *   <li>汉字数 ≥ {@link #CJK_MIN} <b>且</b> 拉丁字母数 ≥ {@link #LATIN_MIN} ⇒ 混排；</li>
 *   <li>所以「英文提示词里带中文角色名（{@code Baoyu (宝玉)}）」「中文提示词里带 {@code image 1} / {@code CGI}」
 *       都**不算**混排 —— 那是专有名词/槽位标记，不是两套语言；</li>
 *   <li>「Cinematic film still, 35mm film, 清代太虚幻境，云雾缭绕…」这类**整段英文风格前缀 + 整段中文正文**
 *       会稳定命中（前缀 ≈44 个字母 ≥ 20）。</li>
 * </ul>
 */
public final class PromptLang {

    /** 汉字数门槛：低于它就不认为存在"一整段中文"。 */
    public static final int CJK_MIN = 8;
    /** 拉丁字母数门槛：低于它就不认为存在"一整段英文"（如 image1 / CGI / 角色拼音）。 */
    public static final int LATIN_MIN = 20;

    private PromptLang() {
    }

    public static int cjkCount(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    public static int latinLetterCount(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                n++;
            }
        }
        return n;
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)        // 基本区
                || (c >= 0x3400 && c <= 0x4DBF)    // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF);   // 兼容表意
    }

    /** 中英混排：两侧都够长。 */
    public static boolean isMixed(String s) {
        return cjkCount(s) >= CJK_MIN && latinLetterCount(s) >= LATIN_MIN;
    }

    /**
     * 主导语言：{@code "zh"} / {@code "en"} / {@code ""}（空或无法判定）。
     *
     * <p>用于「一键 AI 优化」的默认目标语言：汉字 ≥ 拉丁字母 ⇒ 中文，否则英文。
     */
    public static String dominant(String s) {
        int cjk = cjkCount(s);
        int latin = latinLetterCount(s);
        if (cjk == 0 && latin == 0) {
            return "";
        }
        return cjk >= latin ? "zh" : "en";
    }

    /** 诊断用一行摘要（日志/接口回包都用它，便于定位）。 */
    public static String describe(String s) {
        return "zh=" + cjkCount(s) + " en=" + latinLetterCount(s)
                + (isMixed(s) ? " MIXED" : " ok");
    }
}
