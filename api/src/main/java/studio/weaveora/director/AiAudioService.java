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
import java.util.LinkedHashSet;
import java.util.List;
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
 *   <li>时长铺排：按中文 4.5 字/秒估算，段间留 0.25s 间隙；总长超出镜头时统一提语速（≤2.0×）。</li>
 * </ol>
 */
@Service
public class AiAudioService {

    private static final Logger log = LoggerFactory.getLogger(AiAudioService.class);

    /** 中文朗读估算速度（字/秒）——与前端 NarrationTimeline 的估算保持一致 */
    public static final double CHARS_PER_SEC = 4.5;
    /** 段间最小间隙（秒） */
    public static final double GAP_SEC = 0.25;
    /** 单段最短时长（秒） */
    public static final double MIN_LINE_SEC = 0.8;
    public static final double MAX_SPEED = 2.0;
    public static final double MIN_SPEED = 0.5;

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
            List<AiLine> ai = askLines(project, plan, shot, boundSubjects, narrationVoice);
            if (ai.isEmpty()) {
                notes.add("第 " + no + " 镜：AI 没有给出可用台词（已跳过）");
                continue;
            }
            List<FittedLine> fitted = fitLines(ai, dur, boundSubjects);
            if (fitted.isEmpty()) {
                continue;
            }
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

    /** 问 LLM 要台词（subject 限定在已绑定角色内）。 */
    private List<AiLine> askLines(ProjectContextPort.ProjectSnapshot project, JsonNode plan,
                                  JsonNode shot, Set<String> boundSubjects, String narrationVoice) {
        String system = """
                你是竖屏短剧的编剧/对白指导。根据给定镜头的画面与项目人物，写 1~3 段台词。
                要求：
                1. 台词要口语化、贴合画面动作，符合人物身份与当下情绪；
                2. 优先写「角色对白」（dialogue），必要时补一句旁白（narration）交代背景；
                3. 说话人只能从「可用人物」里选；旁白把 subject 留空；
                4. 每段尽量简短（中文 8~25 字），总字数要能在镜头时长内念完；
                5. 不要写画面描写、不要加引号外的解释。
                只输出 JSON，形如：
                {"lines":[{"kind":"dialogue","subject":"关羽","text":"来者何人！"},
                          {"kind":"narration","subject":"","text":"刀光一闪。"}]}
                """;
        String characters = boundSubjects.isEmpty()
                ? "（本项目还没有绑定角色；请只写旁白 narration，subject 留空）"
                : String.join("、", boundSubjects);
        StringBuilder user = new StringBuilder();
        user.append("项目：").append(plan.path("title").asText("")).append('\n');
        user.append("主题：").append(plan.path("script").path("theme").asText("")).append('\n');
        user.append("一句话概要：").append(plan.path("logline").asText("")).append('\n');
        user.append("可用人物（仅可选这些作为对白说话人）：").append(characters).append('\n');
        user.append("旁白音色：").append(narrationVoice).append('\n');
        user.append("本镜：第 ").append(shot.path("shot_no").asInt()).append(" 镜");
        user.append("，时长 ").append(String.format(java.util.Locale.ROOT, "%.1f", shot.path("duration_sec").asDouble(3))).append("s\n");
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
     * <p>规则：
     * <ul>
     *   <li>说话人不在 {@code boundSubjects} 里 → 降级为旁白（subject 置空），避免指向不存在的角色。</li>
     *   <li>每段时长按 {@link #CHARS_PER_SEC} 估算，段间留 {@link #GAP_SEC}；末段不超过镜头时长。</li>
     *   <li>总长超出镜头 → 统一提语速（≤{@link #MAX_SPEED}）把总长压进镜头；压不进就按语速上限铺、并允许溢出。</li>
     * </ul>
     */
    static List<FittedLine> fitLines(List<AiLine> ai, double shotDur, Set<String> boundSubjects) {
        List<FittedLine> out = new ArrayList<>();
        double usable = Math.max(1.0, shotDur);
        // 1) 估算总长（单位：按 1.0 语速的秒数）
        double sum = 0;
        for (AiLine l : ai) {
            sum += estimate(l.text()) + GAP_SEC;
        }
        sum = Math.max(0, sum - GAP_SEC);
        // 2) 需要的语速（挤不下就提，最多 2.0×）
        double speed = 1.0;
        if (sum > usable && sum > 0) {
            // 注意 /100.0：写成 /100 是**整数除法**，会把语速截断成整数（实测踩过）
            speed = Math.min(MAX_SPEED, Math.max(MIN_SPEED, Math.round((sum / usable) * 100) / 100.0));
        }
        // 3) 依次铺排
        double cursor = 0;
        for (AiLine l : ai) {
            String kind = l.kind() == null ? "" : l.kind().trim();
            String subject = l.subject() == null ? "" : l.subject().trim();
            if (!subject.isEmpty() && (boundSubjects == null || !boundSubjects.contains(subject))) {
                subject = "";   // 不在已绑定角色里 → 当旁白
            }
            if (subject.isEmpty()) {
                kind = "narration";
            } else if (kind.isEmpty()) {
                kind = "dialogue";
            }
            double d = Math.max(MIN_LINE_SEC, estimate(l.text()) / speed);
            out.add(new FittedLine(round1(cursor), round1(cursor + d), l.text(), kind,
                    subject.isEmpty() ? null : subject, speed));
            cursor += d + GAP_SEC / Math.max(1.0, speed);
        }
        return out;
    }

    /** 中文朗读时长估算（秒）。 */
    static double estimate(String text) {
        int n = text == null ? 0 : text.replaceAll("\\s", "").length();
        return Math.max(MIN_LINE_SEC, n / CHARS_PER_SEC);
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
