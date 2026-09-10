package studio.weaveora.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.asset.AssetService;
import studio.weaveora.asset.api.AssetResponse;
import studio.weaveora.asset.domain.Asset;
import studio.weaveora.asset.domain.AssetRepository;
import studio.weaveora.director.PlanReader;
import studio.weaveora.director.plan.AudioPlan;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.storage.StoragePort;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.project.api.ProjectContextPort.ProjectSnapshot;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W8 成片：把已确认视频项目的素材合成单 mp4（“可交付自动成片”）。
 * - 画布按项目画幅自适应（9:16 → 720×1280 竖屏，不再压横屏）。
 * - 转场（transition=fade|crossfade）：镜头间用 ffmpeg xfade 真叠化（交叉淡化），
 *   不再是每段独立 fade in/out 造成的闪黑场；为保持时长守恒，每段素材尾帧 clone 延长
 *   CROSSFADE_SEC 后叠化（成片时长 = 素材总长 - 转场重叠 + 片尾定格）。
 * - 每段先统一分辨率/fps/yuv420p + 静音 AAC 轨，重编码保证可 concat / xfade。
 * 依赖宿主 ffmpeg + ffprobe（生产 VPS 已装 7.0.2-static；dev 需自带）。
 */
@Service
public class ConcatService {

    private static final Logger log = LoggerFactory.getLogger(ConcatService.class);
    private static final long FFMPEG_TIMEOUT_SEC = 1200;
    /** 转场叠化时长（秒）。 */
    private static final double CROSSFADE_SEC = 0.4;

    /** 成片画布：按项目画幅（修复竖屏 9:16 被压成横屏）。高基准 720（横） / 1280（竖）。 */
    private static int[] canvasFor(String aspect) {
        String a = aspect == null ? "" : aspect.trim();
        if (a.startsWith("9:16")) return new int[]{720, 1280};
        if (a.startsWith("1:1")) return new int[]{720, 720};
        return new int[]{1280, 720};
    }

    private final AssetService assets;
    private final AssetRepository assetRepo;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final PlanReader planReader;
    private final String ffmpeg;
    private final String subtitleFont;

    public ConcatService(AssetService assets, AssetRepository assetRepo, StoragePort storage,
                         ProjectContextPort projects, WorkspaceGuard guard, PlanReader planReader,
                         @Value("${weaveora.ffmpeg:ffmpeg}") String ffmpeg,
                         @Value("${weaveora.subtitle-font:}") String subtitleFont) {
        this.assets = assets;
        this.assetRepo = assetRepo;
        this.storage = storage;
        this.projects = projects;
        this.guard = guard;
        this.planReader = planReader;
        this.ffmpeg = ffmpeg;
        this.subtitleFont = subtitleFont == null ? "" : subtitleFont;
    }

