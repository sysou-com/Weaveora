package studio.weaveora.director;

/**
 * P13 主体定妆图提示词。
 *
 * <p>为什么需要它：直接把用户上传的随手图喂给生图模型，风格/构图/画幅都不可控，
 * 结果就是「关键帧和参考图对不上」。先按统一规范生成一张**标准角色设定图**，
 * 之后所有分镜都拿这张锚定，一致性才稳。
 *
 * <p>模板可按主体类型微调（人 / 载具装备 / 物件 / 场景），保持简洁、去掉干扰项。
 */
public final class SubjectPrompts {

    private SubjectPrompts() {
    }

    /** 组装该主体的定妆图提示词。 */
    public static String portraitPrompt(String name, String kind, int refCount) {
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
        String withName = String.format(base, name == null ? "" : name);
        if (refCount > 0) {
            withName += " Reference image(s): " + refCount + "; follow them for identity and costume.";
        }
        return withName;
    }
}
