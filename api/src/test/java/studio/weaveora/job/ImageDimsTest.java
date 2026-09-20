package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 出图尺寸档位（图片分辨率）单测。 */
class ImageDimsTest {

    @Test
    void 默认档_完全复现历史尺寸() {
        assertThat(ImageDims.of("16:9", null)).containsExactly(1280, 704);
        assertThat(ImageDims.of("16:9", 1280)).containsExactly(1280, 704);
        assertThat(ImageDims.of("1:1", null)).containsExactly(1024, 1024);
        assertThat(ImageDims.of("9:16", null)).containsExactly(704, 1280);
        assertThat(ImageDims.of("3:2", null)).containsExactly(1152, 768);
    }

    @Test
    void 提高档位_等比放大且32对齐() {
        // 档位 = 以 16:9 基准（1280）计的缩放：1920 = 1.5×，2560 = 2×
        assertThat(ImageDims.of("16:9", 1920)).containsExactly(1920, 1056);
        assertThat(ImageDims.of("1:1", 1920)).containsExactly(1536, 1536);
        assertThat(ImageDims.of("9:16", 1920)).containsExactly(1056, 1920);
        int[] big = ImageDims.of("16:9", 2560);
        assertThat(big).containsExactly(2560, 1408);
        assertThat(Math.max(big[0], big[1])).isEqualTo(2560);
        for (int v : big) {
            assertThat(v % 32).isZero();
        }
        assertThat(ImageDims.of("9:16", 2560)).containsExactly(1408, 2560);
        assertThat(ImageDims.of("1:1", 2560)).containsExactly(2048, 2048);
        // ★ 2026-09-20 新增 1392 档（官方 ~1MP 工作预算；16:9 实际落 1408×768 = 1.08MP）
        assertThat(ImageDims.of("16:9", 1392)).containsExactly(1408, 768);
        assertThat(ImageDims.of("9:16", 1392)).containsExactly(768, 1408);
        assertThat(ImageDims.of("3:2", 1392)).containsExactly(1248, 832);
        assertThat(ImageDims.of("1:1", 1392)).containsExactly(1120, 1120);
        // ★ 2026-09-20 新增 1664 档：16:9 正好落在 Qwen 官方训练桶 1664×928（1.54MP）
        assertThat(ImageDims.of("16:9", 1664)).containsExactly(1664, 928);
        assertThat(ImageDims.of("9:16", 1664)).containsExactly(928, 1664);
        assertThat(ImageDims.of("3:2", 1664)).containsExactly(1504, 992);
        assertThat(ImageDims.of("1:1", 1664)).containsExactly(1344, 1344);
        // ★ 2026-09-20 下拉改成「实际尺寸」口径：1408 档 → 1408×768（与旧 1392 档出图完全一致）
        assertThat(ImageDims.of("16:9", 1408)).containsExactly(1408, 768);
        assertThat(ImageDims.of("9:16", 1408)).containsExactly(768, 1408);
        assertThat(ImageDims.of("1:1", 1408)).containsExactly(1120, 1120);
    }

    @Test
    void 非法值与未知画幅_安全兜底() {
        assertThat(ImageDims.of("16:9", 0)).containsExactly(1280, 704);      // 0 → 默认(1×)
        assertThat(ImageDims.of("16:9", -5)).containsExactly(1280, 704);     // 负数 → 默认
        assertThat(ImageDims.of("16:9", 99)).containsExactly(1280, 704);     // 太小 → 默认
        assertThat(ImageDims.of("99:1", 1920)).containsExactly(1536, 1536);  // 未知画幅 → 1:1 再按档位缩放
        assertThat(ImageDims.of(null, 1920)).containsExactly(1536, 1536);
        assertThat(ImageDims.of("16:9", 99999)).containsExactly(4096, 2240); // 超上限夹到 4096（k=3.2）
    }
}
