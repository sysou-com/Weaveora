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
import java.util.HashMap;
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
    private final studio.weaveora.asset.AudioAssetLookup audioLookup;
    private final StoragePort storage;
    private final ProjectContextPort projects;
    private final WorkspaceGuard guard;
    private final PlanReader planReader;
    private final String ffmpeg;
    private final String subtitleFont;

    public ConcatService(AssetService assets, AssetRepository assetRepo,
                         studio.weaveora.asset.AudioAssetLookup audioLookup,
                         StoragePort storage,
                         ProjectContextPort projects, WorkspaceGuard guard, PlanReader planReader,
                         @Value("${weaveora.ffmpeg:ffmpeg}") String ffmpeg,
                         @Value("${weaveora.subtitle-font:}") String subtitleFont) {
        this.assets = assets;
        this.assetRepo = assetRepo;
        this.audioLookup = audioLookup;
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
        boolean subtitleOn = plan.path("edit_plan").path("subtitle").asBoolean(true);
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
            // P13：配音顺排（shot_fixed）时最后一句可能超出画面总长 —— 给**最后一段补尾**
            // （clone 末帧）而不是把配音切掉，用户听到的最后一个字不会被剪。
            double videoTotal = 0.0;
            for (MediaClip c : clips) {
                videoTotal += c.durationSec();
            }
            double audioEnd = globalVoiceEnd(clips);
            double tailPad = Math.max(0.0, audioEnd - videoTotal);
            if (tailPad > 0.05) {
                log.info("tail pad for voice overflow: audio={}s video={}s pad={}s",
                        fmt3(audioEnd), fmt3(videoTotal), fmt3(tailPad));
            }
            int idx = 0;
            for (MediaClip c : clips) {
                idx++;
                Path raw = work.resolve("src_" + idx);
                writeAsset(c.assetKey(), raw);
                Path seg = work.resolve("seg_" + idx + ".mp4");
                // 叠化模式：每段素材尾部 clone 延长 CROSSFADE_SEC，供 xfade 重叠且时长守恒
                double pad = crossfade ? CROSSFADE_SEC : 0.0;
                if (idx == clips.size()) {
                    pad += tailPad;
                }
                double target = c.durationSec() + pad;
                // P13：素材比镜头短（配音比模型单次上限长）→ **本地重定时拉伸**补齐，
                // 不额外调用云端（按次计费的模型省一次钱）；音频/字幕位置不受影响。
                double stretch = 0.0;
                if (c.stretch()) {
                    double srcDur = durationOf(raw);
                    if (srcDur > 0.05 && target > srcDur * 1.02) {
                        stretch = target / srcDur;
                        log.info("stretch clip to shot length: src={}s target={}s factor={}",
                                fmt3(srcDur), fmt3(target), fmt3(stretch));
                    }
                }
                encodeSegment(raw, seg, c, fps, target, pad, canvas[0], canvas[1],
                        subtitleOn && subOk ? c.subs() : null, work, stretch);
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
        Map<String, studio.weaveora.asset.domain.Asset> bgmAssets = latestBgmByMood(workspaceId, projectId);
        Map<String, String> bgmByMood = new LinkedHashMap<>();
        bgmAssets.forEach((k, v) -> bgmByMood.put(k, v.storageKey()));
        if (bgmByMood.isEmpty() && !hasVoice) return false;

        List<String> inputs = new ArrayList<>(List.of("-y", "-i", in.toString()));
        List<String> parts = new ArrayList<>();
        List<String> voiceLabels = new ArrayList<>();
        int idx = 1;
        double cursor = 0;
        for (MediaClip c : clips) {
            // P8：一镜可多段语音，各自摆到「镜头起点 + 镜内 at_sec」；
            //     设了 end_sec 的先裁到窗口长度再摆放（超出部分剪掉，不拖到下一段）
            for (studio.weaveora.asset.AudioAssetLookup.VoiceCue v : c.voices()) {
                Path vp = work.resolve("voice_" + idx + ".bin");
                writeAsset(v.assetKey(), vp);
                inputs.addAll(List.of("-i", vp.toString()));
                int ms = (int) Math.round((cursor + v.atSec()) * 1000);
                String trim = "";
                double win = v.windowSec();
                if (win > 0) {
                    trim = "atrim=0:" + fmt3(win) + ",asetpts=PTS-STARTPTS,";
                }
                parts.add("[" + idx + ":a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                        + trim + "adelay=" + ms + "|" + ms + ",volume=1.0,apad[v" + idx + "]");
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
     * bgm 产物按 mood 建索引（同一 mood 取最新一条）。读取规则见
     * {@link studio.weaveora.asset.AudioAssetLookup}（与导出共用）。
     */
    private Map<String, studio.weaveora.asset.domain.Asset> latestBgmByMood(UUID workspaceId, UUID projectId) {
        return audioLookup.latestBgmByMood(workspaceId, projectId);
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
                               int cw, int ch, List<SubCue> subs, Path workDir, double stretchFactor)
            throws IOException, InterruptedException {
        String vf = "scale=" + cw + ":" + ch + ":force_original_aspect_ratio=increase,"
                + "crop=" + cw + ":" + ch + ",";
        // P13：先按倍率重定时（拉长时长，帧会被复制/后续重采样），再统一 fps
        if (stretchFactor > 1.01) {
            vf += "setpts=PTS*" + fmt3(stretchFactor) + ",";
        }
        vf += "fps=" + fps + ",format=yuv420p";
        if (pad > 0) {
            vf += ",tpad=stop_mode=clone:stop_duration=" + pad;
        }
        // 字幕（旁白/台词）：ASS 字幕（libass）渲染，避免 drawtext 依赖；逐段定时，白字黑边
        if (subs != null && !subs.isEmpty()) {
            Path ass = workDir.resolve("sub_" + out.getFileName() + ".ass");
            double fs = Math.max(24, ch * 0.055);
            int perLine = Math.max(8, (int) Math.floor((cw - 60) / (fs * 0.9)));
            StringBuilder b = new StringBuilder();
            for (SubCue sc : subs) {
                b.append(assDialogue(cw, ch, (int) fs, sc.startSec(), sc.endSec(),
                        wrapNarration(sc.text(), perLine).replace("\n", "\\N")));
            }
            Files.writeString(ass, assHeader(cw, ch, (int) fs) + b, StandardCharsets.UTF_8);
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
    /** ASS 头（样式）。 */
    static String assHeader(int cw, int ch, int fs) {
        int marginV = (int) Math.round(ch * 0.08);
        return new StringBuilder()
                .append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(cw)
                .append("\nPlayResY: ").append(ch).append("\nWrapStyle: 0\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour,")
                .append(" BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle,")
                .append(" BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Sub,Noto Sans CJK SC,").append(fs)
                .append(",&H00FFFFFF,&H000000FF,&H00000000,&H90000000,0,0,0,0,100,100,0,0,")
                .append("1,2.2,1,2,40,40,").append(marginV).append(",1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                .toString();
    }

    /** 一条 ASS 字幕事件（时间按镜内相对秒 → H:MM:SS.cc）。 */
    static String assDialogue(int cw, int ch, int fs, double startSec, double endSec, String text) {
        String t = text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}");
        return "Dialogue: 0," + assTs(startSec) + "," + assTs(Math.max(startSec + 0.2, endSec))
                + ",Sub,,0,0,0,," + t + "\n";
    }

    /** 秒 → ASS 时间戳 H:MM:SS.cc */
    static String assTs(double sec) {
        double s = Math.max(0, sec);
        int h = (int) (s / 3600);
        int m = (int) ((s % 3600) / 60);
        double rest = s % 60;
        int si = (int) Math.floor(rest);
        int cs = (int) Math.round((rest - si) * 100);
        if (cs >= 100) {
            cs -= 100;
            si += 1;
        }
        if (si >= 60) {
            si -= 60;
            m += 1;
        }
        return String.format("%d:%02d:%02d.%02d", h, m, si, cs);
    }

    /** 单段字幕（兼容旧签名）。 */
    private static String assBody(int cw, int ch, int fs, double target, String text) {
        return assHeader(cw, ch, fs) + assDialogue(cw, ch, fs, 0.4, target - 0.3, text);
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

    /**
     * 配音在**全片时间轴**上的结束时刻（秒）：各段时长累加得到镜起点，再加镜内 of at+实际时长。
     * 用于「配音顺排」模式判断是否需要片尾补画面。
     */
    private double globalVoiceEnd(List<MediaClip> clips) {
        double cursor = 0.0;
        double end = 0.0;
        for (MediaClip c : clips) {
            for (studio.weaveora.asset.AudioAssetLookup.VoiceCue v : c.voices()) {
                double len = v.windowSec();
                if (len <= 0) {
                    Integer ms = v.asset().durationMs();
                    len = (ms != null && ms > 0) ? ms / 1000.0 : Math.max(0.0, v.endSec() - v.atSec());
                }
                end = Math.max(end, cursor + v.atSec() + len);
            }
            cursor += c.durationSec();
        }
        return end;
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
        // ---- 第 1 遍：确定参与渲染的片段，并收集「全局时间轴上的台词区间」 ----
        List<MediaClip> draft = new ArrayList<>();
        List<JsonNode> shotsOfDraft = new ArrayList<>();
        List<List<studio.weaveora.asset.AudioAssetLookup.VoiceCue>> voicesOfDraft = new ArrayList<>();
        int order = 0;
        for (JsonNode shot : plan.path("shots")) {
            order++;
            double dur = shot.path("duration_sec").asDouble(3);
            int shotNo = shot.path("shot_no").asInt(order);
            UUID shotId = order <= shotIds.size() ? shotIds.get(order - 1) : null;
            Asset m = pickClipOrStill(workspaceId, projectId, shotId, shotNo);
            if (m == null) continue;   // 无素材的镜不入片（沿用旧行为，不占时间轴）
            // P13：配音的**摆放位置/窗口以当前方案为准**。
            // 资产快照里的 at_sec/end_sec 是「生成那一刻」冻结的，用户后来拖了/「按实际重排」了，
            // 渲染却还按旧快照摆 → 听起来像“生成了新配音但渲染还是旧的”。
            List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> cues =
                    alignCuesWithPlan(shot, voiceCues(workspaceId, projectId, shotNo));
            // P13：镜头分段（超过模型单次上限）→ 每段一个片段，**同镜内必须 cut** 拼接；
            //       配音/字幕按段窗口重新定位（否则音频会摆到镜内绝对时间上）。
            JsonNode segNode = shot.path("segments");
            if (segNode.isArray() && segNode.size() > 1) {
                List<Asset> segClips = segmentClips(workspaceId, projectId, shotId, shotNo, segNode.size());
                if (!segClips.isEmpty()) {
                    for (int si = 0; si < segNode.size(); si++) {
                        JsonNode seg = segNode.get(si);
                        double segDur = seg.path("duration_sec").asDouble(0);
                        if (segDur <= 0) continue;
                        double s0 = seg.path("start_sec").asDouble(0);
                        Asset sa = si < segClips.size() ? segClips.get(si) : segClips.get(segClips.size() - 1);
                        List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> segCues = sliceVoices(cues, s0, s0 + segDur);
                        draft.add(new MediaClip(sa.storageKey(), isVideo(sa), segDur, segCues, List.of()));
                        shotsOfDraft.add(shot);
                        voicesOfDraft.add(segCues);
                    }
                    log.info("render segmented shot: shot_no={} segs={} clips={}",
                            shotNo, segNode.size(), segClips.size());
                    continue;
                }
                log.warn("render segmented shot without clips: shot_no={} → 回退整镜素材", shotNo);
            }
            // P13：单段但配音比模型上限长 → 标记为“本地拉伸补齐”（不额外花云端调用）
            boolean stretchNeeded = segNode.isArray() && segNode.size() <= 1
                    && shot.path("stretch").asBoolean(false);
            draft.add(new MediaClip(m.storageKey(), isVideo(m), dur, cues, List.of(), stretchNeeded));
            shotsOfDraft.add(shot);
            voicesOfDraft.add(draft.get(draft.size() - 1).voices());
        }

        // P10：字幕区间按**配音实际时长**定，而不是 plan 里的占位值；
        //      并允许一条台词跨镜——这样“上一镜配音超过镜头时长”时字幕会自动跟到下一镜。
        List<LineSpan> spans = new ArrayList<>();
        double cursor = 0;
        for (int i = 0; i < draft.size(); i++) {
            JsonNode shot = shotsOfDraft.get(i);
            double clipDur = draft.get(i).durationSec();
            Map<Integer, Double> actualSec = new LinkedHashMap<>();
            for (studio.weaveora.asset.AudioAssetLookup.VoiceCue v : voicesOfDraft.get(i)) {
                Integer ms = v.asset().durationMs();
                if (ms != null && ms > 0) {
                    actualSec.put(v.lineIndex(), ms / 1000.0);
                }
            }
            spans.addAll(lineSpans(shot, clipDur, cursor, actualSec));
            cursor += clipDur;
        }

        // ---- 第 2 遍：把台词区间按镜切片，落到各镜的字幕上（跨镜的自续显） ----
        List<MediaClip> out = new ArrayList<>();
        cursor = 0;
        for (MediaClip c : draft) {
            double s0 = cursor;
            double s1 = cursor + c.durationSec();
            out.add(new MediaClip(c.assetKey(), c.video(), c.durationSec(), c.voices(),
                    sliceSpans(spans, s0, s1)));
            cursor = s1;
        }
        return out;
    }

    /** 一条台词在全片时间轴上的区间（字幕用）。 */
    record LineSpan(double startSec, double endSec, String text) {
    }

    /**
     * P10：把某镜的台词展开成全片时间轴上的区间（纯函数，便于单测）。
     *
     * 优先级：手动 end_sec &gt; 配音实际时长（actualSec）&gt; 下一段起点/镜尾（旧口径）。
     */
    static List<LineSpan> lineSpans(JsonNode shot, double clipDur, double cursor,
                                    Map<Integer, Double> actualSec) {
        List<LineSpan> out = new ArrayList<>();
        List<AudioPlan.Line> lines = AudioPlan.lines(shot);
        for (int k = 0; k < lines.size(); k++) {
            AudioPlan.Line l = lines.get(k);
            if (l.text().isBlank()) {
                continue;
            }
            double start = cursor + Math.max(0, l.atSec());
            double dur;
            if (l.hasEnd()) {
                dur = l.endSec() - l.atSec();
            } else if (actualSec != null && actualSec.containsKey(k)) {
                dur = actualSec.get(k);
            } else {
                Double nextAt = (k + 1 < lines.size()) ? lines.get(k + 1).atSec() : null;
                dur = (nextAt != null ? nextAt : clipDur) - l.atSec();
            }
            out.add(new LineSpan(start, start + Math.max(0.4, dur), l.text().trim()));
        }
        return out;
    }

    /** P10：把全片区间的台词按某镜 [s0,s1) 切片（跨镜的自续显）。 */
    static List<SubCue> sliceSpans(List<LineSpan> spans, double s0, double s1) {
        List<SubCue> subs = new ArrayList<>();
        for (LineSpan sp : spans) {
            double a = Math.max(sp.startSec(), s0);
            double b = Math.min(sp.endSec(), s1);
            if (b - a < 0.05) continue;
            subs.add(new SubCue(a - s0, b - s0, sp.text()));
        }
        return subs;
    }

    /**
     * 某镜的**分段**产物（按 `segment_index` 对齐，缺失时按生成时间补位），升序。
     *
     * <p>分段任务的 payload 里写了 segment_index（也进了资产快照），所以优先按它排；
     * 历史/手工产物没有这个字段时，退回“按创建时间升序”取前 N 个（与配音“取最新”口径一致）。
     */
    private List<Asset> segmentClips(UUID workspaceId, UUID projectId, UUID shotId, int shotNo, int segCount) {
        List<Asset> clips = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "clip");
        if (clips.isEmpty() && shotId != null) {
            clips = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "clip");
        }
        if (clips.isEmpty()) {
            return List.of();
        }
        Asset[] out = new Asset[segCount];
        List<Asset> rest = new ArrayList<>();
        for (Asset a : clips) {
            JsonNode snap = a.promptSnapshot();
            int si = (snap != null && snap.hasNonNull("segment_index")) ? snap.path("segment_index").asInt(-1) : -1;
            if (si >= 0 && si < segCount && out[si] == null) {
                out[si] = a;
            } else {
                rest.add(a);
            }
        }
        int ri = 0;
        for (int i = 0; i < segCount; i++) {
            if (out[i] == null && ri < rest.size()) {
                out[i] = rest.get(ri++);
            }
        }
        List<Asset> res = new ArrayList<>();
        for (Asset a : out) {
            if (a != null) {
                res.add(a);
            }
        }
        return res;
    }

    /**
     * 取某个段窗口内的配音 cue，并把镜内绝对时间**换算成段内相对时间**。
     * 跨段的长句归到它开始的那一段（允许溢出到下一段，与全局口径一致）。
     */
    private List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> sliceVoices(
            List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> cues, double s0, double s1) {
        List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> out = new ArrayList<>();
        if (cues == null) {
            return out;
        }
        for (studio.weaveora.asset.AudioAssetLookup.VoiceCue c : cues) {
            double at = c.atSec();
            if (at + 1e-6 >= s0 && at + 1e-6 < s1) {
                double nAt = Math.max(0, at - s0);
                double nEnd = c.endSec() > at ? c.endSec() - s0 : 0;
                out.add(new studio.weaveora.asset.AudioAssetLookup.VoiceCue(
                        c.asset(), nAt, nEnd, c.lineIndex(), c.lineKind(), c.subject()));
            }
        }
        return out;
    }

    /**
     * 把配音 cue 的镜内位置对齐到**当前方案**的 narrations（按 line_index 对位）。
     *
     * <p>音频文件仍取该段最新生成的产物（见 AudioAssetLookup）；这里只覆盖 at/end，
     * 保证“渲染出来的配音落在你刚刚在时间轴上看到的位置”。
     * 方案里找不到对应段（历史数据/line_index 缺失）→ 保留快照值。
     */
    private List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> alignCuesWithPlan(
            JsonNode shot, List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return cues == null ? List.of() : cues;
        }
        Map<Integer, double[]> planAt = new HashMap<>();
        JsonNode narr = shot.path("narrations");
        if (narr.isArray()) {
            for (int i = 0; i < narr.size(); i++) {
                JsonNode n = narr.get(i);
                planAt.put(i, new double[]{n.path("at_sec").asDouble(0), n.path("end_sec").asDouble(0)});
            }
        }
        if (planAt.isEmpty()) {
            return cues;
        }
        List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> out = new ArrayList<>(cues.size());
        for (studio.weaveora.asset.AudioAssetLookup.VoiceCue c : cues) {
            double[] pe = planAt.get(c.lineIndex());
            if (pe == null) {
                out.add(c);
                continue;
            }
            double at = Math.max(0, pe[0]);
            double end = pe[1] > at ? pe[1] : 0;
            if (Math.abs(at - c.atSec()) < 0.001 && Math.abs(end - c.endSec()) < 0.001) {
                out.add(c);
                continue;
            }
            log.info("voice cue aligned to plan: shot={} line={} at {}->{} end {}->{}",
                    shot.path("shot_no").asInt(), c.lineIndex(), c.atSec(), at, c.endSec(), end);
            out.add(new studio.weaveora.asset.AudioAssetLookup.VoiceCue(
                    c.asset(), at, end, c.lineIndex(), c.lineKind(), c.subject()));
        }
        return out;
    }

    /**
     * 该镜的配音 cue（按镜内起点升序）。读取规则见
     * {@link studio.weaveora.asset.AudioAssetLookup}（与导出共用同一口径，避免漂移）。
     */
    private List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> voiceCues(
            UUID workspaceId, UUID projectId, int shotNo) {
        return audioLookup.voiceCues(workspaceId, projectId, shotNo);
    }

    /**
     * P8：逐段字幕定时 —— 每条语音段各自一个时间段：
     * start = at_sec；end = 显式 end_sec，否则下一段起点，否则镜尾。
     * 每段各显示自己的文本，不再把整镜拼成一句话。
     */
    /**
     * @deprecated 已改为在 {@link #orderedMedia} 里按「配音实际时长 + 跨镜切片」统一计算，
     *     本方法保留仅供对照。
     */
    @Deprecated
    private static List<SubCue> subtitleCues(JsonNode shot, double shotDur) {
        List<AudioPlan.Line> lines = AudioPlan.lines(shot);
        List<SubCue> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            AudioPlan.Line l = lines.get(i);
            if (l.text().isBlank()) {
                continue;
            }
            Double nextAt = (i + 1 < lines.size()) ? lines.get(i + 1).atSec() : null;
            double start = Math.max(0, l.atSec());
            double end;
            if (l.hasEnd()) {
                end = l.endSec();
            } else if (nextAt != null) {
                end = nextAt;
            } else {
                end = shotDur;
            }
            end = Math.min(end, shotDur > 0 ? shotDur : end);
            if (end - start < 0.4) {
                end = Math.min(start + 0.4, Math.max(start + 0.4, shotDur));   // 太短给个下限，免得闪一下
            }
            out.add(new SubCue(start, end, l.text().trim()));
        }
        return out;
    }

    private Asset pickClipOrStill(UUID workspaceId, UUID projectId, UUID shotId, int shotNo) {
        // P13：对口型产物优先 —— 有 lipsync 就用它（嘴型已与台词对齐），否则用普通 motion/关键帧
        List<Asset> lips = assetRepo.findByProjectIdAndWorkspaceIdAndShotNoAndKindOrderByCreatedAtDesc(
                projectId, workspaceId, shotNo, "lipsync");
        if (lips.isEmpty() && shotId != null) {
            lips = assetRepo.findByShotIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(shotId, workspaceId, "lipsync");
        }
        if (!lips.isEmpty()) {
            return lips.get(0);
        }
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
                a.width(), a.height(), a.durationMs(),
                studio.weaveora.asset.AssetService.lineIndexOf(a),
                studio.weaveora.asset.AssetService.subjectOf(a), null,
                studio.weaveora.asset.AssetService.snapshotKindOf(a),
                studio.weaveora.asset.AssetService.faceDetectedOf(a),
                studio.weaveora.asset.AssetService.faceFramesOf(a),
                studio.weaveora.asset.AssetService.notesOf(a), a.createdAt());
    }

    /** 一个字幕段（镜内相对秒）。 */
    record SubCue(double startSec, double endSec, String text) {
    }

    private record MediaClip(String assetKey, boolean video, double durationSec,
                             List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> voices, List<SubCue> subs,
                             boolean stretch) {
        MediaClip(String assetKey, boolean video, double durationSec,
                  List<studio.weaveora.asset.AudioAssetLookup.VoiceCue> voices, List<SubCue> subs) {
            this(assetKey, video, durationSec, voices, subs, false);
        }
    }
}
