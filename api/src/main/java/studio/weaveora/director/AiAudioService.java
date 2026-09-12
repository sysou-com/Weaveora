package studio.weaveora.director;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.director.plan.AudioPlan;
import studio.weaveora.identity.api.WorkspaceGuard;
import studio.weaveora.infra.llm.DirectorLlm;
import studio.weaveora.infra.llm.LlmRequest;
import studio.weaveora.project.api.ProjectContextPort;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P11 AI 音频助手：
 * <ul>
 *   <li><b>一键台词</b>：分析分镜画面与项目人物 → 产出 1~3 段台词（旁白/对白，模拟对话）。
 *       时长与语速由 {@link #fitLines} **确定性铺排**（不交给 LLM 算时间），保证字幕与配音同步。</li>
 *   <li><b>一键配乐</b>：依据剧情（主题/一段话概要/分段目的/逐镜动作）→ 产出 2~5 段「时间段 + 情绪」。</li>
 * </ul>
 *
 * <p>关键口径（与前端、渲染层一致）：
 * <ol>
 *   <li>AI 只能从**已绑定角色**里选说话人；不在列表里的名字一律降级为旁白。</li>
 *   <li>产出的台词**只写 subject，不写死 voice** —— 音色由角色绑定解析。
 *       写死会重演「改了绑定但旧台词仍用老音色」的坑。</li>
 *   <li>时长铺排：按中文 4.5 字/秒估算，段间留 0.25s 间隙；**固定自然语速 1.0×**（P12：不再自动提速）。</li>
 *   <li><b>台词优先</b>：先铺台词（dialogue），超出镜头就**允许溢出到下一镜**，不缩短、不提速、
 *       也不自动延长镜头；<b>旁白只填剩余空档</b>，镜头里台词没占满才铺旁白，放不下就整段不铺（会提示）。</li>
 * </ol>
 */
@Service
public class AiAudioService {

    private static final Logger log = LoggerFactory.getLogger(AiAudioService.class);

    /** 中文朗读估算速度（字/秒）——与前端 NarrationTimeline 的估算保持一致 */
    public static final double CHARS_PER_SEC = 4.5;
    /**
     * 拉丁文本（英文）朗读速度（字符/秒，含空格）≈ 140 词/分。
     * 不分开算的话，英文台词会被当成中文长度估 → 明明念得完却被判“超出镜头”。
     */
    public static final double LATIN_CHARS_PER_SEC = 13.0;
    /** 段间最小间隙（秒） */
    public static final double GAP_SEC = 0.25;
    /** 单段最短时长（秒） */
    public static final double MIN_LINE_SEC = 0.8;
    /** 台词铺排固定语速：自然语速，不再自动提速（P12 口径） */
    public static final double NATURAL_SPEED = 1.0;
    /**
     * 字数预算的安全系数：镜头 dur 秒能念 {@code dur × CHARS_PER_SEC} 字，
     * 给 AI 的预算再留 10% 余量（不要卡到临界，实测临界必溢出）。
     */
    public static final double BUDGET_RATIO = 0.9;

    private final DirectorLlm llm;
    private final PlanReader planReader;
    private final WorkspaceGuard guard;
    private final ProjectContextPort projects;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiAudioService(DirectorLlm llm, PlanReader planReader, WorkspaceGuard guard,
                          ProjectContextPort projects) {
        this.llm = llm;
        this.planReader = planReader;
        this.guard = guard;
        this.projects = projects;
    }

    // ---------------------------------------------------------------- A. 台词

    /** 一段台词（AI 只给 text/kind/subject，时长由后端铺排）。 */
    public record AiLine(String text, String kind, String subject) {
    }

    /** 铺排结果：直接可写入 {@code shots[].narrations[]} 的一条。 */
    public record FittedLine(double atSec, double endSec, String text, String kind, String subject, double speed) {
    }

    public record ShotLines(int shotNo, double shotDurationSec, List<FittedLine> lines) {
    }

    /** 铺排结果 + 需要回给用户的话（跳过旁白 / 溢出）。 */
    public record FitResult(List<FittedLine> lines, List<String> notes) {
    }

    public record LinesResult(String source, List<ShotLines> shots, List<String> notes) {
    }

    /**
     * 为指定（或全部）分镜生成台词。
     *
     * @param shotNo  null = 全部“还没有台词的镜头”
     * @param replace true = 覆盖该镜已有台词；false = 追加
     */
    @Transactional(readOnly = true)
    public LinesResult generateLines(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId,
                                     Integer shotNo, boolean replace) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频方案支持生成台词");
        }
        Set<String> boundSubjects = AudioPlan.subjects(plan);
        List<String> notes = new ArrayList<>();
        List<ShotLines> out = new ArrayList<>();
        // P12：AI 台词只写角色对白 —— 一个角色都没绑定时不必白调 LLM
        if (boundSubjects.isEmpty()) {
            notes.add("本项目还没有绑定角色：请在「② 角色音色绑定」里点「按剧情主体补全」"
                    + "（或「加一个角色」）后重试 —— AI 台词只写角色对白；旁白可手动新增一段。");
            return new LinesResult(llm.source(), out, notes);
        }

        // P12：台词语言跟随角色绑定的音色（英文音色 / 用英文样本克隆的音色 → 写英文），
        //      否则英文音色念中文会变形（听感上像在说粤语之类）。
        Map<String, String> subjectLangs = subjectLanguages(plan, boundSubjects);

        String narrationVoice = AudioPlan.voiceFor(plan, null, null);
        List<JsonNode> shots = new ArrayList<>();
        for (JsonNode s : plan.path("shots")) {
            if (shotNo != null && s.path("shot_no").asInt() != shotNo) {
                continue;
            }
            shots.add(s);
        }
        if (shots.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "找不到第 " + shotNo + " 镜");
        }

        for (JsonNode shot : shots) {
            int no = shot.path("shot_no").asInt();
            double dur = shot.path("duration_sec").asDouble(3);
            boolean hasLines = !AudioPlan.lines(shot).isEmpty();
            if (shotNo == null && hasLines) {
                continue;   // 批量模式：跳过已有台词的镜头
            }
            if (hasLines && !replace) {
                notes.add("第 " + no + " 镜已有台词，按“追加”处理（如需覆盖请选覆盖）");
            }
            List<AiLine> ai = askLines(project, plan, shot, subjectLangs, narrationVoice);
            if (ai.isEmpty()) {
                notes.add("第 " + no + " 镜：AI 没有给出可用台词（已跳过）");
                continue;
            }
            FitResult fit = fitLinesDetailed(ai, dur, boundSubjects);
            if (fit.lines().isEmpty()) {
                continue;
            }
            for (String n : fit.notes()) {
                notes.add("第 " + no + " 镜：" + n);
            }
            List<FittedLine> fitted = fit.lines();
            // 追加时，新台词接在已有台词之后
            if (hasLines && !replace) {
                double after = 0;
                for (AudioPlan.Line l : AudioPlan.lines(shot)) {
                    double end = l.hasEnd() ? l.endSec() : l.atSec() + estimate(l.text());
                    after = Math.max(after, end + GAP_SEC);
                }
                List<FittedLine> shifted = new ArrayList<>();
                for (FittedLine f : fitted) {
                    shifted.add(new FittedLine(round1(f.atSec() + after), round1(f.endSec() + after),
                            f.text(), f.kind(), f.subject(), f.speed()));
                }
                fitted = shifted;
            }
            out.add(new ShotLines(no, dur, fitted));
        }
        if (out.isEmpty() && notes.isEmpty()) {
            notes.add("没有需要生成台词的镜头（全部已有台词；可勾选“覆盖”重写）");
        }
        return new LinesResult(llm.source(), out, notes);
    }

    /**
     * 角色 → 台词语言。由该角色绑定的音色推断：
     * <ul>
     *   <li>内置音色：英文女/英文男 → English；日语男 → 日本語；韩语女 → 한국어；粤语女/中文女 → 中文</li>
     *   <li>克隆音色：看样本的转写文本（{@code promptText}）——拉丁字母为主 → English；
     *       带假名 → 日本語；带谚文 → 한국어；否则看音色名/中文</li>
     * </ul>
     */
    static Map<String, String> subjectLanguages(JsonNode plan, Set<String> subjects) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : subjects) {
            String voice = AudioPlan.voiceFor(plan, s, null);
            out.put(s, languageOf(voice, plan));
        }
        return out;
    }

    /** 从一个音色标识推导台词语言（返回给 LLM 看的语言名）。 */
    static String languageOf(String voice, JsonNode plan) {
        String v = voice == null ? "" : voice.trim();
        if (AudioPlan.isClone(v)) {
            AudioPlan.VoicePreset p = AudioPlan.presetById(plan, AudioPlan.cloneId(v));
            if (p != null) {
                return languageOfSample(p.name(), p.promptText());
            }
            return "中文";
        }
        return languageOfName(v);
    }

    /** 内置音色名 → 语言。 */
    static String languageOfName(String name) {
        String n = name == null ? "" : name;
        if (n.contains("英文") || n.toLowerCase().contains("english")) {
            return "English";
        }
        if (n.contains("日语") || n.contains("日文")) {
            return "日本語";
        }
        if (n.contains("韩语") || n.contains("韩文")) {
            return "한국어";
        }
        return "中文";
    }

    /** 克隆样本（名字 + 转写文本）→ 语言。 */
    static String languageOfSample(String name, String promptText) {
        String nameLang = languageOfName(name);
        if (!"中文".equals(nameLang)) {
            return nameLang;                       // 名字里已经写了“英文/日语/韩语”
        }
        String t = promptText == null ? "" : promptText;
        if (t.chars().anyMatch(c -> c >= 0x3040 && c <= 0x30FF)) {
            return "日本語";
        }
        if (t.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7AF)) {
            return "한국어";
        }
        if (isLatinText(t)) {
            return "English";
        }
        return "中文";
    }

    /** 问 LLM 要台词（subject 限定在已绑定角色内；每个角色用自己的台词语言）。 */
    private List<AiLine> askLines(ProjectContextPort.ProjectSnapshot project, JsonNode plan,
                                  JsonNode shot, Map<String, String> subjectLangs, String narrationVoice) {
        Set<String> boundSubjects = subjectLangs.keySet();
        String system = """
                你是竖屏短剧的编剧/对白指导。根据给定镜头的画面与项目人物，写 1~3 段**角色台词**。
                硬性约束（必须满足，否则配音会念不完）：
                1. 本镜「时长/字数上限」由用户消息给出：**所有段相加不得超过它**。
                2. **只写角色对白**（kind 固定为 dialogue）：每段都必须有说话人，且说话人只能从「可用人物」里选。
                3. **不要写旁白**（kind=narration / subject 留空）—— 旁白由用户手动新增，你写了会被丢掉。
                4. **台词语言跟随该角色的括号标注**（如“B21（English）”就用英文写，“关羽（中文）”就用中文写）；
                   不同角色可以不同语言，**不要互相翻译、不要写拼音/音译**。
                5. 配音固定自然语速 1.0×（不会提速），所以宁少勿长。
                6. 台词要口语化、贴合画面动作，符合人物身份与当下情绪；每段尽量简短（中文 8~25 字 / 英文 6~18 词）。
                7. 不要写画面描写、不要加引号外的解释。
                只输出 JSON，形如：
                {"lines":[{"kind":"dialogue","subject":"关羽","text":"来者何人！"},
                          {"kind":"dialogue","subject":"B21","text":"Target locked."}]}
                """;
        StringBuilder user = new StringBuilder();
        user.append("项目：").append(plan.path("title").asText("")).append('\n');
        user.append("主题：").append(plan.path("script").path("theme").asText("")).append('\n');
        user.append("一句话概要：").append(plan.path("logline").asText("")).append('\n');
        // 括号里标出每个角色的台词语言（P12：英文音色/英文样本克隆出来的音色 → 写英文）
        user.append("可用人物（仅可选这些作为对白说话人；括号内是该角色的台词语言）：")
                .append(boundSubjects.stream()
                        .map(s -> s + "（" + subjectLangs.get(s) + "）")
                        .collect(java.util.stream.Collectors.joining("、")))
                .append('\n');
        double dur = shot.path("duration_sec").asDouble(3);
        user.append("本镜：第 ").append(shot.path("shot_no").asInt()).append(" 镜");
        user.append("，时长 ").append(String.format(java.util.Locale.ROOT, "%.1f", dur)).append("s\n");
        user.append("时长上限：台词合计不超过 ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", secBudget(dur))).append("s")
                .append("（中文 ≈ ").append(String.format(java.util.Locale.ROOT, "%.1f", CHARS_PER_SEC)).append(" 字/秒，")
                .append("即最多约 ").append(charBudget(dur)).append(" 字；英文 ≈ ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", LATIN_CHARS_PER_SEC)).append(" 字符/秒，")
                .append("即最多约 ").append(charBudget(dur, true)).append(" 字符）")
                .append("；超出会溢出到下一镜\n");
        user.append("旁白音色：").append(narrationVoice).append('\n');
        user.append("景别：").append(shot.path("shot_size").asText("")).append('\n');
        user.append("运镜：").append(shot.path("camera_move").asText("")).append('\n');
        user.append("画面动作：").append(shot.path("action").asText("")).append('\n');
        String zh = shot.path("zh").asText("");
        if (!zh.isBlank()) {
            user.append("画面描述：").append(zh).append('\n');
        }
        user.append("请输出 JSON。");

        try {
            String raw = llm.generateJson(new LlmRequest(system, user.toString(),
                    plan.path("title").asText(""), plan.path("logline").asText(""), "video",
                    project.aspectRatio(), null, null));
            return parseLines(raw);
        } catch (Exception e) {
            log.warn("AI 台词生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 LLM 返回的台词（容忍多余字段/代码块围栏）。 */
    static List<AiLine> parseLines(String raw) {
        List<AiLine> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String s = raw.trim();
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) {
            return out;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(s.substring(b, e + 1));
            for (JsonNode n : root.path("lines")) {
                String text = n.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                String kind = n.path("kind").asText("").trim();
                String subject = n.path("subject").asText("").trim();
                out.add(new AiLine(text, kind.isEmpty() ? (subject.isEmpty() ? "narration" : "dialogue") : kind, subject));
            }
        } catch (Exception ignored) {
            // 返回非法 JSON → 当作没产出
        }
        return out;
    }

    /**
     * 把 AI 台词铺到镜头时间轴上（纯函数，便于单测）。
     *
     * <p>P12 口径：**AI 台词只写角色对白**（旁白由用户手动新增），所以：
     * <ol>
     *   <li>没写说话人（= 旁白）或说话人不在 {@code boundSubjects} 里 → **丢弃**（旁白改由用户手动加）。</li>
     *   <li>对白按自然语速 {@link #NATURAL_SPEED} 从镜头发端顺铺；超出镜头也**不提速、不缩短、不延长镜头**，
     *       允许溢出到下一镜（渲染层已支持）。</li>
     * </ol>
     */
    static FitResult fitLinesDetailed(List<AiLine> ai, double shotDur, Set<String> boundSubjects) {
        List<String> notes = new ArrayList<>();
        List<FittedLine> out = new ArrayList<>();
        int droppedNarration = 0;
        int droppedUnbound = 0;
        for (AiLine raw : ai) {
            String text = raw.text() == null ? "" : raw.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            String subject = raw.subject() == null ? "" : raw.subject().trim();
            if (subject.isEmpty()) {
                droppedNarration++;      // AI 不该写旁白：旁白让用户“新增一段”自己加
                continue;
            }
            if (boundSubjects == null || !boundSubjects.contains(subject)) {
                droppedUnbound++;        // 说话人不在已绑定角色里 → 不能指着不存在的角色说话
                continue;
            }
            out.add(new FittedLine(0, 0, text, "dialogue", subject, NATURAL_SPEED));
        }

        double usable = Math.max(1.0, shotDur);
        List<FittedLine> placed = new ArrayList<>();
        double cursor = 0;
        for (FittedLine l : out) {
            double d = estimate(l.text());
            placed.add(new FittedLine(round1(cursor), round1(cursor + d), l.text(), l.kind(), l.subject(), l.speed()));
            cursor = cursor + d + GAP_SEC;
        }
        if (droppedNarration > 0) {
            notes.add("AI 多写了 " + droppedNarration + " 段旁白，已按默认口径丢开（需要旁白请在下方面配音时间轴上「新增一段」）");
        }
        if (droppedUnbound > 0) {
            notes.add("丢弃 " + droppedUnbound + " 段说话人未绑定的台词（名字不在「可用人物」里）");
        }
        double end = placed.isEmpty() ? 0 : placed.get(placed.size() - 1).endSec();
        if (end > usable + 0.05) {
            notes.add("台词约 " + fmt(end) + "s 超出镜头 " + fmt(usable)
                    + "s —— 已按自然语速 1.0× 铺排并允许溢出到下一镜（未自动提速、未延长镜头）");
        }
        return new FitResult(placed, notes);
    }

    /** 兼容旧调用：只要铺好的行。 */
    static List<FittedLine> fitLines(List<AiLine> ai, double shotDur, Set<String> boundSubjects) {
        return fitLinesDetailed(ai, shotDur, boundSubjects).lines();
    }

    /** 去掉空白后的字数（与 {@link #estimate} 同一口径）。 */
    static int chars(String text) {
        return text == null ? 0 : text.replaceAll("\\s", "").length();
    }

    /** 铺给 AI 的字数预算：镜头时长能读完的字数 ×{@link #BUDGET_RATIO}（中文口径）。 */
    static int charBudget(double shotDur) {
        return charBudget(shotDur, false);
    }

    /** 铺给 AI 的字数预算（latin=true 用英文速度）。 */
    static int charBudget(double shotDur, boolean latin) {
        double rate = latin ? LATIN_CHARS_PER_SEC : CHARS_PER_SEC;
        return Math.max(0, (int) Math.floor(Math.max(0, shotDur) * rate * BUDGET_RATIO));
    }

    /** 可用时长预算（秒）：镜头时长 × 安全系数。 */
    static double secBudget(double shotDur) {
        return Math.max(0, shotDur) * BUDGET_RATIO;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 中文朗读时长估算（秒）——按文本语种选速度（中文 4.5 字/秒；英文 13 字符/秒）。 */
    static double estimate(String text) {
        return Math.max(MIN_LINE_SEC, chars(text) / charsPerSec(text));
    }

    /** 该文本的朗读速度（字符/秒）。 */
    static double charsPerSec(String text) {
        return isLatinText(text) ? LATIN_CHARS_PER_SEC : CHARS_PER_SEC;
    }

    /** 拉丁字母占比过半 → 当英文处理（中文/日文/韩文都走 {@link #CHARS_PER_SEC}）。 */
    static boolean isLatinText(String text) {
        if (text == null) {
            return false;
        }
        int letters = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '，' || c == '。' || c == '、' || c == '！' || c == '？') {
                continue;
            }
            total++;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                letters++;
            }
        }
        return total > 0 && letters * 2 > total;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ---------------------------------------------------------------- B. 配乐

    public record MusicResult(String source, List<AudioPlan.MusicCue> cues, List<String> notes) {
    }

    @Transactional(readOnly = true)
    public MusicResult generateMusic(UUID userId, UUID workspaceId, UUID projectId, UUID revisionId) {
        guard.requireMember(userId, workspaceId);
        ProjectContextPort.ProjectSnapshot project = projects.require(userId, workspaceId, projectId);
        JsonNode plan = planReader.revisionPlan(revisionId);
        if (!"video".equals(plan.path("mode").asText(""))) {
            throw new BizException(ErrorCode.VALIDATION, "仅视频方案支持生成配乐");
        }
        double total = plan.path("duration_sec").asDouble(0);
        if (total <= 0) {
            throw new BizException(ErrorCode.VALIDATION, "方案缺少总时长，无法铺配乐");
        }

        String system = """
                你是短片作曲/音乐指导。根据剧情把整片划分成 2~5 段配乐，给出每段的情绪（mood）与强弱。
                要求：
                1. 段落要跟剧情节奏走（如开场铺陈偏弱、冲突/追逐段转急促、结尾回落）；
                2. 情绪用中文短语，尽量从这些里选（也可自定义）：史诗磅礴、温暖治愈、紧张悬疑、空灵神秘、
                   浪漫柔情、轻快活泼、古典中国风、电子科技感、悲伤低沉；
                3. gain_db 是该段音量（dB，0=原始，越低越轻；常用范围 -18 ~ -6，开场可用 -16 左右）；
                4. 段落必须首尾相接、覆盖全片，不要留空隙、不要重叠；
                5. 只输出 JSON，形如：
                {"music":[{"start_sec":0,"end_sec":16,"mood":"空灵神秘","gain_db":-16.5},
                          {"start_sec":16,"end_sec":24,"mood":"紧张悬疑","gain_db":-8}]}
                """;
        StringBuilder user = new StringBuilder();
        user.append("项目：").append(plan.path("title").asText("")).append('\n');
        user.append("主题：").append(plan.path("script").path("theme").asText("")).append('\n');
        user.append("一句话概要：").append(plan.path("logline").asText("")).append('\n');
        user.append("全片时长：").append(String.format(java.util.Locale.ROOT, "%.1f", total)).append("s\n");
        for (JsonNode act : plan.path("script").path("acts")) {
            user.append("段落：").append(act.path("name").asText(""));
            user.append('（').append(String.format(java.util.Locale.ROOT, "%.1f", act.path("start_sec").asDouble(0)));
            user.append("s ~ ").append(String.format(java.util.Locale.ROOT, "%.1f", act.path("end_sec").asDouble(0)));
            user.append("s）：").append(act.path("purpose").asText("")).append('\n');
        }
        for (JsonNode s : plan.path("shots")) {
            user.append("第 ").append(s.path("shot_no").asInt()).append(" 镜：")
                    .append(s.path("action").asText(s.path("zh").asText(""))).append('\n');
        }
        user.append("请输出 JSON。");

        List<String> notes = new ArrayList<>();
        List<AudioPlan.MusicCue> cues;
        try {
            String raw = llm.generateJson(new LlmRequest(system, user.toString(),
                    plan.path("title").asText(""), plan.path("logline").asText(""), "video",
                    project.aspectRatio(), null, null));
            cues = parseMusic(raw, total);
        } catch (Exception e) {
            log.warn("AI 配乐生成失败: {}", e.getMessage());
            cues = List.of();
        }
        if (cues.isEmpty()) {
            notes.add("AI 没有给出可用配乐段落（可手动添加或检查 LLM 配置）");
        } else {
            notes.add("已生成 " + cues.size() + " 段配乐；点「生成配乐」后按情绪渲染，同一情绪只生成一次曲子");
        }
        return new MusicResult(llm.source(), cues, notes);
    }

    /** 解析并归一化 AI 配乐：排序、裁到成片时长、补空隙、去重叠（纯函数，便于单测）。 */
    static List<AudioPlan.MusicCue> parseMusic(String raw, double total) {
        List<AudioPlan.MusicCue> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String s = raw.trim();
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) {
            return out;
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(s.substring(b, e + 1));
        } catch (Exception ex) {
            return out;
        }
        int i = 0;
        for (JsonNode m : root.path("music")) {
            i++;
            double st = m.path("start_sec").asDouble(0);
            double en = m.path("end_sec").asDouble(0);
            String mood = m.path("mood").asText("").trim();
            if (en <= st || mood.isEmpty()) {
                continue;
            }
            double gain = m.path("gain_db").isNumber() ? m.path("gain_db").asDouble() : AudioPlan.DEFAULT_MUSIC_GAIN_DB;
            out.add(new AudioPlan.MusicCue("m" + i, Math.max(0, st), Math.min(total, en), mood, gain,
                    0.5, 1.0, true, true));
        }
        out.sort(Comparator.comparingDouble(AudioPlan.MusicCue::startSec));
        // 首段从 0 起、段间无空隙、不超总长
        List<AudioPlan.MusicCue> fixed = new ArrayList<>();
        double cursor = 0;
        for (AudioPlan.MusicCue c : out) {
            double st = fixed.isEmpty() ? 0 : Math.max(cursor, c.startSec());
            double en = Math.min(total, Math.max(st + 1.0, c.endSec()));
            if (en <= st) {
                continue;
            }
            fixed.add(new AudioPlan.MusicCue(c.id(), round1(st), round1(en), c.mood(), c.gainDb(),
                    c.fadeInSec(), c.fadeOutSec(), c.loop(), c.duck()));
            cursor = en;
        }
        // 末段补到总长（保证覆盖全片）
        if (!fixed.isEmpty()) {
            AudioPlan.MusicCue last = fixed.get(fixed.size() - 1);
            if (last.endSec() < total - 0.05) {
                fixed.set(fixed.size() - 1, new AudioPlan.MusicCue(last.id(), last.startSec(), round1(total),
                        last.mood(), last.gainDb(), last.fadeInSec(), last.fadeOutSec(), last.loop(), last.duck()));
            }
        }
        return fixed;
    }

    /** 供控制器构造统一响应（避免直接暴露内部 record）。 */
    public static ObjectNode linesToJson(ObjectMapper m, LinesResult r) {
        ObjectNode root = m.createObjectNode();
        root.put("source", r.source());
        var arr = root.putArray("shots");
        for (ShotLines sl : r.shots()) {
            ObjectNode o = arr.addObject();
            o.put("shotNo", sl.shotNo());
            o.put("shotDurationSec", sl.shotDurationSec());
            var ls = o.putArray("lines");
            for (FittedLine f : sl.lines()) {
                ObjectNode l = ls.addObject();
                l.put("at_sec", f.atSec());
                l.put("end_sec", f.endSec());
                l.put("text", f.text());
                l.put("kind", f.kind());
                if (f.subject() != null) {
                    l.put("subject", f.subject());
                }
                l.put("speed", f.speed());
            }
        }
        var notes = root.putArray("notes");
        r.notes().forEach(notes::add);
        return root;
    }
}
