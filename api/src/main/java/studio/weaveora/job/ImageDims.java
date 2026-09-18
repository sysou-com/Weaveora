package studio.weaveora.job;

import java.util.Map;

/**
 * 出图尺寸 = 画幅基础尺寸 × 「图片分辨率」档（长边目标像素），并 32 对齐。
 *
 * <p>纯函数、禁止 IO —— 单测覆盖（{@code ImageDimsTest}）。
 *
 * <p>为什么这样算（2026-09-18）：
 * <ul>
 *   <li>历史基础档是 16:9 → 1280×704（不是数学上的 16:9，而是 32 对齐后的实际档），
 *       所有存量关键帧都按它出 —— 所以默认值必须复现它，不能顺手"修正"比例。</li>
 *   <li>提高档位时**等比放缩**（长边 = 目标），画幅与存量一致；2560 档 → 2560×1408
 *       （若要精确 2560×1440，需要改画幅基础比例，属于另一件事）。</li>
 *   <li>32 对齐：ComfyUI/Qwen-Image 的潜空间按 8/16/32 下采样，非对齐会边缘出条纹。</li>
 * </ul>
 */
public final class ImageDims {

    /** 各画幅的基础尺寸（长边为 1280 档；与历史完全一致）。 */
    private static final Map<String, int[]> BASE = Map.of(
            "1:1", new int[]{1024, 1024},
            "3:2", new int[]{1152, 768},
            "2:3", new int[]{768, 1152},
            "16:9", new int[]{1280, 704},
            "9:16", new int[]{704, 1280});

    /** 基准长边（16:9 基础档 = 1280×704）；档位 = 该值 ⇒ 按 1× 出图（所有画幅完全等于历史尺寸）。 */
    public static final int BASE_LONG_SIDE = 1280;
    /** 默认档（= 历史行为：1× ）。 */
    public static final int DEFAULT_MAX_SIDE = BASE_LONG_SIDE;
    /** 允许区间（防手填把显存打爆）。 */
    public static final int MIN_MAX_SIDE = 512;
    public static final int MAX_MAX_SIDE = 4096;

    private static final int[] FALLBACK = new int[]{1024, 1024};

    private ImageDims() {
    }

    /**
     * @param aspect  画幅（16:9 / 9:16 / 1:1 / 3:2 / 2:3）；未知/null → 1:1
     * @param maxSide 档位：**以 16:9 基准长边为准的缩放**（1280=1×；1920=1.5×；2560=2×）；
     *                null / 越界 → {@link #DEFAULT_MAX_SIDE}（1×）
     *
     * <p>为什么不是“长边一定等于 maxSide”：各画幅的基础档长边本来就不同（1:1=1024、16:9=1280），
     * 若强行拉长边会把 1:1 从 1024 放大成 1280（测试实测踩到）—— 默认档必须对所有画幅都是“原尺寸”。
     */
    public static int[] of(String aspect, Integer maxSide) {
        int[] base = BASE.get(aspect == null ? "1:1" : aspect);
        if (base == null) {
            base = FALLBACK;
        }
        int target = normalize(maxSide);
        double k = (double) target / BASE_LONG_SIDE;
        if (Math.abs(k - 1.0) < 1e-9) {
            return new int[]{base[0], base[1]};
        }
        return new int[]{align32((int) Math.round(base[0] * k)),
                align32((int) Math.round(base[1] * k))};
    }

    /** 规范化档位（null/越界 → 默认；上限夹住）。 */
    public static int normalize(Integer maxSide) {
        if (maxSide == null || maxSide < MIN_MAX_SIDE) {
            return DEFAULT_MAX_SIDE;
        }
        return Math.min(maxSide, MAX_MAX_SIDE);
    }

    private static int align32(int v) {
        return Math.max(32, (int) Math.round(v / 32.0) * 32);
    }
}
