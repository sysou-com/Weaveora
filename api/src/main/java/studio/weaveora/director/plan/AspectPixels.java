package studio.weaveora.director.plan;

import java.util.Map;

/** 画幅 → 像素（§11.5 图片默认值；64 的倍数，SD/FLUX 友好）。 */
public final class AspectPixels {

    public record Dim(int width, int height) {
    }

    private static final Map<String, Dim> IMAGE = Map.of(
            "1:1", new Dim(1024, 1024),
            "3:2", new Dim(1216, 832),
            "2:3", new Dim(832, 1216),
            "16:9", new Dim(1344, 768),
            "9:16", new Dim(768, 1344));

    private AspectPixels() {
    }

    public static Dim forImage(String aspect) {
        Dim d = IMAGE.get(aspect == null ? "" : aspect);
        return d != null ? d : IMAGE.get("16:9");
    }
}
