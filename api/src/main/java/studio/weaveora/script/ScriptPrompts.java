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
    /**
     * **分段续写**（2026-09-17 实测修复）：LLM 单次输出被 `max_tokens=8000` 截断，
     * 一次要「≥4000 字」必定 `finish_reason=length` → 422。改为每次只写一段（约 2200 字，≈3300 tokens）
     * 再拼接，既绕过截断又能稳定达标；2200×2=4400 ≥ 4000，所以常规只需 2 段。
     */
    public static final int FIELD_PASS_CHARS = 2200;
    public static final int EPISODE_PASS_CHARS = 2200;
    /** 最多续写几段（防跑偏 + 兜底上限） */
    public static final int MAX_PASSES = 3;
    /** 单段硬上限（字数）：超过就会被 `max_tokens` 截断，提示词里必须显式让模型自己收住 */
    public static final int MAX_SEGMENT_CHARS = 2600;
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
            你每次只负责**一个字段的一段**，输出必须是纯 JSON，不要解释、不要 Markdown 围栏。
            硬规则：
            1. **每次只写一段**（约 %d 字）：写完即停；\n               【硬上限】**严格不超过 2600 字** —— 超出会被接口截断，你必须自己收住，宁可少写不要写满；\n               【禁止】写总结、结语、“综上所述”式收尾——系统会把多段拼成完整字段，你提前收尾会让后面无话可写；
            2. 若给定了「已有前文」，必须**接着写、不得重复**，也不要重写前文已讲的要点；
            3. 与剧本类型/题材保持一致，不得与已写章节或其它要素矛盾；
            4. 人物要立体：性格特征、背景经历、动机与目标、与其他角色的关系都要落到具体；
            5. 可用小标题、分条、表格化文本组织，便于编剧直接使用；
            6. 语言与「剧本语言」要素一致；戏曲/歌剧可含唱词。
            只输出 JSON：{"field":"<字段key>","value":"<本段内容>","note":"<本段要点，可选>"}\n            【格式】JSON 字符串内**不得出现真实换行**（需要分段请写 \\n 转义），不要输出注释或多余文字。
            """.formatted(FIELD_PASS_CHARS);

    private static final String EPISODE_SYSTEM_FALLBACK = """
            你是本剧的编剧。请依据「精简的故事」（唯一连续记忆）、剧本要素与最近几集，写出**下一集的一段**。
            硬规则：
            1. **每次只写一段**（约 %d 字，允许 ±300）：本集会被拆成 2–3 段依次生成（起 / 承转 / 合与钩子），
               你只写当前这一段，写完即停；\n               【硬上限】**严格不超过 2600 字** —— 超出会被接口截断（本集就废了），必须自己收住；\n               若给定了「已有前文」，必须**接着写、不得重复**；
            2. 严格承接精简故事中的既有事实（人物性格/关系/动机、已发生事件、伏笔与未回收钩子、场景与年代），
               不得出现任何矛盾；如需推进，只能在此前事实之上推进；
            3. 按「剧本情节结构」判断当前处于 开端/发展/转折/高潮/结局 的哪一段，并让本集承担该阶段的功能；
            4. 本集必须有明确冲突（人与人 / 人与环境 / 人物内心），有推进、有转折；
            5. 语言遵守「剧本语言」的风格；对话与舞台说明分开书写，舞台说明放在【】内。
            只输出 JSON：{"title":"<本集标题>","summary":"<本集摘要，<=120字>","content":"<本段正文>"}\n            （第 2/3 段的 title/summary 可与第 1 段相同，但**必须都给出**，以保持输出结构一致）\n            【格式】JSON 字符串内**不得出现真实换行**（需要分段请写 \\n 转义），不要输出注释或多余文字。
            """.formatted(EPISODE_PASS_CHARS);

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

    /** 字段生成 / 更新（**一次一段**：`pass` 从 1 开始，`soFar` 是已生成的前文）。 */
    public static String fieldUser(Script s, List<ScriptEpisode> episodes, ScriptField field,
                                   String hint, String currentValue, boolean fromContent,
                                   int pass, String soFar) {
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
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("\n【用户补充要求】").append(hint.trim()).append('\n');
        }
        sb.append('\n').append(passInstruction(pass, soFar,
                fromContent ? "请重写并更新该字段" : "请仅凭标题与类型生成该字段"));
        return sb.toString();
    }

    /** 下一集（**一次一段**：第 1 段给出标题/摘要，后续段只续正文）。 */
    public static String episodeUser(Script s, List<ScriptEpisode> episodes, int nextNo,
                                     String titleHint, String instruction, int pass, String soFar) {
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
        if (pass <= 1) {
            sb.append("\n【本段任务】写本集的**第 1 段（起）**：引入本集场景与人物、抛出本集的核心冲突。\n");
        } else if (pass == 2) {
            sb.append("\n【本段任务】写本集的**第 2 段（承 / 转）**：承接前文推进冲突、制造转折。\n");
        } else {
            sb.append("\n【本段任务】写本集的**收束段（合）**：解决或升级冲突，并在结尾留下钩子。\n");
        }
        sb.append(passInstruction(pass, soFar,
                "写出第 " + nextNo + " 集的本段正文"));
        sb.append("【本集总量】本集目标总共约 ").append(EPISODE_MIN_CHARS).append("–")
                .append(EPISODE_MAX_CHARS)
                .append(" 字，会被拆成 2–3 段依次生成；**你只写当前这一段，不要在本段把整集收尾**")
                .append("（除非本段任务写的是收束段）。\n");
        return sb.toString();
    }

    /** 分段续写的公共指令（长度目标 + 不重复 + 不提前收尾）。 */
    private static String passInstruction(int pass, String soFar, String task) {
        StringBuilder sb = new StringBuilder();
        if (pass > 1 && soFar != null && !soFar.isBlank()) {
            sb.append("【已有前文（**勿重复**，只用于衔接）】\n")
                    .append(clip(tail(soFar, 1400), 1400))
                    .append("\n\n");
            sb.append(task).append(" 的**第 ").append(pass).append(" 段**")
                    .append("（约 ").append(FIELD_PASS_CHARS).append(" 字，**严格不超过 ")
                    .append(MAX_SEGMENT_CHARS).append(" 字**）：")
                    .append("接着上文继续写，**不得重复前文已讲内容**，也不要重写要点；写完本段即停，不要写全文总结。\n");
        } else {
            sb.append(task).append(" 的**第 1 段**（约 ").append(FIELD_PASS_CHARS)
                    .append(" 字，**严格不超过 ").append(MAX_SEGMENT_CHARS).append(" 字**）：")
                    .append("先给出总体定位，再展开最前面的 2–3 个要点；写完本段即停，**不要写总结/结语**（后面还有段落）。\n");
        }
        sb.append("只输出 JSON：{\"value\":\"<本段内容>\",\"note\":\"<本段要点，可选>\"}");
        return sb.toString();
    }

    /** 取字符串末尾（续写时给模型看的上文尾巴）。 */
    private static String tail(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(t.length() - max);
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
