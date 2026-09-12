package studio.weaveora.director.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P8 音频计划解析：把 plan JSON 归一化成「可执行的配音条目 / 配乐段落」。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>全部向后兼容</b>：没有 {@code shots[].narrations} 时把 {@code narration} 当作 at_sec=0 的单段；
 *       没有 {@code audio.music} 时把 {@code music_mood} 当作覆盖 [0, duration_sec] 的单段。</li>
 *   <li><b>纯函数、不依赖 Spring/DB</b>，便于单测（§27）。</li>
 *   <li>配音/配乐任务生成（JobService）与混音（ConcatService）共用同一套解析，避免两处口径漂移。</li>
 * </ul>
 *
 * <p>音色解析优先级：{@code narrations[].voice} → {@code voiceBindings[subject].voice}
 * → {@code audio.voice} → {@link #DEFAULT_VOICE}。
 */
public final class AudioPlan {

    /** 与 deploy/audio/tts_server.py 的 WEAVEORA_TTS_DEFAULT_VOICE 保持一致。 */
    public static final String DEFAULT_VOICE = "中文女";

    /**
     * 内置音色（CosyVoice-300M-SFT 的 7 个 spk）——与前端 {@code web/src/utils/audio.ts} 的
     * {@code VOICE_PRESETS} 保持一致。P12 用它判断「音色试听」是内置音色还是用户的参考音频路径。
     */
    public static final List<String> BUILTIN_VOICES = List.of(
            "中文女", "中文男", "英文女", "英文男", "日语男", "韩语女", "粤语女");

    /**
     * 默认配乐音量（dB）。-10.5dB ≈ 线性 0.30，与 P7 硬编码的 {@code volume=0.30} 等价，
     * 保证不填 gain_db 的老方案听感不变。「一半」约 -16.5dB。
     */
    public static final double DEFAULT_MUSIC_GAIN_DB = -10.5;

    public static final double DEFAULT_SPEED = 1.0;

    /** 克隆音色的 voice 前缀：{@code clone:关羽} */
    public static final String CLONE_PREFIX = "clone:";

    private AudioPlan() {
    }

    /** 镜内一段语音。{@code voice==null} 表示交给 {@link #voiceFor} 解析；{@code speed<=0} 表示用默认。
     *  {@code endSec<=atSec} 表示未设结束点（用配音自然长度）。 */
    public record Line(double atSec, double endSec, String text, String kind, String subject,
                       String voice, double speed) {
        public boolean dialogue() {
            return "dialogue".equals(kind);
        }

        /** 是否设了明确的结束点。 */
        public boolean hasEnd() {
            return endSec > atSec;
        }

        /**
         * 本段的可用窗口（秒）：有 end_sec 用它，否则到下一段起点（或镜头末尾）。
         * 给混音的裁切与 job 的 target_sec 用。
         */
        public double windowSec(double shotDur, Double nextAtSec) {
            double end = hasEnd() ? Math.min(endSec, shotDur > 0 ? shotDur : endSec)
                    : (nextAtSec != null ? nextAtSec : shotDur);
            return Math.max(0, end - atSec);
        }
    }

    /** 一段配乐。{@code mood} 可为空串（沿用默认 prompt）。 */
    public record MusicCue(String id, double startSec, double endSec, String mood, double gainDb,
                           double fadeInSec, double fadeOutSec, boolean loop, boolean duck) {
        public double durationSec() {
            return endSec - startSec;
        }
    }

    /**
     * P9 克隆音色：用户录/传一段样本 → 处理 → 存为音色，供 {@code voice="clone:<id>"} 引用。
     *
     * @param promptText 样本的文本（whisper 转写，可手改）—— 传给 CosyVoice 的 prompt_text，
     *                   比空串明显更贴音色
     */
    public record VoicePreset(String id, String name, String assetId, String promptText, double durationSec) {
    }

    /** 是否引用了克隆音色（形如 {@code clone:guanyu}）。 */
    public static boolean isClone(String voice) {
        return voice != null && voice.startsWith(CLONE_PREFIX) && voice.length() > CLONE_PREFIX.length();
    }

    /** 从 {@code clone:xxx} 取出 {@code xxx}。 */
    public static String cloneId(String voice) {
        return voice == null ? "" : voice.substring(CLONE_PREFIX.length()).trim();
    }

    /** 音色库：audio.voicePresets[] */
    public static List<VoicePreset> voicePresets(JsonNode plan) {
        List<VoicePreset> out = new ArrayList<>();
        if (plan == null || plan.isMissingNode()) {
            return out;
        }
        for (JsonNode p : plan.path("audio").path("voicePresets")) {
            String id = p.path("id").asText("").trim();
            String assetId = p.path("assetId").asText("").trim();
            if (id.isEmpty() || assetId.isEmpty()) {
                continue;
            }
            String name = p.path("name").asText("").trim();
            out.add(new VoicePreset(id, name.isEmpty() ? id : name, assetId,
                    p.path("promptText").asText("").trim(), p.path("durationSec").asDouble(0)));
        }
        return out;
    }

    /** 按 id 取音色；找不到返回 null（调用方应报错，不要静默回落成默认音色）。 */
    public static VoicePreset presetById(JsonNode plan, String id) {
        if (blank(id)) {
            return null;
        }
        for (VoicePreset p : voicePresets(plan)) {
            if (p.id().equals(id.trim())) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 语音

    /** 解析某镜内的所有语音段（已过滤空文本、已排序）。 */
    public static List<Line> lines(JsonNode shot) {
        List<Line> out = new ArrayList<>();
        if (shot == null || shot.isMissingNode()) {
            return out;
        }
        JsonNode arr = shot.path("narrations");
        if (arr.isArray() && !arr.isEmpty()) {
            for (JsonNode n : arr) {
                Line line = toLine(n);
                if (line != null) {
                    out.add(line);
                }
            }
            out.sort((a, b) -> Double.compare(a.atSec(), b.atSec()));
            return out;
        }
        // 兼容旧 plan：narration 单段
        String nar = shot.path("narration").asText("").trim();
        if (!nar.isEmpty()) {
            out.add(new Line(0, 0, nar, "narration", null, null, 0));
        }
        return out;
    }

    private static Line toLine(JsonNode n) {
        String text = n.path("text").asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        String subject = n.path("subject").asText("").trim();
        String kind = n.path("kind").asText("").trim();
        if (kind.isEmpty()) {
            // 有说话人 = 角色台词，否则旁白
            kind = subject.isEmpty() ? "narration" : "dialogue";
        }
        double speed = n.path("speed").asDouble(0);
        if (speed < 0.5 || speed > 2.0) {
            speed = 0;   // 交给上层取默认
        }
        double at = n.path("at_sec").asDouble(0);
        if (at < 0 || Double.isNaN(at)) {
            at = 0;
        }
        double end = n.path("end_sec").asDouble(0);
        if (end <= at || Double.isNaN(end)) {
            end = 0;   // 0/非法 → 未设结束点
        }
        return new Line(at, end, text, kind, subject.isEmpty() ? null : subject,
                blankToNull(n.path("voice").asText("")), speed);
    }

    /** 全片语音段总数（用于「有没有可配音内容」的判断与提示）。 */
    public static int totalLines(JsonNode plan) {
        int n = 0;
        for (JsonNode shot : plan.path("shots")) {
            n += lines(shot).size();
        }
        return n;
    }

    /**
     * 主体名是否与绑定项一致 —— 支持**别名互认**。
     *
     * <p>踩过的坑：绑定表里写的是别名（`贾宝玉`），剧情主体/台词里写的是 `宝玉`，
     * 早前这里只做字符串相等 → 全部对不上 → **所有角色都回落到同一个默认音色**
     * （用户以为"所有人都绑成了同一个人"）。
     */
    static boolean subjectMatches(JsonNode plan, String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        // 别名互认：任一方是对方所在主体的别名
        for (String name : new String[]{x, y}) {
            String other = name.equals(x) ? y : x;
            for (JsonNode sub : plan.path("subjects")) {
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                names.add(sub.path("name").asText("").trim());
                for (JsonNode al : sub.path("aliases")) {
                    names.add(al.asText("").trim());
                }
                if (names.contains(name) && names.contains(other)) {
                    return true;
                }
            }
        }
        // 退一步：中文名 2 字片段（秦可卿 ↔ 可卿）
        return nameSimilar(x, y);
    }

    /** 中文名容错：长度≥3 的一方包含另一方的 2 字片段。 */
    static boolean nameSimilar(String x, String y) {
        String longOne = x.length() >= y.length() ? x : y;
        String shortOne = x.length() >= y.length() ? y : x;
        if (shortOne.length() < 2 || longOne.length() < 3) {
            return false;
        }
        if (longOne.contains(shortOne)) {
            return true;
        }
        for (int i = 0; i + 2 <= longOne.length(); i++) {
            if (shortOne.contains(longOne.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    /** 音色解析：lineVoice > voiceBindings[subject]（别名互认） > audio.voice > 默认。 */
    public static String voiceFor(JsonNode plan, String subject, String lineVoice) {
        if (!blank(lineVoice)) {
            return lineVoice.trim();
        }
        JsonNode audio = plan.path("audio");
        if (!blank(subject)) {
            for (JsonNode b : audio.path("voiceBindings")) {
                if (!blank(b.path("voice").asText(""))
                        && subjectMatches(plan, subject, b.path("subject").asText(""))) {
                    return b.path("voice").asText("").trim();
                }
            }
        }
        String v = audio.path("voice").asText("").trim();
        return v.isEmpty() ? DEFAULT_VOICE : v;
    }

    /** 语速解析：line.speed > voiceBindings[subject].speed > 1.0。 */
    public static double speedFor(JsonNode plan, String subject, double lineSpeed) {
        if (lineSpeed >= 0.5 && lineSpeed <= 2.0) {
            return lineSpeed;
        }
        if (!blank(subject)) {
            for (JsonNode b : plan.path("audio").path("voiceBindings")) {
                if (subjectMatches(plan, subject, b.path("subject").asText(""))) {
                    double s = b.path("speed").asDouble(0);
                    if (s >= 0.5 && s <= 2.0) {
                        return s;
                    }
                }
            }
        }
        return DEFAULT_SPEED;
    }

    /** 已知角色/主体名（voiceBindings ∪ referenceAssets[].subject），供 UI 下拉选择。 */
    public static Set<String> subjects(JsonNode plan) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode b : plan.path("audio").path("voiceBindings")) {
            String s = b.path("subject").asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        for (JsonNode r : plan.path("referenceAssets")) {
            String s = r.path("subject").asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 配乐

    /**
     * 解析配乐段落表。无 {@code audio.music} 时回退为「整片一段」（mood 取 music_mood，可为空）。
     * 非法段（end&lt;=start、起点超出成片时长）会被跳过；超出成片末尾的会截断。
     */
    public static List<MusicCue> musicCues(JsonNode plan) {
        List<MusicCue> out = new ArrayList<>();
        if (plan == null || plan.isMissingNode()) {
            return out;
        }
        JsonNode audio = plan.path("audio");
        double total = plan.path("duration_sec").asDouble(0);
        JsonNode arr = audio.path("music");
        if (arr.isArray() && !arr.isEmpty()) {
            int i = 0;
            for (JsonNode m : arr) {
                i++;
                MusicCue cue = toCue(m, "m" + i, total, audio.path("music_mood").asText(""));
                if (cue != null) {
                    out.add(cue);
                }
            }
            out.sort((a, b) -> Double.compare(a.startSec(), b.startSec()));
            return out;
        }
        // 兼容旧 plan：整片铺一段
        if (total > 0) {
            out.add(new MusicCue("m1", 0, total, audio.path("music_mood").asText("").trim(),
                    DEFAULT_MUSIC_GAIN_DB, 0, 0, true, true));
        }
        return out;
    }

    private static MusicCue toCue(JsonNode m, String defaultId, double total, String fallbackMood) {
        double s = m.path("start_sec").asDouble(0);
        double e = m.path("end_sec").asDouble(0);
        if (Double.isNaN(s) || s < 0) {
            s = 0;
        }
        if (Double.isNaN(e) || e <= s) {
            return null;
        }
        if (total > 0) {
            if (s >= total) {
                return null;
            }
            e = Math.min(e, total);
            if (e <= s) {
                return null;
            }
        }
        String mood = m.path("mood").asText("").trim();
        if (mood.isEmpty()) {
            mood = fallbackMood == null ? "" : fallbackMood.trim();
        }
        String id = m.path("id").asText("").trim();
        return new MusicCue(id.isEmpty() ? defaultId : id, s, e, mood,
                m.path("gain_db").isNumber() ? m.path("gain_db").asDouble() : DEFAULT_MUSIC_GAIN_DB,
                Math.max(0, m.path("fade_in_sec").asDouble(0)),
                Math.max(0, m.path("fade_out_sec").asDouble(0)),
                m.path("loop").asBoolean(true),
                m.path("duck").asBoolean(true));
    }

    /**
     * 需要生成曲子的 (mood) 去重列表——同一 mood 只生成一次，多段引用同一产物。
     * 顺序 = 首次出现顺序，保证结果稳定。
     */
    public static List<String> distinctMoods(List<MusicCue> cues) {
        Set<String> seen = new LinkedHashSet<>();
        for (MusicCue c : cues) {
            seen.add(c.mood() == null ? "" : c.mood());
        }
        return new ArrayList<>(seen);
    }

    /** 某个 mood 需要的**生成时长**：取其所有段落里最长的一段（能循环更好，但一次生成够长更省事）。 */
    public static double generateDurationFor(List<MusicCue> cues, String mood) {
        double max = 0;
        for (MusicCue c : cues) {
            String m = c.mood() == null ? "" : c.mood();
            if (m.equals(mood == null ? "" : mood)) {
                max = Math.max(max, c.durationSec());
            }
        }
        return max;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