    @Transactional
    public AssetResponse renderMaster(UUID userId, UUID workspaceId, UUID projectId,
                                      UUID revisionId, String transition) {
        guard.requireMember(userId, workspaceId);
        ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        if (!revisionId.equals(project.approvedRevisionId())) {
            throw new BizException(ErrorCode.REVISION_NOT_APPROVED, "请先确认方案再渲染成片");
        }
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频项目可渲染成片");
        }
        boolean crossfade = "fade".equals(transition) || "crossfade".equals(transition);
        boolean subtitleOn = plan.path("edit_plan").path("subtitle").asBoolean(false);
        boolean subOk = hasFilter("ass");
        if (subtitleOn && !subOk) {
            log.warn("subtitle enabled but ffmpeg lacks ass/libass filter; skip burning");
        }
        int fps = plan.path("edit_plan").path("fps").asInt(30);
        String aspect = plan.path("aspect_ratio").asText("");
        int[] canvas = canvasFor(aspect);
        List<UUID> shotIds = planReader.shotIds(revisionId);
        List<MediaClip> clips = orderedMedia(workspaceId, projectId, plan, shotIds);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.EXPORT_EMPTY, "没有可用素材（先生成关键帧/运动）");
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("weaveora-render-");
            List<Path> segs = new ArrayList<>();
            List<String> segDurs = new ArrayList<>();
            int idx = 0;
            for (MediaClip c : clips) {
                idx++;
                Path raw = work.resolve("src_" + idx);
                writeAsset(c.assetKey(), raw);
                Path seg = work.resolve("seg_" + idx + ".mp4");
                // 叠化模式：每段素材尾部 clone 延长 CROSSFADE_SEC，供 xfade 重叠且时长守恒
                double pad = crossfade ? CROSSFADE_SEC : 0.0;
                double target = c.durationSec() + pad;
                encodeSegment(raw, seg, c, fps, target, pad, canvas[0], canvas[1],
                        subtitleOn && subOk ? c.narration() : null, work);
                segs.add(seg);
                segDurs.add(String.valueOf(c.durationSec() + pad));
            }
            Path master = work.resolve("master.mp4");
            if (crossfade && segs.size() > 1) {
                mergeCrossfade(segs, segDurs, master,
                        plan.path("title").asText(""));
            } else {
                // cut：直接 concat（各段同参数含 AAC 轨）→ 封装拷贝，无二次转码损失
                Path list = work.resolve("list.txt");
                StringBuilder sb = new StringBuilder();
                for (Path s : segs) sb.append("file '").append(s.toAbsolutePath()).append("'\n");
                Files.writeString(list, sb.toString());
                run("ffmpeg-concat", "-y", "-f", "concat", "-safe", "0", "-i",
                        list.toString(), "-c", "copy",
                        "-metadata", "title=" + plan.path("title").asText(""), master.toString());
            }
            // P7：配音 + 配乐混音（静音轨 → voice(逐镜) + bgm(ducking)）
            Path mixed = work.resolve("master_mix.mp4");
            if (mixAudio(workspaceId, projectId, plan, master, mixed, clips, work)) {
                master = mixed;
            }
            byte[] bytes = Files.readAllBytes(master);
            String key = workspaceId + "/" + projectId + "/master/" + UUID.randomUUID() + ".mp4";
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                storage.put(key, in, bytes.length, "video/mp4");
            }
            return toAssetResponse(assets.createOutput(workspaceId, projectId, null, null, null, "master",
                    key, "video/mp4", null, null, null, null));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("成片合成失败", e);
        } finally {
            if (work != null) {
                try {
                    Files.walk(work).sorted(Comparator.reverseOrder()).forEach(p -> {
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

    /**
     * P7 混音：master 静音轨替换为 逐镜 voice（按镜起点 adelay）+ bgm（低音量，voice 侧链 ducking）。
     * 无 voice/bgm 时返回 false（保持原静音轨）。sidechaincompress 不可用时退化为固定低音量 bgm。
     */
    private boolean mixAudio(UUID workspaceId, UUID projectId, JsonNode plan, Path in, Path out,
                             List<MediaClip> clips, Path work) throws IOException, InterruptedException {
        boolean hasVoice = false;
        for (MediaClip c : clips) {
            if (!c.voices().isEmpty()) {
                hasVoice = true;
            }
        }
        List<AudioPlan.MusicCue> cues = AudioPlan.musicCues(plan);
        Map<String, String> bgmByMood = latestBgmByMood(workspaceId, projectId);
        if (bgmByMood.isEmpty() && !hasVoice) return false;

        List<String> inputs = new ArrayList<>(List.of("-y", "-i", in.toString()));
        List<String> parts = new ArrayList<>();
        List<String> voiceLabels = new ArrayList<>();
        int idx = 1;
        double cursor = 0;
        for (MediaClip c : clips) {
            // P8：一镜可多段语音，各自摆到「镜头起点 + 镜内 at_sec」
            for (VoiceCue v : c.voices()) {
                Path vp = work.resolve("voice_" + idx + ".bin");
                writeAsset(v.assetKey(), vp);
                inputs.addAll(List.of("-i", vp.toString()));
                int ms = (int) Math.round((cursor + v.atSec()) * 1000);
                parts.add("[" + idx + ":a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                        + "adelay=" + ms + "|" + ms + ",volume=1.0,apad[v" + idx + "]");
                voiceLabels.add("[v" + idx + "]");
                idx++;
            }
            cursor += c.durationSec();
        }

        // P8：配乐按段落表铺（同 mood 只生成一次曲子，多段引用同一产物、各自套 gain/fade）
        List<String> musicLabels = new ArrayList<>();
        for (AudioPlan.MusicCue cue : cues) {
            String mood = cue.mood() == null ? "" : cue.mood();
            String key = bgmByMood.get(mood);
            if (key == null) {
                log.warn("配乐段 {} 找不到 mood={} 的产物（先点“生成配乐”），跳过", cue.id(), mood);
                continue;
            }
            Path bp = work.resolve("bgm_" + idx + ".bin");
            writeAsset(key, bp);
            if (cue.loop()) {
                inputs.addAll(List.of("-stream_loop", "-1", "-i", bp.toString()));
            } else {
                inputs.addAll(List.of("-i", bp.toString()));
            }
            int i = idx++;
            parts.add(bgmCueFilter(i, cue));
            musicLabels.add("[m" + i + "]");
        }
        if (!musicLabels.isEmpty()) {
            if (musicLabels.size() == 1) {
                parts.add(musicLabels.get(0) + "acopy[bgmraw]");
            } else {
                parts.add(String.join("", musicLabels) + "amix=inputs=" + musicLabels.size()
                        + ":duration=longest:normalize=0[bgmraw]");
            }
        }
        if (!voiceLabels.isEmpty()) {
            parts.add(String.join("", voiceLabels) + "amix=inputs=" + voiceLabels.size()
                    + ":duration=longest:normalize=0[voice]");
        }
        String total = String.format("%.3f", cursor > 0 ? cursor : 30.0);
        boolean hasMusic = !musicLabels.isEmpty();
        boolean duck = !voiceLabels.isEmpty() && hasMusic;

        List<String> duckParts = new ArrayList<>(parts);
        if (duck) {
            duckParts.add("[voice]asplit=2[vmix][vsc]");
            duckParts.add("[bgmraw][vsc]sidechaincompress=threshold=0.05:ratio=6:attack=20:release=400[bgmduck]");
            duckParts.add("[vmix][bgmduck]amix=inputs=2:duration=longest:normalize=0[aout]");
        } else if (!voiceLabels.isEmpty()) {
            duckParts.add("[voice]acopy[aout]");
        } else if (hasMusic) {
            duckParts.add("[bgmraw]acopy[aout]");
        } else {
            return false;
        }
        if (runMix(inputs, duckParts, total, out)) return true;
        if (!duck) return false;
        log.warn("sidechaincompress 混音失败，退化为固定低音量 BGM");
        // 各段 gain_db 已经施加，这里只做整体轻微衰减 + 直接叠加
        List<String> simple = new ArrayList<>(parts);
        simple.add("[bgmraw]volume=0.8[bgmduck]");
        simple.add("[voice][bgmduck]amix=inputs=2:duration=longest:normalize=0[aout]");
        return runMix(inputs, simple, total, out);
    }

    /** dB → 线性增益。0dB=1.0；-10.5dB≈0.30（P7 默认）；-16.5dB≈0.15（“一半”）。 */
    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    /**
     * 单个配乐段的滤镜串（纯函数，便于单测）：
     * 裁到区间 → 归零时间轴 → 施加 gain_db → 淡入/淡出 → adelay 摆到全片起点 → apad。
     * 输入侧若设置 {@code -stream_loop -1} 则短曲子会自动循环填满区间。
     */
    static String bgmCueFilter(int inputIdx, AudioPlan.MusicCue cue) {
        double dur = cue.durationSec();
        StringBuilder f = new StringBuilder("[" + inputIdx + ":a]aresample=44100,"
                + "aformat=sample_fmts=fltp:channel_layouts=stereo,");
        f.append("atrim=0:").append(fmt3(dur)).append(",asetpts=PTS-STARTPTS,");
        f.append("volume=").append(fmt3(dbToLinear(cue.gainDb()))).append(",");
        if (cue.fadeInSec() > 0) {
            f.append("afade=t=in:st=0:d=").append(fmt3(Math.min(cue.fadeInSec(), dur))).append(",");
        }
        if (cue.fadeOutSec() > 0) {
            double fo = Math.min(cue.fadeOutSec(), dur);
            f.append("afade=t=out:st=").append(fmt3(Math.max(0, dur - fo)))
                    .append(":d=").append(fmt3(fo)).append(",");
        }
        int startMs = (int) Math.round(Math.max(0, cue.startSec()) * 1000);
        f.append("adelay=").append(startMs).append("|").append(startMs).append(",apad")
                .append("[m").append(inputIdx).append("]");
        return f.toString();
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /**
     * bgm 产物按 mood 建索引（同一 mood 多次生成时取最新一条）。
     * mood 从资产的 prompt_snapshot 里取（P8 起 job payload 带 mood）；老资产没有 snapshot→归到空 mood。
     */
    private Map<String, String> latestBgmByMood(UUID workspaceId, UUID projectId) {
        List<Asset> bgms = assetRepo.findByProjectIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, "bgm");
        Map<String, String> out = new LinkedHashMap<>();
        for (Asset a : bgms) {                     // 已按 createdAt DESC，先遇到的即最新
            JsonNode snap = a.promptSnapshot();
            String mood = (snap != null && snap.hasNonNull("mood")) ? snap.path("mood").asText("").trim() : "";
            out.putIfAbsent(mood, a.storageKey());
        }
        return out;
    }

    /** 执行一次混音（video copy + 新音轨）；失败返回 false（由调用方决定是否退化重试）。 */
    private boolean runMix(List<String> inputs, List<String> parts, String total, Path out)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(inputs);
        args.addAll(List.of("-filter_complex", String.join(";", parts),
                "-map", "0:v:0", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
                "-t", total, "-movflags", "+faststart", out.toString()));
        try {
            run("ffmpeg-mix", args.toArray(new String[0]));
            return true;
        } catch (IllegalStateException e) {
            log.warn("mix 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 单段编码：统一画布/fps/yuv420p + 静音 AAC 轨；输出精确时长 target 秒。
     * pad>0 时用 tpad 尾帧 clone 补足（供 xfade 叠化与时长守恒）。
     */
    private void encodeSegment(Path raw, Path out, MediaClip c, int fps, double target, double pad,
                               int cw, int ch, String narration, Path workDir)
            throws IOException, InterruptedException {
        String vf = "scale=" + cw + ":" + ch + ":force_original_aspect_ratio=increase,"
                + "crop=" + cw + ":" + ch + ","
                + "fps=" + fps + ",format=yuv420p";
        if (pad > 0) {
            vf += ",tpad=stop_mode=clone:stop_duration=" + pad;
        }
        // 字幕（旁白）：ASS 字幕（libass）渲染，避免 drawtext 依赖；按画布宽折行，白字黑边
        if (narration != null && !narration.isBlank()) {
            Path ass = workDir.resolve("sub_" + out.getFileName() + ".ass");
            double fs = Math.max(24, ch * 0.055);
            int perLine = Math.max(8, (int) Math.floor((cw - 60) / (fs * 0.9)));
            String body = assBody(cw, ch, (int) fs, target,
                    wrapNarration(narration.trim(), perLine).replace("\n", "\\N"));
            Files.writeString(ass, body, StandardCharsets.UTF_8);
            vf += ",ass=filename=" + quoteFilter(ass.toString());
        }
        List<String> args = new ArrayList<>(List.of("-y"));
        if (c.video()) {
            args.addAll(List.of("-i", raw.toString()));
        } else {
            args.addAll(List.of("-loop", "1", "-i", raw.toString()));
        }
        args.addAll(List.of(
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", vf,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "aac", "-b:a", "96k",
                "-t", String.format("%.3f", target),
                "-shortest",
                out.toString()));
        run("ffmpeg-seg", args.toArray(new String[0]));
    }

    /** 按每行字数折行（中英混排近似按字符）。 */
    private static String wrapNarration(String text, int perLine) {
        if (text.length() <= perLine) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i += perLine) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(text, i, Math.min(text.length(), i + perLine));
        }
        return sb.toString();
    }

    /** 生成 ASS 字幕文本（libass）：白字黑边、底部居中、自动换行已含 \N。 */
    private static String assBody(int cw, int ch, int fs, double target, String text) {
        String t = text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}");
        int endSec = Math.max(1, (int) Math.floor(target - 0.3));
        int endCs = Math.max(0, (int) Math.round((target - 0.3 - Math.floor(target - 0.3)) * 100));
        int marginV = (int) Math.round(ch * 0.08);
        StringBuilder b = new StringBuilder();
        b.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(cw)
                .append("\nPlayResY: ").append(ch).append("\nWrapStyle: 0\n\n");
        b.append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour,")
                .append(" BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle,")
                .append(" BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Sub,Noto Sans CJK SC,").append(fs)
                .append(",&H00FFFFFF,&H000000FF,&H00000000,&H90000000,0,0,0,0,100,100,0,0,")
                .append("1,2.2,1,2,40,40,").append(marginV).append(",1\n\n");
        b.append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                .append("Dialogue: 0,0:00:00.40,0:00:").append(String.format("%02d.%02d", endSec, endCs))
                .append(",Sub,,0,0,0,,").append(t).append("\n");
        return b.toString();
    }

    /** 探测 ffmpeg 是否带某滤镜（如 ass），用于字幕降级保护。 */
    private boolean hasFilter(String name) {
        if (ffmpeg == null || ffmpeg.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-filters");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
                    && out.contains(" " + name + " ");
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** ffmpeg filter 选项值加引号并转义（逗号/冒号/单引号是 filter 语法保留字）。 */
    private static String quoteFilter(String path) {
        String esc = path.replace("\\", "/").replace("'", "\\\\'");
        return "'" + esc + "'";
    }

    /** 多段 xfade 叠化串接成 master（时长守恒：Σdur - (n-1)*overlap + 片尾定格 ≈ Σdur）。 */
    private void mergeCrossfade(List<Path> segs, List<String> durs, Path master, String title)
            throws IOException, InterruptedException {
        int n = segs.size();
        double[] len = new double[n];
        for (int i = 0; i < n; i++) {
            len[i] = durationOf(segs.get(i));
        }
        List<String> args = new ArrayList<>();
        args.add("-y");
        for (Path s : segs) {
            args.add("-i");
            args.add(s.toString());
        }
        // 全长静音轨（音频输入位于最后，索引 n）
        double total = 0.0;
        StringBuilder g = new StringBuilder();
        String left = "[0:v]";
        double acc = len[0];
        for (int k = 1; k < n; k++) {
            double off = acc - CROSSFADE_SEC;
            if (off < 0.0) off = 0.0;
            String outLabel = "[v" + k + "]";
            g.append(left).append("[").append(k).append(":v]")
                    .append("xfade=transition=fade:duration=").append(CROSSFADE_SEC)
                    .append(":offset=").append(String.format("%.3f", off)).append(outLabel).append(";");
            left = outLabel;
            acc = off + len[k];
        }
        total = acc;
        args.add("-f");
        args.add("lavfi");
        args.add("-t");
        args.add(String.format("%.3f", total));
        args.add("-i");
        args.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        args.add("-filter_complex");
        args.add(g.toString());
        args.add("-map");
        args.add(left);
        args.add("-map");
        args.add(n + ":a:0");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("20");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("96k");
        args.add("-movflags");
        args.add("+faststart");
        args.add("-metadata");
        args.add("title=" + title);
        args.add(master.toString());
        run("ffmpeg-xfade", args.toArray(new String[0]));
    }

    /** 用 ffprobe 读视频时长（秒）。 */
    private double durationOf(Path file) throws IOException, InterruptedException {
        String probe = ffprobeBin();
        ProcessBuilder pb = new ProcessBuilder(probe, "-v", "error",
                "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        boolean done = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("ffprobe 超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("ffprobe 失败 exit=" + p.exitValue());
        }
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(out);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        return 0.0;
    }

    private String ffprobeBin() {
        if (ffmpeg != null && ffmpeg.contains("/") && !"ffmpeg".equals(ffmpeg)) {
            return ffmpeg.replaceAll("(^|/)ffmpeg$", "$1ffprobe");
        }
        return "ffprobe";
    }

    private List<MediaClip> orderedMedia(UUID workspaceId, UUID projectId, JsonNode plan, List<UUID> shotIds) {
        List<MediaClip> out = new ArrayList<>();
        int order = 0;
        for (JsonNode shot : plan.path("shots")) {
            order++;
            double dur = shot.path("duration_sec").asDouble(3);
            int shotNo = shot.path("shot_no").asInt(order);
            UUID shotId = order <= shotIds.size() ? shotIds.get(order - 1) : null;
            Asset m = pickClipOrStill(workspaceId, projectId, shotId, shotNo);
            if (m == null) continue;
            out.add(new MediaClip(m.storageKey(), isVideo(m), dur, subtitleText(shot),
                    voiceCues(workspaceId, projectId, shotNo)));
        }
        return out;
    }

    /**
     * 该镜的配音资产 → 带「镜内起点」的 cue 列表（按 at_sec 升序）。
     *
     * <p>关键点：多个配音任务并发完成，<b>资产完成顺序不确定</b>，所以不能用 createdAt 推断是哪一段，
     * 必须读资产上的 {@code prompt_snapshot}（= 产生它的 job payload）里的 {@code at_sec} / {@code line_index}。
     * 同一 line_index 重复生成时（用户点了两次「生成配音」）只取最新一条，避免叠音。
     * 老资产没有 snapshot → line_index=0 / at_sec=0，退化为原来的「每镜一段」行为。
     */
    private List<VoiceCue> voiceCues(UUID workspaceId, UUID projectId, int shotNo) {
        List<Asset> vs = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "voice");
        Map<Integer, VoiceCue> latestPerLine = new LinkedHashMap<>();
        for (Asset a : vs) {                       // 已按 createdAt DESC，先遇到的即最新
            JsonNode snap = a.promptSnapshot();
            int li = (snap != null && snap.hasNonNull("line_index")) ? snap.path("line_index").asInt(0) : 0;
            if (latestPerLine.containsKey(li)) continue;
            double at = 0;
            if (snap != null && snap.hasNonNull("at_sec")) {
                at = Math.max(0, snap.path("at_sec").asDouble(0));
            }
            latestPerLine.put(li, new VoiceCue(a.storageKey(), at, li));
        }
        List<VoiceCue> cues = new ArrayList<>(latestPerLine.values());
        cues.sort(Comparator.comparingDouble(VoiceCue::atSec));
        return cues;
    }

    /** 该镜的字幕文本：多段则拼接（P8.6 再做逐段字幕定时）。 */
    private static String subtitleText(JsonNode shot) {
        List<String> texts = new ArrayList<>();
        for (AudioPlan.Line line : AudioPlan.lines(shot)) {
            texts.add(line.text());
        }
        return texts.isEmpty() ? null : String.join("  ", texts);
    }

    private Asset pickClipOrStill(UUID workspaceId, UUID projectId, UUID shotId, int shotNo) {
        // P6：优先 (project, shot_no)；退 shot_id
        List<Asset> clips = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "clip");
        if (clips.isEmpty() && shotId != null) {
            clips = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "clip");
        }
        if (!clips.isEmpty()) return clips.get(0);
        List<Asset> stills = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "still");
        if (stills.isEmpty() && shotId != null) {
            stills = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "still");
        }
        return stills.isEmpty() ? null : stills.get(0);
    }

    private void writeAsset(String storageKey, Path target) throws IOException {
        var obj = storage.get(storageKey);
        if (obj == null) throw new IllegalStateException("资产文件缺失 " + storageKey);
        try (InputStream in = obj.stream()) {
            Files.copy(in, target);
        }
    }

    private void run(String tag, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException(tag + " 超时");
        }
        if (p.exitValue() != 0) {
            log.warn("{} 失败: {}", tag,
                    out.length() > 500 ? out.substring(out.length() - 500) : out);
            throw new IllegalStateException(tag + " 执行失败（exit=" + p.exitValue() + "）");
        }
    }

    private static boolean isVideo(Asset a) {
        String m = a.mime() == null ? "" : a.mime();
        return m.startsWith("video/");
    }

    private AssetResponse toAssetResponse(Asset a) {
        return new AssetResponse(a.id(), a.projectId(), a.jobId(), a.shotId(), a.shotNo(), a.kind(), a.mime(),
                a.width(), a.height(), a.createdAt());
    }

    private record VoiceCue(String assetKey, double atSec, int lineIndex) {
    }

    private record MediaClip(String assetKey, boolean video, double durationSec, String narration,
                             List<VoiceCue> voices) {
    }
}
