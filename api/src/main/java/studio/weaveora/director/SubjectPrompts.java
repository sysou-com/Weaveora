package studio.weaveora.director;

/**
 * P13 主体定妆图提示词。
 *
 * <p>为什么需要它：直接把用户上传的随手图喂给生图模型，风格/构图/画幅都不可控，
 * 结果就是「关键帧和参考图对不上」。先按统一规范生成一张**标准角色设定图**，
 * 之后所有分镜都拿这张锚定，一致性才稳。
 *
 * <p>模板可按主体类型微调（人 / 载具装备 / 物件 / 场景），保持简洁、去掉干扰项。
 *
 * <p>★ 2026-09-16（用户要求）：定妆图生成前要像分镜一样**弹出正/负向提示词让用户改**，
 * 所以这份模板同时是「默认值」的唯一真源（后端 {@code createPortraitJob} 与前端弹框都读它），
 * 避免前后端各写一份漂移。
 */
public final class SubjectPrompts {

    /** 定妆图默认负向词（与分镜负词口径一致：文字/水印/多人物/畸形/低清）。 */
    public static final String PORTRAIT_NEGATIVE =
            "text, watermark, logo, subtitle, caption, signature, multiple people, two people, "
                    + "deformed face, deformed hands, extra limbs, extra fingers, lowres, blurry, "
                    + "jpeg artifacts, ugly, 3d render, cgi, 文字, 水印, 标志, 字幕, 多人, 畸形, 低分辨率";

    private SubjectPrompts() {
    }

    /** 组装该主体的定妆图正向提示词（默认值）。 */
    public static String portraitPrompt(String name, String kind, int refCount) {
        return portraitPrompt(name, kind, refCount, null);
    }

    /**
     * 组装该主体的定妆图正向提示词（默认值）。
     *
     * ★ P14（2026-09-16 用户要求）：把「主体设定」（性别/年龄/身高/体态/性格/外貌）拼进定妆提示词 ——
     * 定妆照是所有分镜的**唯一错定图**，如果它本身就把性别/年龄段画错，后面每一镜都会错。
     */
    public static String portraitPrompt(String name, String kind, int refCount,
                                       studio.weaveora.director.plan.PlanSubjects.Traits traits) {
        String core = portraitCore(name, kind);
        if (traits != null && !traits.isEmpty()) {
            core += "\n角色设定（必须体现在画面里）：" + traits.describe(true) + "。";
            String gz = traits.genderZh();
            if (!gz.isEmpty()) {
                core += "这个角色是「" + gz + "」，"
                        + ("男".equals(gz) ? "严重禁止画成女性、不要女性化的五官与发型服装。"
                                           : "女".equals(gz) ? "严重禁止画成男性、不要男性化的五官与体型。"
                                                             : "请按参考图与上面的描述如实表现性别特征。");
            }
        }
        if (refCount > 0) {
            core += " Reference image(s): " + refCount
                    + "; follow them for identity, hairstyle and costume (keep the same character, do not redesign).";
        }
        return core;
    }

    /** 主语模板（不含参考图计数句），供前端弹框展示/微调。 */
    public static String portraitCore(String name, String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        String base = switch (k) {
            case "vehicle" ->
                    "标准设定图（三视图感）：%s 整体外形清晰、结构细节准确、纯色背景、均匀布光、写实电影质感；"
                            + "严格保持参考图中的外形/涂装/比例特征。只画这一个主体，不要文字、不要边框、不要多主体。";
            case "object" ->
                    "标准物品设定图：%s 居中、纯色背景、均匀布光、细节清晰、写实质感；"
                            + "严格保持参考图中的形状/材质/颜色。只画这一个物品，不要文字、不要边框。";
            case "scene" ->
                    "标准场景设定图：%s 全景构图、无人物、光线自然、层次清晰；"
                            + "严格保持参考图中的空间结构与氛围。只画这一个场景，不要文字、不要边框。";
            default ->
                    "标准角色设定图：%s 正面半身、中性表情、纯色背景、全身服装与配饰清晰可辨、"
                            + "柔和均匀布光、写实电影质感；严格保持参考图的人物特征（五官/发型/服装/年龄感）。"
                            + "只画这一个角色，不要文字、不要边框、不要多人物。";
        };
        return String.format(base, name == null ? "" : name);
    }

    /** 定妆图默认负向词（单一真源）。 */
    public static String portraitNegativePrompt() {
        return PORTRAIT_NEGATIVE;
    }
}
