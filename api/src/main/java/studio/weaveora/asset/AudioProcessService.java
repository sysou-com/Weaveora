package studio.weaveora.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P9 克隆音色的样本处理：把用户录/传的一段声音处理成 CosyVoice 好用的参考音。
 *
 * <p>处理链（顺序有讲究）：
 * <ol>
 *   <li>单声道 + 24kHz —— CosyVoice 的前置要求（必做）</li>
 *   <li>降噪 {@code afftdn} —— 可选（先降噪，后续静音检测与响度测量才准）</li>
 *   <li>去首尾静音 {@code silenceremove} —— 可选</li>
 *   <li>响度归一 {@code loudnorm I=-16} —— 可选（EBU R128）</li>
 *   <li>音高微调 —— 可选（{@code asetrate} 升采样率改变音高 + {@code atempo} 还原时长）</li>
 *   <li>裁到上限时长 —— 可选</li>
 * </ol>
 *
 * <p>纯逻辑（{@link #filterChain}）与执行分离，方便单测断言滤镜串。
 */
@Service
public class AudioProcessService {

    private static final Logger log = LoggerFactory.getLogger(AudioProcessService.class);

    /** 参考音的目标采样率（与 CosyVoice2 一致） */
    public static final int TARGET_SR = 24000;
    /** 参考音最长保留秒数（再长对克隆没帮助，只会拖慢推理） */
    public static final double MAX_KEEP_SEC = 15.0;
    /** 低于这个时长基本克隆不出效果 */
    public static final double MIN_USEFUL_SEC = 3.0;

    private static final long FFMPEG_TIMEOUT_SEC = 180;

    private final String ffmpeg;

    public AudioProcessService(@Value("${weaveora.ffmpeg:ffmpeg}") String ffmpeg) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
    }

    /** 处理选项（默认值 = 产品默认：去静音 + 响度归一 + 单声道重采样）。 */
    public record Options(boolean trimSilence, boolean loudnorm, boolean denoise,
                          boolean limitLength, double pitchSemitones) {

        public static Options defaults() {
            return new Options(true, true, false, false, 0);
        }
    }

    /** 处理结果。 */
    public record Result(byte[] audio, double durationSec, List<String> warnings) {
    }

    /**
     * 构造 ffmpeg 音频滤镜链（纯函数，供单测）。
     *
     * @param o        选项
     * @param maxSec   限长上限（秒）
     * @param outRate  输出采样率
     */
    static String filterChain(Options o, double maxSec, int outRate) {
        List<String> f = new ArrayList<>();
        // 1) 单声道 + 目标采样率（必做）
        f.add("aformat=channel_layouts=mono");
        f.add("aresample=" + outRate);
        // 2) 降噪（先做，后面的静音检测/响度测量才准）
        if (o.denoise()) {
            f.add("afftdn=nf=-25");
        }
        // 3) 去首尾静音：掐掉开头 <0.1s 的静音段、结尾留 0.3s 余量
        if (o.trimSilence()) {
            f.add("silenceremove=start_periods=1:start_silence=0.1:start_threshold=-45dB"
                    + ":stop_periods=-1:stop_silence=0.3:stop_threshold=-45dB");
        }
        // 4) 响度归一（EBU R128；-16 LUFS 是流媒体常用档）
        if (o.loudnorm()) {
            f.add("loudnorm=I=-16:TP=-1.5:LRA=11");
        }
        // 5) 音高微调：改采样率→音高变，再 aresample + atempo 把时长掰回来
        double semi = o.pitchSemitones();
        if (semi > 0.01 || semi < -0.01) {
            double k = Math.pow(2, semi / 12.0);
            f.add("asetrate=" + (int) Math.round(outRate * k));
            f.add("aresample=" + outRate);
            // atempo 限幅 [0.5, 2.0]；±12 半音以内都安全
            double tempo = 1.0 / k;
            tempo = Math.max(0.5, Math.min(2.0, tempo));
            f.add("atempo=" + String.format(java.util.Locale.ROOT, "%.6f", tempo));
        }
        // 6) 裁到上限
        if (o.limitLength() && maxSec > 0) {
            f.add("atrim=0:" + String.format(java.util.Locale.ROOT, "%.3f", maxSec));
            f.add("asetpts=PTS-STARTPTS");
        }
        return String.join(",", f);
    }

    /**
     * 拼装处理命令（纯函数，供单测）。
     *
     * <p><b>第一个元素必须是 ffmpeg 可执行文件</b> —— 否则 ProcessBuilder 会把 {@code -y} 当成
     * 程序名，报 {@code Cannot run program "-y": error: 2, No such file or directory}。
     */
    static List<String> buildArgs(String ffmpegBin, Path in, Path out, Options o, int outRate) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.addAll(List.of("-y", "-hide_banner", "-loglevel", "error",
                "-i", in.toString(),
                "-af", filterChain(o, MAX_KEEP_SEC, outRate),
                "-ac", "1", "-ar", String.valueOf(outRate),
                "-c:a", "pcm_s16le", out.toString()));
        return args;
    }

    /** 处理一段音频；失败抛 IllegalStateException（调用方转成业务错误）。 */
    public Result process(byte[] src, String srcExt, Options opts) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("wv-voice-");
            Path in = dir.resolve("in." + (srcExt == null || srcExt.isBlank() ? "bin" : srcExt));
            Path out = dir.resolve("out.wav");
            Files.write(in, src);
            run(buildArgs(ffmpeg, in, out, opts, TARGET_SR));
            if (!Files.exists(out)) {
                throw new IllegalStateException("音频处理没有产出文件");
            }
            byte[] audio = Files.readAllBytes(out);
            double dur = probeDuration(out);
            return new Result(audio, dur, warnings(dur));
        } catch (IOException e) {
            throw new IllegalStateException("音频处理失败: " + e.getMessage(), e);
        } finally {
            if (dir != null) {
                try {
                    Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 质检提示（不阻断，只提醒用户换样本）。 */
    static List<String> warnings(double durSec) {
        List<String> w = new ArrayList<>();
        if (durSec > 0 && durSec < MIN_USEFUL_SEC) {
            w.add(String.format("样本只有 %.1fs，短于 %.0fs：克隆相似度会明显下降，建议录 5–15s 的连续说话",
                    durSec, MIN_USEFUL_SEC));
        } else if (durSec > MAX_KEEP_SEC) {
            w.add(String.format("样本 %.1fs 超过 %.0fs，已截取前 %.0fs（更长对克隆无益）",
                    durSec, MAX_KEEP_SEC, MAX_KEEP_SEC));
        }
        if (durSec <= 0.05) {
            w.add("处理后几乎没有声音：请确认录音没有静音或麦克风被占用");
        }
        return w;
    }

    private void run(List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("ffmpeg 处理超时");
            }
            if (p.exitValue() != 0) {
                log.warn("ffmpeg 处理失败（{}）: {}", p.exitValue(), output.strip());
                throw new IllegalStateException("ffmpeg 处理失败：" + output.strip().substring(0, Math.min(300, output.strip().length())));
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            String hint = (e instanceof IOException && e.getMessage() != null
                    && e.getMessage().contains("No such file"))
                    ? "（找不到可执行文件，请确认服务器已安装 ffmpeg 且在 PATH 中，或用 weaveora.ffmpeg 指定绝对路径）"
                    : "";
            throw new IllegalStateException("调用 ffmpeg 失败 [" + ffmpeg + "]" + hint + ": " + e.getMessage(), e);
        }
    }

    private static final Pattern DUR = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)");

    private double probeDuration(Path file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffprobeBin(), "-hide_banner", "-i", file.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            Matcher m = DUR.matcher(out);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) * 3600.0
                        + Integer.parseInt(m.group(2)) * 60.0
                        + Double.parseDouble(m.group(3));
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private String ffprobeBin() {
        if (ffmpeg.contains("/") || ffmpeg.contains("\\")) {
            return ffmpeg.replaceAll("(^|[/\\\\])ffmpeg(\\.exe)?$", "$1ffprobe$2");
        }
        return "ffprobe";
    }
}
