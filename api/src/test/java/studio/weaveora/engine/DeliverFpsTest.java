package studio.weaveora.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成片帧率归一规则（P2，2026-09-23）。
 *
 * <p>为什么要有这条规则：`ConcatService.encodeSegment()` / `ExportService` 会用成片帧率做
 * `ffmpeg fps=<成片帧率>`。**成片帧率必须能被出片引擎的原生帧率整除**，否则 ffmpeg 只能用
 * 复制帧把时间轴拉齐 —— LTX-2.5 出的是 24fps、计划里却是 32（DirectorService 的历史默认），
 * 24→32 不是整数倍 ⇒ 成片顿挫。本测试把这条口径锁住。
 */
class DeliverFpsTest {

    /** Wan2.2 I2V-A14B：原生 16fps，缺省成片 32fps（=16×2，RIFE 整数倍插帧）。 */
    @Test
    void Wan_合法整数倍原样保留_非法则归一() {
        assertThat(EngineSettingsService.normalizeDeliverFps(32, 16, 32)).isEqualTo(32);   // 16×2 ✓
        assertThat(EngineSettingsService.normalizeDeliverFps(16, 16, 32)).isEqualTo(16);   // 原生 ✓
        assertThat(EngineSettingsService.normalizeDeliverFps(48, 16, 32)).isEqualTo(48);   // 16×3 ✓
        assertThat(EngineSettingsService.normalizeDeliverFps(30, 16, 32)).isEqualTo(32);   // 旧默认 30 ✗ → 32
        assertThat(EngineSettingsService.normalizeDeliverFps(24, 16, 32)).isEqualTo(32);   // 1.5× ✗ → 32
    }

    /** LTX-2.5：原生 24fps，缺省成片 24（未开时间轴 ×2）/ 48（开了 ×2）。 */
    @Test
    void LTX_原生24与时间轴x2合法_计划的32要归一() {
        assertThat(EngineSettingsService.normalizeDeliverFps(24, 24, 24)).isEqualTo(24);   // 原生 ✓
        assertThat(EngineSettingsService.normalizeDeliverFps(48, 24, 48)).isEqualTo(48);   // 24×2 ✓
        assertThat(EngineSettingsService.normalizeDeliverFps(32, 24, 24)).isEqualTo(24);   // 计划里的 32 ✗ → 24
        assertThat(EngineSettingsService.normalizeDeliverFps(32, 24, 48)).isEqualTo(48);   // 同上，开了 ×2 → 48
        assertThat(EngineSettingsService.normalizeDeliverFps(30, 24, 24)).isEqualTo(24);   // 1.25× ✗ → 24
    }

    @Test
    void 未填或异常值走缺省() {
        assertThat(EngineSettingsService.normalizeDeliverFps(0, 24, 24)).isEqualTo(24);
        assertThat(EngineSettingsService.normalizeDeliverFps(-1, 16, 32)).isEqualTo(32);
        assertThat(EngineSettingsService.normalizeDeliverFps(30, 0, 30)).isEqualTo(30);    // 原生未知 → 不动
    }
}
