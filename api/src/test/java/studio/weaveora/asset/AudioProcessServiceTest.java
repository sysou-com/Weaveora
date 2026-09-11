package studio.weaveora.asset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P9 音色样本处理：滤镜链构造与质检提示（纯函数，不跑 ffmpeg）。 */
class AudioProcessServiceTest {

    private static final AudioProcessService.Options DEFAULTS = AudioProcessService.Options.defaults();

    @Test
    void defaultChainAlwaysResamplesAndNormalizes() {
        String f = AudioProcessService.filterChain(DEFAULTS, 15, 24000);
        // 单声道 + 24kHz 是 CosyVoice 的硬要求，必做
        assertTrue(f.contains("aformat=channel_layouts=mono"), f);
        assertTrue(f.contains("aresample=24000"), f);
        // 默认开：去静音 + 响度归一
        assertTrue(f.contains("silenceremove="), f);
        assertTrue(f.contains("loudnorm=I=-16"), f);
        // 默认关：降噪 / 裁长 / 音高
        assertFalse(f.contains("afftdn"), f);
        assertFalse(f.contains("atrim"), f);
        assertFalse(f.contains("asetrate"), f);
    }

    @Test
    void orderIsDenoiseThenSilenceThenLoudnorm() {
        var o = new AudioProcessService.Options(true, true, true, false, 0);
        String f = AudioProcessService.filterChain(o, 15, 24000);
        // 降噪必须排在静音检测/响度测量之前，否则它们测的是噪底
        assertTrue(f.indexOf("afftdn") < f.indexOf("silenceremove"), f);
        assertTrue(f.indexOf("silenceremove") < f.indexOf("loudnorm"), f);
        assertTrue(f.indexOf("aformat") < f.indexOf("afftdn"), f);
    }

    @Test
    void pitchShiftKeepsDurationByAtempo() {
        var o = new AudioProcessService.Options(false, false, false, false, -2);
        String f = AudioProcessService.filterChain(o, 15, 24000);
        // -2 半音 → k=2^(-2/12)=0.8909 → asetrate=24000*0.8909=21382，atempo=1/k=1.1225
        assertTrue(f.contains("asetrate=21382"), f);
        assertTrue(f.contains("aresample=24000"), f);
        assertTrue(f.contains("atempo=1.122"), f);
    }

    @Test
    void pitchZeroChangesNothing() {
        var o = new AudioProcessService.Options(false, false, false, false, 0);
        String f = AudioProcessService.filterChain(o, 15, 24000);
        assertFalse(f.contains("asetrate"), f);
        assertFalse(f.contains("atempo"), f);
    }

    @Test
    void limitLengthTrimsToMax() {
        var o = new AudioProcessService.Options(false, false, false, true, 0);
        String f = AudioProcessService.filterChain(o, 15, 24000);
        assertTrue(f.contains("atrim=0:15.000"), f);
        assertTrue(f.contains("asetpts=PTS-STARTPTS"), f);
    }

    @Test
    void warningsFlagTooShortAndTooLong() {
        assertTrue(AudioProcessService.warnings(1.5).stream().anyMatch(w -> w.contains("短于")),
                "1.5s 样本应提示过短");
        assertTrue(AudioProcessService.warnings(30).stream().anyMatch(w -> w.contains("超过")),
                "30s 样本应提示超长已截取");
        assertTrue(AudioProcessService.warnings(0).stream().anyMatch(w -> w.contains("几乎没有声音")),
                "无声音应提示");
        assertTrue(AudioProcessService.warnings(8).isEmpty(), "8s 是理想长度，不该有告警");
    }

    @Test
    void defaultsMatchProductDecision() {
        // 产品默认：去静音 + 响度归一 开；降噪 / 裁长 / 音高 关
        assertTrue(DEFAULTS.trimSilence());
        assertTrue(DEFAULTS.loudnorm());
        assertFalse(DEFAULTS.denoise());
        assertFalse(DEFAULTS.limitLength());
        assertEquals(0.0, DEFAULTS.pitchSemitones(), 1e-9);
    }
}
