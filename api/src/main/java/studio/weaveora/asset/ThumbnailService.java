package studio.weaveora.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 资产缩略图（§7.6 / §16.2 / §21）。
 *
 * <p><b>规格来自 §21：缩略图 = 同前缀 {@code _thumb.webp}，最长边 512。</b>
 *
 * <p>生成走宿主 ffmpeg —— 与 {@link AudioProcessService}、{@code export.ConcatService} 是同一条
 * 既有依赖（生产 VPS 为 7.0.2-static，含 {@code libwebp} 编码器），不引入 JNI 图像库：
 *
 * <ul>
 *   <li><b>静态图</b>（png/jpg/webp）→ 缩到最长边 512。webp 输入也能处理（libwebp 解码），
 *       而 JDK {@code ImageIO} 读不了 webp；实测 1344×768 / 1.6MB 的 png → <b>18.9KB</b>（约 85×）。</li>
 *   <li><b>视频</b> → 取 {@value #VIDEO_SEEK_SEC}s 处一帧。<b>刻意不用首帧</b>：本项目实测
 *       所有 i2v / 对口型产物首帧都是纯黑（webp 只有 324 bytes、YAVG=16），取出来是空图；
 *       带 -ss 后同一文件 4.9KB / YAVG=71 是正常画面。视频短于该偏移时自动退回首帧。</li>
 *   <li><b>音频</b> → {@code showwavespic} 波形图，使配音/配乐 Tab 也有可辨识的视觉。</li>
 * </ul>
 *
 * <p><b>失败一律返回 null，绝不抛异常</b>：缩略图是加速手段，不是业务的必要前置 ——
 * 它坏了不能让「读资产」这件事跟着失败（调用方会回落原文件）。
 */
@Service
public class ThumbnailService {

    /** §21：缩略图最长边（像素）。 */
    public static final int MAX_EDGE = 512;
    /**
     * 视频取帧位置（秒）。
     *
     * <p>不能取首帧 —— 见类注释：实测本项目所有生成视频的首帧都是纯黑。
     */
    public static final double VIDEO_SEEK_SEC = 0.5;
    /** 波形图高度（宽度用 {@link #MAX_EDGE}）。 */
    public static final int WAVE_HEIGHT = 128;
    /** libwebp 质量（0–100）。78 在 512 长边上肉眼无损、体积约为 90 的一半。 */
    public static final String WEBP_QUALITY = "78";

    private static final long FFMPEG_TIMEOUT_SEC = 60;
    private static final int LOG_TAIL = 300;

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    private final String ffmpeg;

    public ThumbnailService(@Value("${weaveora.ffmpeg:ffmpeg}") String ffmpeg) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
    }

    /** 生成结果。{@code ext} 决定存储 key 后缀（正常 = webp；回落 = jpg）。 */
    public record Thumb(byte[] bytes, String mime, String ext) {
    }

    /** 该 mime 是否可能生成缩略图。不支持的（如导出 zip）由调用方回落原文件。 */
    public static boolean supports(String mime) {
        return mime != null && (mime.startsWith("image/")
                || mime.startsWith("video/") || mime.startsWith("audio/"));
    }

    /**
     * 缩略图存储 key —— §21「同前缀 {@code _thumb.webp}」：
     * 原 key 去掉扩展名后拼 {@code _thumb.<ext>}（如 {@code ws/p/j/a.png → ws/p/j/a_thumb.webp}）。
     *
     * <p>注意只在「最后一个点位于最后一个斜杠之后」时才当扩展名，避免目录名里带点被切坏。
     */
    public static String thumbKeyFor(String storageKey, String ext) {
        int slash = storageKey.lastIndexOf('/');
        int dot = storageKey.lastIndexOf('.');
        String base = dot > slash ? storageKey.substring(0, dot) : storageKey;
        return base + "_thumb." + ext;
    }

    /**
     * 纯函数：缩放滤镜串（供单测断言）。
     *
     * <p>两条要点：
     * <ol>
     *   <li><b>逗号必须转义成 {@code \,}</b> —— ffmpeg 滤镜语法里逗号既分隔滤镜链也分隔参数，
     *       {@code min(512,iw)} 不转义会被解析成两个滤镜而直接报错。</li>
     *   <li>{@code min()} 包住源尺寸 + {@code force_original_aspect_ratio=decrease} = 等比缩进
     *       512×512 且<b>不放大</b>小图。</li>
     * </ol>
     */
    public static String scaleFilter() {
        return "scale=w=min(" + MAX_EDGE + "\\,iw):h=min(" + MAX_EDGE + "\\,ih)"
                + ":force_original_aspect_ratio=decrease";
    }

    /** 纯函数：音频波形滤镜串。 */
    public static String waveFilter() {
        return "showwavespic=s=" + MAX_EDGE + "x" + WAVE_HEIGHT + ":colors=white";
    }

    /**
     * 纯函数：拼装 ffmpeg 参数（供单测断言）。
     *
     * <p><b>第一个元素必须是 ffmpeg 可执行文件</b> —— 否则 ProcessBuilder 会把 {@code -y}
     * 当程序名，报 {@code Cannot run program "-y": error: 2}（{@code AudioProcessService} 踩过同一个坑）。
     *
     * @param seek 视频是否做 {@code -ss} 输入定位（短于偏移的视频需要重试，故做成参数）
     */
    static List<String> buildArgs(String ffmpegBin, Path in, Path out, String mime, boolean seek) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.addAll(List.of("-y", "-nostdin", "-hide_banner", "-loglevel", "error"));
        if (seek) {
            // -ss 放在 -i 之前 = 输入定位（快，不解码前面那段）
            args.addAll(List.of("-ss", String.valueOf(VIDEO_SEEK_SEC)));
        }
        args.add("-i");
        args.add(in.toString());
        if (mime != null && mime.startsWith("audio/")) {
            args.addAll(List.of("-filter_complex", waveFilter()));
        } else {
            args.addAll(List.of("-vf", scaleFilter()));
        }
        args.addAll(List.of("-frames:v", "1", "-c:v", "libwebp",
                "-quality", WEBP_QUALITY, "-f", "webp", out.toString()));
        return args;
    }

    /**
     * 生成缩略图。不支持、ffmpeg 缺失、文件损坏……一律返回 null（调用方回落原文件）。
     *
     * @param mime 资产原始 mime
     * @param src  原始字节流（由调用方开启，本方法不负责关闭）
     */
    public Thumb generate(String mime, InputStream src) {
        if (!supports(mime) || src == null) {
            return null;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("wv-thumb-");
            Path in = dir.resolve("in." + extOf(mime));
            Files.copy(src, in, StandardCopyOption.REPLACE_EXISTING);
            Path out = dir.resolve("out.webp");

            boolean video = mime.startsWith("video/");
            boolean ok = runFfmpeg(buildArgs(ffmpeg, in, out, mime, video));
            if (!ok && video) {
                // 视频短于 VIDEO_SEEK_SEC 时 -ss 取不到帧 → 退回首帧（总比没有强）
                ok = runFfmpeg(buildArgs(ffmpeg, in, out, mime, false));
            }
            if (ok && Files.exists(out) && Files.size(out) > 0) {
                return new Thumb(Files.readAllBytes(out), "image/webp", "webp");
            }
            // 首选路径失败：静态图还能用 JDK 兜一张 JPEG（非 §21 规格，仅作降级；
            // 生产已确认 ffmpeg 带 libwebp，正常不会走到这里）
            if (mime.startsWith("image/")) {
                Thumb fallback = jpegViaImageIo(in);
                if (fallback != null) {
                    log.warn("缩略图回落 JPEG（ffmpeg 不可用或无 libwebp；请检查 weaveora.ffmpeg 配置）: {}", mime);
                    return fallback;
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("缩略图生成失败（IO）: {}", e.getMessage());
            return null;
        } finally {
            cleanup(dir);
        }
    }

    /** 一次 ffmpeg 执行；成功返回 true。任何失败都只记日志、不抛。 */
    private boolean runFfmpeg(List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("缩略图 ffmpeg 超时（{}s）", FFMPEG_TIMEOUT_SEC);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("缩略图 ffmpeg 失败（exit {}）: {}", p.exitValue(), tail(output));
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("缩略图 ffmpeg 被中断");
            return false;
        } catch (IOException e) {
            // 找不到可执行文件是最常见的原因（dev 机器没装 ffmpeg / weaveora.ffmpeg 路径不对）
            log.warn("调用 ffmpeg 失败 [{}]: {}", ffmpeg, e.getMessage());
            return false;
        }
    }

    /**
     * 降级路径：JDK ImageIO 缩到最长边 {@link #MAX_EDGE} 并写 JPEG。
     *
     * <p>JPEG 无 alpha，必须先铺白底再画，否则透明区会变黑。读不了的格式（如 webp）返回 null。
     */
    private Thumb jpegViaImageIo(Path in) {
        try {
            BufferedImage source = ImageIO.read(in.toFile());
            if (source == null) {
                return null;
            }
            int w = source.getWidth();
            int h = source.getHeight();
            double k = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
            int tw = Math.max(1, (int) Math.round(w * k));
            int th = Math.max(1, (int) Math.round(h * k));
            BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, tw, th);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(source, 0, 0, tw, th, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(dst, "jpg", bos)) {
                return null;
            }
            return new Thumb(bos.toByteArray(), "image/jpeg", "jpg");
        } catch (IOException | RuntimeException e) {
            log.warn("ImageIO 缩略图降级失败: {}", e.getMessage());
            return null;
        }
    }

    /** 读回已存在的缩略图（第二次起走这条路，不再跑 ffmpeg）。 */
    public Thumb read(String thumbKey, InputStream src) {
        String ext = extOfKey(thumbKey);
        try {
            return new Thumb(src.readAllBytes(), mimeOf(ext), ext);
        } catch (IOException e) {
            log.warn("缩略图读取失败 {}: {}", thumbKey, e.getMessage());
            return null;
        }
    }

    /** 缩略图 mime 由后缀推断；未知后缀按 webp 处理。 */
    public static String mimeOf(String ext) {
        return "jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext) ? "image/jpeg" : "image/webp";
    }

    static String extOfKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 && dot < key.length() - 1 ? key.substring(dot + 1) : "webp";
    }

    /** 原始文件的后缀（决定写进临时文件的文件名；ffmpeg 靠它嗅探容器格式）。 */
    private static String extOf(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/aac" -> "aac";
            case "audio/ogg" -> "ogg";
            case "audio/flac", "audio/x-flac" -> "flac";
            default -> "bin";
        };
    }

    private static String tail(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= LOG_TAIL ? t : t.substring(0, LOG_TAIL);
    }

    private static void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 供测试与调用方构造内存流。 */
    public static InputStream streamOf(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
