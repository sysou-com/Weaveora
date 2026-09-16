package studio.weaveora.script;

import org.springframework.core.io.ClassPathResource;
import studio.weaveora.script.api.ScriptConflict;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptEpisode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 剧本 AI 提示词**单一真源**（系统词 + user 组装 + Stub 兜底）。
 *
 * <p>与 {@code prompts/script_*.md} 双写：外部文件缺失时用内置兜底，两处必须同口径
 * （沿用项目铁律「改提示词三处同口径」的精神——剧本这边是「Java 兜底与 md 同步」）。
 */
public final class ScriptPrompts {

    /** 字段生成/更新的目标长度（Q2：输入上限 8000，AI 硬要求 ≥4000 字）。 */
    public static final int FIELD_MIN_CHARS = 4000;
    public static final int FIELD_MAX_CHARS = 8000;
    /** 单集正文长度（AI 生成时的目标区间）。 */
    public static final int EPISODE_MIN_CHARS = 4000;
    public static final int EPISODE_MAX_CHARS = 8000;
    /** 「精简的故事」上限（唯一连续记忆，越短越稳）。 */
    public static final int CONDENSED_MAX_CHARS = 1500;
    /** 转成项目时 AI 精简 brief 的上限（公开 brief 限 2000，这里留余量）。 */
    public static final int BRIEF_MAX_CHARS = 1800;
    /** 注入上下文时，每集正文最多带多少字。 */
    private static final int CONTEXT_EPISODE_CHARS = 1500;

    private ScriptPrompts() {
    }

    // ------------------------------------------------------------ system

    public static String fieldSystem() {
        return load("script_field_system.md", FIELD_SYSTEM_FALLBACK);
    }

    public static String episodeSystem() {
        return load("script_episode_system.md", EPISODE_SYSTEM_FALLBACK);
    }

    public static String condenseSystem() {
        return load("script_condense_system.md", CONDENSE_SYSTEM_FALLBACK);
    }

    public static String guideSystem() {
        return load("script_guide_system.md", GUIDE_SYSTEM_FALLBACK);
    }

    private static final String FIELD_SYSTEM_FALLBACK = """
            你是资深影视编剧与剧本医生。用户会给出一个剧本的标题、类型，以及已有的全部创作内容。
            你每次只负责**一个字段**，输出必须是纯 JSON，不要解释、不要 Markdown 围栏。
            硬规则：
            1. 内容长度必须不少于 %d 个中文字符（硬要求；不足则继续补充具体细节，禁止空话凑字）；
            2. 与剧本类型/题材保持一致，不得与已写章节或其它要素矛盾；
            3. 人物要立体：性格特征、背景经历、动机与目标、与其他角色的关系都要落到具体；
            4. 可用小标题、分条、表格化文本组织，便于编剧直接使用；
            5. 语言与「剧本语言」要素一致；戏曲/歌剧可含唱词。
            只输出 JSON：{"field":"<字段key>","value":"<内容>","note":"<一句话说明改了什么>","changed":true}
            """.formatted(FIELD_MIN_CHARS);

    private static final String EPISODE_SYSTEM_FALLBACK = """
            你是本剧的编剧。请依据「精简的故事」（唯一连续记忆）、剧本要素与最近几集，写出**下一集**。
            硬规则：
            1. 严格承接精简故事中的既有事实（人物性格/关系/动机、已发生事件、伏笔与未回收钩子、场景与年代），
               不得出现任何矛盾；如需推进，只能在此前事实之上推进；
            2. 按「剧本情节结构」判断当前处于 开端/发展/转折/高潮/结局 的哪一段，并让本集承担该阶段的功能；
            3. 本集必须有明确冲突（人与人 / 人与环境 / 人物内心），有推进、有转折，结尾留钩子；
            4. 语言遵守「剧本语言」的风格；对话与舞台说明分开书写，舞台说明放在【】内；
            5. 正文不少于 %d 字、不多于 %d 字。
            只输出 JSON：{"episodeNo":n,"title":"...","summary":"<=120字","content":"..."}
            """.formatted(EPISODE_MIN_CHARS, EPISODE_MAX_CHARS);

