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
                    + "jpeg artifacts, ugly, 3d render, cgi, 文字, 水印, 标志, 字幕, 多人, 畸形, 低分辨率, "
                    // ★ 2026-09-19（用户要求）：定妆照底色必须统一为白 —— 背景一带场景/环境，
                    //   会连带把关键帧的色调带偏。实测同一主体：“白底 + 人物占画面大”与
                    //   “带环境氛围 + 人物变小”两版作参考时，归一化后脸宽 157px vs 61px。
                    + "场景背景, 环境背景, 复杂背景, 渐变背景, 风景, 房间, 建筑, 树木, 天空, "
                    + "scenery background, environment, complex background, gradient background, "
                    // ★ 2026-09-19（用户要求）：定妆照要“主体比例更大、空白区域更小”——
                    //   定妆照人脸是出图的唯一身份锚，而**脸在画面里占多高**直接决定它作参考时的强度：
                    //   归一化到 1MP 后，脸占画幅高 25% → 约 150px；占 10% → 约 61px（实测，后者三主体同框时最先被牺牲）。
                    //   所以把“小人 + 大片空白”直接写进负词。
                    + "远景, 全身, 人物过小, 主体过小, 大片空白, 过多留白, 画面空旷, "
                    + "wide shot, full body shot, tiny subject, small subject, "
                    + "lots of empty space, excessive blank space, empty frame";

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
                    "标准设定图（三视图感）：%s 整体外形清晰、结构细节准确、纯白色背景、均匀布光、写实电影质感；"
                            + "严格保持参考图中的外形/涂装/比例特征。只画这一个主体，不要文字、不要边框、不要多主体。";
            case "object" ->
                    "标准物品设定图：%s 居中、纯白色背景、均匀布光、细节清晰、写实质感；"
                            + "严格保持参考图中的形状/材质/颜色。只画这一个物品，不要文字、不要边框。";
            case "scene" ->
                    "标准场景设定图：%s 全景构图、无人物、光线自然、层次清晰；"
                            + "严格保持参考图中的空间结构与氛围。只画这一个场景，不要文字、不要边框。";
            default ->
                    // ★ 2026-09-19（用户要求）：底色统一为**纯白色**。原来只写“纯色背景”，
                    //   模型可以自己选暗色/暖色底 → 各版定妆照底色不一致 → 关键帧色调跟着漂。
                    "标准角色设定图：%s 正面半身（腰以上，头肩到胸口）、中性表情、纯白色背景（纯白 #FFFFFF）、"
                            // ★ 注意：portraitCore 的返回值会过 String.format(..., name)，
                            //   所以正文里任何字面量百分号必须写成 %%。
                            + "人物占满画面、主体占比大，头顶距画面顶端不超过 5%%、四周不留大片空白、脸部大而清晰，"
                            + "上方服装与配饰（发饰/首饰/领口/衣襟）清晰可辨、柔和均匀布光、写实电影质感；"
                            + "严格保持参考图的人物特征（五官/发型/服装/年龄感）。"
                            + "只画这一个角色，不要文字、不要边框、不要多人物、不要任何环境/场景元素。";
        };
        return String.format(base, name == null ? "" : name);
    }

    /** 定妆图默认负向词（单一真源）。 */
    public static String portraitNegativePrompt() {
        return PORTRAIT_NEGATIVE;
    }
}
