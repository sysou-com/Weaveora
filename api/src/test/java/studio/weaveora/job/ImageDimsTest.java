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