    private static final String CONDENSE_SYSTEM_FALLBACK = """
            你在维护一部剧的「精简的故事」——它是唯一的连续记忆，供后续每一集生成使用。
            输入：现有精简故事；剧本要素；全部已发布集（编号/标题/摘要/正文节选）。
            任务：
            1. 重写「精简的故事」：按时间顺序保留所有已确立的事实（人物关系、已发生事件、伏笔与未回收钩子、
               关键场景与年代），删除文学化描写与对白，压缩到 %d 字以内；
            2. 找出既有集之间、或集与要素之间的矛盾/断裂，**列出受影响的既有集**及需要修改的要点（无则为空数组）；
            3. 标注「情节结构」已经完成的部分（开端/发展/转折/高潮/结局）。
            只输出 JSON：{"condensedStory":"...","completedBeats":["开端"],
              "conflicts":[{"episodeNo":2,"title":"...","issue":"...","fix":"..."}]}
            """.formatted(CONDENSED_MAX_CHARS);

    private static final String GUIDE_SYSTEM_FALLBACK = """
            你是这部剧的剧本医生，负责引导作者把剧本写完。
            基于「精简的故事」与「剧本情节结构」，判断剧本目前走到哪一步，并给出下一步该怎么写。
            要求：建议必须具体、可执行，不要泛泛而谈。
            只输出 JSON：{"stage":"开端|发展|转折|高潮|结局","missingBeats":["高潮","结局"],
              "suggestions":["...","..."],"estimatedRemainingEpisodes":4}
            """;

