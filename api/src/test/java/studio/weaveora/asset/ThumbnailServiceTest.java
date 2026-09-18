package studio.weaveora.asset;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §21 缩略图：滤镜串 / 命令拼装 / key 命名（纯函数，不跑 ffmpeg）。 */
class ThumbnailServiceTest {

    private static final Path IN = Path.of("in.png");
    private static final Path OUT = Path.of("out.webp");

    /** 逗号不转义会被 ffmpeg 当成滤镜分隔符 → 整条命令直接报错，是本模块唯一的高危语法点。 */
    @Test
    void scaleFilterEscapesCommasForFfmpeg() {
        String f = ThumbnailService.scaleFilter();
        assertTrue(f.contains("min(512\\,iw)"), f);
        assertTrue(f.contains("min(512\\,ih)"), f);
        // 未被转义的裸逗号只允许出现在不可能的位置：这里断言 min(...) 里没有裸逗号
        assertFalse(f.contains("min(512,iw)"), "min(512,iw) 的逗号必须转义成 \\, 否则 ffmpeg 解析失败: " + f);
    }

    /** 必须等比缩进 512×512 且不放大；否则小图会被拉大、大图会变形。 */
    @Test
    void scaleFilterKeepsAspectAndNeverUpscales() {
        String f = ThumbnailService.scaleFilter();
        assertTrue(f.contains("force_original_aspect_ratio=decrease"), f);
        // min(512, iw) 保证源比 512 小时不放大
        assertTrue(f.contains("min(512\\,iw)") && f.contains("min(512\\,ih)"), f);
    }

    /** 回归：曾经漏掉可执行文件，ProcessBuilder 把 "-y" 当程序名 → Cannot run program "-y"。 */
    @Test
    void commandStartsWithFfmpegBinary() {
        List<String> a = ThumbnailService.buildArgs("ffmpeg", IN, OUT, "image/png", false);
        assertEquals("ffmpeg", a.get(0), "第一个元素必须是 ffmpeg 可执行文件");
        assertEquals(OUT.toString(), a.get(a.size() - 1), "最后一个元素应为输出文件");
        assertEquals(IN.toString(), a.get(a.indexOf("-i") + 1), "-i 后面应紧跟输入文件");
        assertEquals("libwebp", a.get(a.indexOf("-c:v") + 1), "§21 要求 webp");
        assertEquals("webp", a.get(a.indexOf("-f") + 1));
        assertEquals("1", a.get(a.indexOf("-frames:v") + 1), "只取一帧");
        assertEquals(ThumbnailService.WEBP_QUALITY, a.get(a.indexOf("-quality") + 1));
    }

    /** 图片：不加 -ss；用 -vf 缩放。 */
    @Test
    void imageUsesScaleFilterAndNoSeek() {
        List<String> a = ThumbnailService.buildArgs("ffmpeg", IN, OUT, "image/png", false);
        assertFalse(a.contains("-ss"), "静态图不需要定位: " + a);
        assertEquals(ThumbnailService.scaleFilter(), a.get(a.indexOf("-vf") + 1));
        assertFalse(a.contains("-filter_complex"), a.toString());
    }

    /** 视频：必须带 -ss；且 -ss 在 -i 之前（输入定位，比输出定位快得多）。 */
    @Test
    void videoSeeksBeforeInput() {
        List<String> a = ThumbnailService.buildArgs("ffmpeg", IN, Path.of("out.webp"), "video/mp4", true);
        assertTrue(a.contains("-ss"), a.toString());
        assertTrue(a.indexOf("-ss") < a.indexOf("-i"), "-ss 必须在 -i 之前做输入定位: " + a);
        assertEquals(String.valueOf(ThumbnailService.VIDEO_SEEK_SEC), a.get(a.indexOf("-ss") + 1));
    }

    /**
     * 回归（实测踩过）：本项目所有 i2v / 对口型产物的<b>首帧是纯黑</b>
     * （同一文件：首帧 webp 324 bytes / YAVG=16，-ss 0.5 则是 4.9KB / YAVG=71）。
     * 所以默认取帧位置绝不能是 0。
     */
    @Test
    void videoSeekIsNotZeroBecauseFirstFrameIsBlack() {
        assertTrue(ThumbnailService.VIDEO_SEEK_SEC > 0,
                "首帧实测为纯黑，取帧位置必须大于 0");
    }

    /** 音频：走 showwavespic，不用 -vf（波形是 filter_complex 生成的一帧）。 */
    @Test
    void audioUsesWaveformFilter() {
        List<String> a = ThumbnailService.buildArgs("ffmpeg", IN, OUT, "audio/wav", false);
        assertEquals(ThumbnailService.waveFilter(), a.get(a.indexOf("-filter_complex") + 1));
        assertFalse(a.contains("-vf"), a.toString());
        assertTrue(ThumbnailService.waveFilter().contains("showwavespic="), ThumbnailService.waveFilter());
    }

    /** §21：同前缀 `_thumb.webp`（原扩展名换掉，不是简单追加）。 */
    @Test
    void thumbKeyFollowsSamePrefixRule() {
        assertEquals("ws/p/job/a_thumb.webp", ThumbnailService.thumbKeyFor("ws/p/job/a.png", "webp"));
        assertEquals("ws/p/job/a_thumb.jpg", ThumbnailService.thumbKeyFor("ws/p/job/a.mp4", "jpg"));
        // 没有扩展名 → 直接拼
        assertEquals("ws/p/job/a_thumb.webp", ThumbnailService.thumbKeyFor("ws/p/job/a", "webp"));
    }

    /** 目录名里带点不能被当成扩展名切掉（否则 key 会跑到别的目录去）。 */
    @Test
    void thumbKeyDoesNotMistakeDirectoryDotForExtension() {
        assertEquals("ws/v1.2/job/a_thumb.webp", ThumbnailService.thumbKeyFor("ws/v1.2/job/a", "webp"));
        assertEquals("ws/v1.2/ref/a_thumb.webp", ThumbnailService.thumbKeyFor("ws/v1.2/ref/a.png", "webp"));
    }

    /** 只有图/视频/音频才有缩略图；导出 zip 之类应直接回落原文件。 */
    @Test
    void supportsOnlyVisualAndAudioMimes() {
        assertTrue(ThumbnailService.supports("image/png"));
        assertTrue(ThumbnailService.supports("video/mp4"));
        assertTrue(ThumbnailService.supports("audio/wav"));
        assertFalse(ThumbnailService.supports("application/zip"));
        assertFalse(ThumbnailService.supports("application/octet-stream"));
        assertFalse(ThumbnailService.supports(null));
    }

    /** 缩略图 mime 由 key 后缀推断（正常 webp；降级路径是 jpg）。 */
    @Test
    void mimeInferredFromKeyExtension() {
        assertEquals("image/webp", ThumbnailService.mimeOf("webp"));
        assertEquals("image/jpeg", ThumbnailService.mimeOf("jpg"));
        assertEquals("image/jpeg", ThumbnailService.mimeOf("JPEG"));
    }

    /** 不支持的 mime 绝不能白跑一趟 ffmpeg。 */
    @Test
    void generateReturnsNullForUnsupportedMime() {
        ThumbnailService svc = new ThumbnailService("ffmpeg");
        assertNull(svc.generate("application/zip", ThumbnailService.streamOf(new byte[]{1, 2, 3})));
        assertNull(svc.generate("image/png", null));
    }
}