    private static String load(String file, String fallback) {
        try {
            var res = new ClassPathResource("prompts/" + file);
            if (!res.exists()) return fallback;
            return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ user

    /** 字段生成 / 更新。 */
    public static String fieldUser(Script s, List<ScriptEpisode> episodes, ScriptField field,
                                   String hint, String currentValue, boolean fromContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("【字段】").append(field.label()).append("（key=").append(field.key()).append("）\n");
        sb.append("【字段说明】").append(field.help()).append("\n\n");
        sb.append("【剧本标题】").append(s.title()).append('\n');
        sb.append("【剧本类型】").append(s.genre()).append('\n');
        if (fromContent) {
            sb.append('\n').append(elementsBlock(s));
            sb.append('\n').append(episodesBlock(episodes, true));
            if (currentValue != null && !currentValue.isBlank()) {
                sb.append("\n【本字段当前内容（请在此基础上更新，保留可取部分）】\n")
                        .append(clip(currentValue, FIELD_MAX_CHARS)).append('\n');
            }
            sb.append("\n请重写并更新该字段，使其与已写章节完全一致、不产生矛盾。");
        } else {
            sb.append("\n请仅凭标题与类型，生成该字段的专业内容。");
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("\n【用户补充要求】").append(hint.trim()).append('\n');
        }
        sb.append("\n长度要求：不少于 ").append(FIELD_MIN_CHARS).append(" 字。请输出 JSON。");
        return sb.toString();
    }

    /** 下一集。 */
    public static String episodeUser(Script s, List<ScriptEpisode> episodes, int nextNo,
                                     String titleHint, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(elementsBlock(s));
        sb.append('\n').append(episodesBlock(episodes, false));
        sb.append("\n【本集编号】第 ").append(nextNo).append(" 集\n");
        if (titleHint != null && !titleHint.isBlank()) {
            sb.append("【标题提示】").append(titleHint.trim()).append('\n');
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("【用户对下一集的额外要求】").append(instruction.trim()).append('\n');
        }
        sb.append("\n请写出第 ").append(nextNo).append(" 集的完整内容（不少于 ")
                .append(EPISODE_MIN_CHARS).append(" 字）。请输出 JSON。");
        return sb.toString();
    }

    /** 精简故事刷新 + 一致性检查。 */
    public static String condenseUser(Script s, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【现有精简的故事】\n")
                .append(s.condensedStory() == null || s.condensedStory().isBlank()
                        ? "（尚无，请从下面各集内容建立）" : clip(s.condensedStory(), CONDENSED_MAX_CHARS))
                .append("\n\n");
        sb.append(elementsBlock(s));
        sb.append('\n').append(episodesBlock(episodes, true));
        sb.append("\n请输出 JSON（精简故事 ≤ ").append(CONDENSED_MAX_CHARS).append(" 字）。");
        return sb.toString();
    }

    /** AI 引导。 */
    public static String guideUser(Script s, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append(elementsBlock(s));
        sb.append("\n【已完成集数】").append(episodes.size()).append(" 集\n");
        sb.append(episodesBlock(episodes, false));
        sb.append("\n请判断当前阶段并给出下一步建议。请输出 JSON。");
        return sb.toString();
    }

    /** 应用一致性改动：一次重写多集（Q4：用户确认后才调用）。 */
    public static String rewriteEpisodesUser(Script s, List<ScriptConflict> items, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append(elementsBlock(s));
        sb.append("\n【精简的故事】\n").append(clip(s.condensedStory(), CONDENSED_MAX_CHARS)).append("\n\n");
        sb.append("【需要修改的既有集与修改要点】\n");
        for (ScriptConflict c : items) {
            sb.append("- 第 ").append(c.episodeNo()).append(" 集《").append(nz(c.title())).append("》：")
                    .append("问题=").append(nz(c.issue())).append("；修改要点=")
                    .append(nz(c.fix())).append('\n');
        }
        sb.append("\n【这些集的当前正文（按下标对应，请按修改要点重写）】\n");
        for (ScriptConflict c : items) {
            ScriptEpisode e = findByNo(episodes, c.episodeNo());
            sb.append("\n第 ").append(c.episodeNo()).append(" 集《")
                    .append(e == null ? nz(c.title()) : e.title()).append("》正文：\n")
                    .append(e == null ? "（缺失）" : clip(e.content(), 8000)).append('\n');
        }
        sb.append("""
                
                要求：只改与修改要点相关的部分，保留其余内容与风格；改写后仍不得与精简故事矛盾。
                只输出 JSON：{"episodes":[{"episodeNo":n,"title":"...","summary":"<=120字","content":"..."}]}
                （只输出上面列出的集，顺序不限。）
                """);
        return sb.toString();
    }

    /** 转成项目时把整集压缩成 brief。 */
    public static String briefUser(Script s, ScriptEpisode e) {
        return """
                下面是一部剧的要素与某一集的正文。请把它压缩成一段**给视频导演用的 brief**：
                保留本集的场景年代、人物、核心事件与冲突、情绪走向与结尾钩子，删去对白原文与文学描写；
                用紧凑的陈述句，不超过 %d 字。只输出 JSON：{"brief":"..."}

                【剧本标题】%s
                【剧本类型】%s
                【精简的故事】
                %s

                【本集】第 %d 集《%s》
                %s
                """.formatted(BRIEF_MAX_CHARS, s.title(), s.genre(),
                clip(s.condensedStory(), CONDENSED_MAX_CHARS), e.episodeNo(), e.title(),
                clip(e.content(), 8000));
    }

    // ------------------------------------------------------------ blocks

    /** 7 个要素块（标题/类型 + 6 个内容要素）。 */
    public static String elementsBlock(Script s) {
        return "【剧本标题】" + s.title() + "\n"
                + "【剧本类型】" + s.genre() + "\n"
                + "【剧本人物及人物介绍】\n" + nz(s.characters()) + "\n"
                + "【剧本故事】\n" + nz(s.story()) + "\n"
                + "【剧本冲突】\n" + nz(s.conflict()) + "\n"
                + "【剧本情节结构】\n" + nz(s.plotStructure()) + "\n"
                + "【剧本语言】\n" + nz(s.language()) + "\n"
                + "【舞台说明】\n" + nz(s.stageDirections()) + "\n"
                + "【精简的故事】\n" + nz(s.condensedStory()) + "\n";
    }

    /**
     * 已写集数块。
     *
     * @param withContent true=带正文节选（用于一致性检查/字段更新）；false=只带标题+摘要（用于下一集，控上下文）
     */
    public static String episodesBlock(List<ScriptEpisode> episodes, boolean withContent) {
        if (episodes == null || episodes.isEmpty()) {
            return "【已写集数】无（这是第一集）\n";
        }
        StringBuilder sb = new StringBuilder("【已写集数】共 ").append(episodes.size()).append(" 集\n");
        for (ScriptEpisode e : episodes) {
            sb.append("\n第 ").append(e.episodeNo()).append(" 集《").append(nz(e.title())).append("》\n");
            sb.append("摘要：").append(nz(e.summary().isBlank() ? clip(e.content(), 120) : e.summary())).append('\n');
            if (withContent) {
                sb.append("正文节选：").append(clip(e.content(), CONTEXT_EPISODE_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    private static ScriptEpisode findByNo(List<ScriptEpisode> episodes, Integer no) {
        if (no == null) return null;
        for (ScriptEpisode e : episodes) {
            if (e.episodeNo() == no) return e;
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
