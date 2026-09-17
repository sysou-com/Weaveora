package studio.weaveora.script;

import org.springframework.core.io.ClassPathResource;
import studio.weaveora.script.api.ScriptConflict;
import studio.weaveora.script.domain.Script;
import studio.weaveora.script.domain.ScriptEpisode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 剧本 AI 提示词**单一真源**（系统词 + 上下文组装 + user 拼装 + 兜底）。
 *
 * <p>与 {@code prompts/script_*.md} 双写：外部文件缺失时用内置兜底，两处必须同口径。
 *
 * <h3>三条硬口径（2026-09-17 上线实测后定型）</h3>
 * <ol>
 *   <li><b>输出侧：分段续写。</b>单次要求 ≥4000 字会被 `max_tokens=8000` 截断（`finish_reason=length` → 422）。
 *       所以每次只写一段（≤{@link #MAX_SEGMENT_CHARS}），按目标字数拆 1–5 段拼接。</li>
 *   <li><b>输入侧：上下文预算。</b>要素最长 8000 字 × 6 ≈ 4.8 万字，若全量注入，每段都重送一遍
 *       （成本/延迟线性膨胀），且会「中间丢失」——模型看不见埋在中间的约束。
 *       所以统一走 {@link #context} 组装：**目标字段全文 + 其它要素摘要 + 每集标题摘要 + 仅最近 2 集正文**，
 *       总量封顶 {@link #CONTEXT_BUDGET_CHARS}；被压缩/省略的项会记录在 notes 里（不静默）。</li>
 *   <li><b>结构先行：每段按提纲写。</b>先生成一份与段数 1:1 的提纲（每段 2–3 个子要点，≤600 字），
 *       再让每段「只写提纲第 K 条」——不跑偏、不重复、可显示进度；提纲同时回给前端展示。</li>
 * </ol>
 */
public final class ScriptPrompts {

    // ---------------------------------------------------------------- 长度口径

    /** 字段生成/更新的**默认**目标长度（用户可在弹窗里改，上限 8000）。 */
    public static final int FIELD_TARGET_DEFAULT = 4000;
    /** 用户可设的目标区间（用户 2026-09-17：最大值不超过 8000）。 */
    public static final int FIELD_TARGET_MIN = 500;
    public static final int FIELD_TARGET_MAX = 8000;
    /** 单集正文长度（AI 生成时的默认目标）。 */
    public static final int EPISODE_MIN_CHARS = 4000;
    public static final int EPISODE_MAX_CHARS = 8000;
    /** 单段**优先**字数（实际每段字数由 {@link #planFor(int)} 按目标算）。 */
    public static final int FIELD_PASS_CHARS = 2200;
    public static final int EPISODE_PASS_CHARS = 2200;
    /** 单段最少写多少（目标很小时不要拆得太碎）。 */
    public static final int PASS_MIN_CHARS = 400;
    /** 单段硬上限（字数）：超过就会被 `max_tokens` 截断，提示词里必须显式让模型自己收住。 */
    public static final int MAX_SEGMENT_CHARS = 2600;
    /** 最多续写几段（8000 字需 4 段，再留一段补写余量）。 */
    public static final int MAX_PASSES = 5;

    // ---------------------------------------------------------------- 上下文预算

    /** 【A】单次调用的上下文总预算（汉字≈token，12000 ≈ 12k token）。 */
    public static final int CONTEXT_BUDGET_CHARS = 12000;
    /** 【A】非目标要素的摘要上限（头 60% + 尾 40%）。 */
    public static final int ELEMENT_DIGEST_CHARS = 1200;
    /** 【A】要素摘要的下限（低于此值就整项省略并记录）。 */
    public static final int DIGEST_MIN_CHARS = 260;
    /** 【A】「精简的故事」上限（全剧唯一连续记忆，最高优先级）。 */
    public static final int CONDENSED_MAX_CHARS = 1500;
    /** 【A】单集正文节选上限 / 保留正文的最近集数。 */
    public static final int EPISODE_BODY_CHARS = 1200;
    public static final int RECENT_EPISODES_WITH_BODY = 2;
    /** 【A】分给「集列表」的预算（标题+摘要全量，正文只给最近几集）。 */
    public static final int EPISODES_RESERVE_CHARS = 3000;
    /** 转成项目时 AI 精简 brief 的上限（公开 brief 限 2000，这里留余量）。 */
    public static final int BRIEF_MAX_CHARS = 1800;
    /** 提纲条目的子要点数上限。 */
    private static final int OUTLINE_POINTS_MAX = 4;

    private ScriptPrompts() {
    }

    // ---------------------------------------------------------------- system

    public static String fieldSystem() {
        return load("script_field_system.md", FIELD_SYSTEM_FALLBACK);
    }

    public static String episodeSystem() {
        return load("script_episode_system.md", EPISODE_SYSTEM_FALLBACK);
    }

    public static String outlineSystem() {
        return load("script_outline_system.md", OUTLINE_SYSTEM_FALLBACK);
    }

    public static String condenseSystem() {
        return load("script_condense_system.md", CONDENSE_SYSTEM_FALLBACK);
    }

    public static String guideSystem() {
        return load("script_guide_system.md", GUIDE_SYSTEM_FALLBACK);
    }

    private static final String FIELD_SYSTEM_FALLBACK = """
            你是资深影视编剧与剧本医生。用户会给出一个剧本的标题、类型、已有创作内容，以及**本段的写作提纲**。
            你每次只负责**一个字段的一段**，输出必须是纯 JSON，不要解释、不要 Markdown 围栏。
            硬规则：
            1. **只写提纲指定的那一段**（篇幅以用户消息中给出的目标字数为准）：写完即停；\n               【硬上限】**严格不超过 %d 字** —— 超出会被接口截断，你必须自己收住，宁可少写不要写满；\n               【禁止】写总结、结语、“综上所述”式收尾，也**不要把提纲里其它段的内容写完**；
            2. 若给定了「已有前文」，必须**接着写、不得重复**；
            3. 与剧本类型/题材保持一致，不得与已写章节、其它要素或「精简的故事」矛盾；
            4. 人物要立体：性格特征、背景经历、动机与目标、与其他角色的关系都要落到具体；
            5. 可用小标题、分条、表格化文本组织，便于编剧直接使用；
            6. 语言与「剧本语言」要素一致；戏曲/歌剧可含唱词。
            只输出 JSON：{"field":"<字段key>","value":"<本段内容>","note":"<本段要点，可选>"}
            【格式】JSON 字符串内**不得出现真实换行**（需要分段请写 \\n 转义），不要输出注释或多余文字。
            """.formatted(MAX_SEGMENT_CHARS);

    private static final String EPISODE_SYSTEM_FALLBACK = """
            你是本剧的编剧。请依据「精简的故事」（全剧唯一连续记忆）、剧本要素、最近几集，以及**本段的写作提纲**，
            写出**下一集的一段**。
            硬规则：
            1. **只写提纲指定的那一段**（篇幅以用户消息中给出的目标字数为准）：写完即停；\n               【硬上限】**严格不超过 %d 字** —— 超出会被接口截断（本集就废了），必须自己收住；\n               若给定了「已有前文」，必须**接着写、不得重复**，也不要把后面段的内容提前写完；
            2. 严格承接精简故事中的既有事实（人物性格/关系/动机、已发生事件、伏笔与未回收钩子、场景与年代），
               不得出现任何矛盾；如需推进，只能在此前事实之上推进；
            3. 按「剧本情节结构」判断当前处于 开端/发展/转折/高潮/结局 的哪一段，并让本集承担该阶段的功能；
            4. 本集必须有明确冲突（人与人 / 人与环境 / 人物内心），有推进、有转折；
            5. 语言遵守「剧本语言」的风格；对话与舞台说明分开书写，舞台说明放在【】内。
            只输出 JSON：{"title":"<本集标题>","summary":"<本集摘要，<=120字>","content":"<本段正文>"}
            （每段的 title/summary 可与第 1 段相同，但**必须都给出**，以保持输出结构一致）
            【格式】JSON 字符串内**不得出现真实换行**（需要分段请写 \\n 转义），不要输出注释或多余文字。
            """.formatted(MAX_SEGMENT_CHARS);

    private static final String OUTLINE_SYSTEM_FALLBACK = """
            你是本剧的编剧／剧本医生。用户会给出剧本上下文与一个**写作目标**（某个要素，或本集正文）。
            请只输出一份**分段写作提纲**：把目标恰好拆成 N 段（N 由用户指定），每段给出 2–%d 个子要点。
            硬规则：
            1. **恰好 N 段**，编号从 1 开始，顺序即写作顺序；每段对应约相同字数；
            2. 每段要能独立成文，段与段之间不重叠、不重复；合起来刚好覆盖目标，不缺不溢；
            3. 与「精简的故事」、剧本要素、人物关系保持一致，不得引入矛盾或新设定；
            4. 每段标题 ≤14 字；每条子要点 ≤40 字，写「写什么」而不是「写得好」。
            只输出 JSON：{"segments":[{"index":1,"title":"...","points":["...","..."]}]}
            【格式】JSON 字符串内不得出现真实换行，不要输出注释或多余文字。
            """.formatted(OUTLINE_POINTS_MAX);

    private static final String CONDENSE_SYSTEM_FALLBACK = """
            你在维护一部剧的「精简的故事」——它是唯一的连续记忆，供后续每一集生成使用。
            输入：现有精简故事；剧本要素（部分为摘要）；全部已发布集（编号/标题/摘要/正文节选）。
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

    // ---------------------------------------------------------------- 预算计划

    /**
     * 生成长文的预算计划：总目标字数 → 需要几段 + 每段写多少字。
     *
     * <p>不变式：每段 ≤ {@link #MAX_SEGMENT_CHARS}（否则单次输出会被 `max_tokens` 截断）。
     */
    public record PassPlan(int target, int passes, int perPass) {

        /** 同一目标与段数，只换「本段预算」——供「按剩余目标平摊」的单段提示词使用（段数口径不变）。 */
        public PassPlan withPerPass(int per) {
            return new PassPlan(target, passes, per);
        }
    }

    /** 按目标字数算出分段计划（目标会被夹到 {@code [FIELD_TARGET_MIN, FIELD_TARGET_MAX]} 内）。 */
    public static PassPlan planFor(int targetChars) {
        int target = clampTarget(targetChars);
        int byChunk = (int) Math.ceil(target / (double) FIELD_PASS_CHARS);
        int passes = Math.max(1, Math.min(MAX_PASSES, byChunk));
        int perPass = (int) Math.ceil(target / (double) passes);
        perPass = Math.max(PASS_MIN_CHARS, Math.min(MAX_SEGMENT_CHARS, perPass));
        return new PassPlan(target, passes, perPass);
    }

    /** 目标字数夹到用户允许的范围（用户 2026-09-17：最大值不超过 8000）。 */
    public static int clampTarget(int targetChars) {
        if (targetChars <= 0) return FIELD_TARGET_DEFAULT;
        return Math.max(FIELD_TARGET_MIN, Math.min(FIELD_TARGET_MAX, targetChars));
    }

    /**
     * 本段预算：把**剩余目标**平摊给**剩余段数**（下限 {@link #PASS_MIN_CHARS}、上限 {@link #MAX_SEGMENT_CHARS}）。
     *
     * <p>2026-09-17 实测事故（用户报「字数很少也很慢，还弹 AI 生成内容超长」）：分集生成的循环上限是
     * {@code plan.passes() + 1}（留一段补齐），但每段提示词里的数字仍是 {@code planFor(target).perPass()}
     * ——也就是**整集的目标字数**。目标 500 字时：计划 1 段 / 每段却被要求「约 500 字、严格不超过 800 字」，
     * 循环又写了 2 段 → 实测 1335 字 → 触发一次压缩调用（多几十秒）+ 弹「写超了…已自动压缩」。
     * 现在改成「剩余目标 / 剩余段数」，保证多段加起来 ≈ 目标。
     */
    public static int segmentBudget(int remaining, int passesLeft) {
        int p = Math.max(1, passesLeft);
        int per = (int) Math.ceil(Math.max(0, remaining) / (double) p);
        return Math.max(PASS_MIN_CHARS, Math.min(MAX_SEGMENT_CHARS, per));
    }

    /**
     * 「写超」判定阈值：只有**同时**超过目标 1.5 倍、又超过「按分段封顶本可以写到的上限」时才做压缩重写。
     *
     * <p>为什么不能只用 {@code target × 1.5}：单段封顶 {@link #segmentCeiling(int)} 是目标 × 1.3（下限 800），
     * 目标 500 时封顶 800 &gt; 750 —— 老实按提示词上限写完的稿子会被判「写超」再压一次（白花一次调用，
     * 还要弹一条看着像报错的提示）。
     */
    public static int allowedChars(PassPlan plan) {
        long byCeiling = (long) segmentCeiling(plan.perPass()) * Math.max(1, plan.passes());
        long byRatio = (long) plan.target() * 3 / 2;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(byCeiling, byRatio));
    }

    // ---------------------------------------------------------------- 提纲

    /**
     * 【B】复用判定：已存提纲**段数与本次计划一致**且用户未要求刷新 → 复用同一份提纲。
     *
     * <p>为什么要判段数：段数由目标字数算出（500→1 段…8000→4 段），
     * 用户把目标从 4000 改成 8000 时旧提纲（2 段）与新的 4 段对不上，必须重生成。
     */
    public static boolean canReuseOutline(Outline stored, PassPlan plan, boolean refresh) {
        return !refresh && stored != null && !stored.isEmpty()
                && stored.segments().size() == plan.passes();
    }

    /**
     * 本段提示词里的「严格不超过」数字。
     *
     * <p>为什么不直接用 {@link #MAX_SEGMENT_CHARS}：实测模型会无视高于目标很多的封顶
     * （目标 1500 字却写了 3361）；把封顶与目标挂钩（≈目标×1.3、上下限夹到 [800, 2600]）后遵循度明显提升。
     */
    public static int segmentCeiling(int perPass) {
        return Math.min(MAX_SEGMENT_CHARS, Math.max(800, (int) Math.ceil(perPass * 1.3)));
    }

    /** 提纲：每段一行文本（「第K段：标题 —— 要点1；要点2」），便于前端直接展示。 */
    public record Outline(List<String> segments) {

        public static Outline empty() {
            return new Outline(List.of());
        }

        public boolean isEmpty() {
            return segments == null || segments.isEmpty();
        }

        /** 第 pass 段（1 起）；越界返回空串。 */
        public String at(int pass) {
            if (segments == null || pass < 1 || pass > segments.size()) return "";
            return segments.get(pass - 1);
        }
    }

    /** 提纲请求的 user：字段版。 */
    public static String outlineUserForField(Script s, List<ScriptEpisode> episodes, ScriptField field,
                                             String hint, int targetChars, boolean fromContent,
                                             String currentValue) {
        PassPlan plan = planFor(targetChars);
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, field.key(), false).text());
        sb.append("\n【写作目标】字段「").append(field.label()).append("」（key=").append(field.key()).append("）\n");
        sb.append("【字段说明】").append(field.help()).append('\n');
        sb.append("【总目标】约 ").append(plan.target()).append(" 字\n");
        sb.append("【请拆成】").append(plan.passes()).append(" 段（每段约 ").append(plan.perPass()).append(" 字）\n");
        if (fromContent && currentValue != null && !currentValue.isBlank()) {
            sb.append("【本字段当前内容（供参考，可保留可取之处）】\n")
                    .append(clip(currentValue, ELEMENT_DIGEST_CHARS)).append('\n');
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("【用户补充要求】").append(hint.trim()).append('\n');
        }
        sb.append("\n请输出 JSON 提纲。");
        return sb.toString();
    }

    /** 提纲请求的 user：下一集版（目标字数由用户设定，与要素同一套分段机制）。 */
    public static String outlineUserForEpisode(Script s, List<ScriptEpisode> episodes, int nextNo,
                                               String titleHint, String instruction, int targetChars) {
        PassPlan plan = planFor(targetChars);
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, null, false).text());
        sb.append("\n【写作目标】第 ").append(nextNo).append(" 集正文\n");
        sb.append("【总目标】约 ").append(plan.target()).append(" 字\n");
        sb.append("【请拆成】").append(plan.passes()).append(" 段（每段约 ").append(plan.perPass())
                .append(" 字）：第 1 段＝起（引入场景人物、抛出本集冲突）、末段＝合（收束并留钩子）\n");
        if (titleHint != null && !titleHint.isBlank()) {
            sb.append("【标题提示】").append(titleHint.trim()).append('\n');
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("【用户对下一集的额外要求】").append(instruction.trim()).append('\n');
        }
        sb.append("\n请输出 JSON 提纲。");
        return sb.toString();
    }

    // ---------------------------------------------------------------- user（分段）

    /** 字段生成 / 更新（**一次一段**，按提纲写）。 */
    public static String fieldUser(Script s, List<ScriptEpisode> episodes, ScriptField field,
                                   String hint, String currentValue, boolean fromContent,
                                   int pass, String soFar, int targetChars, Outline outline) {
        return fieldUser(s, episodes, field, hint, currentValue, fromContent, pass, soFar,
                planFor(targetChars), outline);
    }

    /**
     * 同 {@link #fieldUser}，但**本段预算由调用方给定**：{@code plan.perPass()} = 本段字数、
     * {@code plan.passes()} = 计划段数（多段时用「剩余目标 / 剩余段数」平摊，见 {@link #segmentBudget(int, int)}）。
     */
    public static String fieldUser(Script s, List<ScriptEpisode> episodes, ScriptField field,
                                   String hint, String currentValue, boolean fromContent,
                                   int pass, String soFar, PassPlan plan, Outline outline) {
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, field.key(), fromContent).text());
        sb.append("\n【字段】").append(field.label()).append("（key=").append(field.key()).append("）\n");
        sb.append("【字段说明】").append(field.help()).append('\n');
        if (fromContent) {
            sb.append("\n【约定】本次是「AI 更新」：读全文后重写该字段，使其与已写章节完全一致；")
                    .append("保留原稿可取内容，不要凭空改设定。\n");
            if (currentValue != null && !currentValue.isBlank()) {
                sb.append("【本字段当前内容（在此基础上更新）】\n")
                        .append(clip(currentValue, FIELD_TARGET_MAX)).append('\n');
            }
        } else {
            sb.append("\n【约定】本次是「AI 生成」：仅凭标题、类型与上文生成该字段。\n");
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("【用户补充要求】").append(hint.trim()).append('\n');
        }
        sb.append(segmentsBlock(outline));
        sb.append('\n').append(passInstruction(pass, soFar, "该字段", plan, outline));
        return sb.toString();
    }

    /** 下一集（**一段一段**，按提纲写）。 */
    public static String episodeUser(Script s, List<ScriptEpisode> episodes, int nextNo,
                                     String titleHint, String instruction, int pass, String soFar,
                                     Outline outline, int targetChars) {
        return episodeUser(s, episodes, nextNo, titleHint, instruction, pass, soFar, outline,
                planFor(targetChars));
    }

    /**
     * 同 {@link #episodeUser}，但**本段预算由调用方给定**：{@code plan.perPass()} = 本段字数、
     * {@code plan.passes()} = 计划段数（与「【本集总量】…拆成 N 段」保持同一口径）。
     */
    public static String episodeUser(Script s, List<ScriptEpisode> episodes, int nextNo,
                                     String titleHint, String instruction, int pass, String soFar,
                                     Outline outline, PassPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, null, true).text());
        sb.append("\n【本集编号】第 ").append(nextNo).append(" 集\n");
        if (titleHint != null && !titleHint.isBlank()) {
            sb.append("【标题提示】").append(titleHint.trim()).append('\n');
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("【用户对下一集的额外要求】").append(instruction.trim()).append('\n');
        }
        // 本节拍的角色（与提纲并行：提纲说「写什么」，角色说「本段的功能」）
        if (plan.passes() == 1) {
            // 单段成集：不能再只让它「起」——否则它会按一整集写而大幅超字（实测目标 1500 字写了 3361）
            sb.append("【本段功能】本集**只写这一段**：起→承转→合一次写完，**必须在字数上限内收束**并留钩子。\n");
        } else if (pass <= 1) {
            sb.append("【本段功能】起：引入本集场景与人物、抛出本集的核心冲突。\n");
        } else if (pass == 2) {
            sb.append("【本段功能】承／转：承接前文推进冲突、制造转折。\n");
        } else {
            sb.append("【本段功能】合：解决或升级冲突，并在结尾留下钩子。\n");
        }
        sb.append("【本集总量】本集目标总共约 ").append(plan.target()).append(" 字，会被拆成 ")
                .append(plan.passes()).append(" 段依次生成；")
                .append(plan.passes() == 1
                        ? "**本集只有这一段，请在本段内完成整集**（起承转合 + 结尾钩子）。\n"
                        : "**你只写当前这一段，不要在本段把整集收尾**（除非本段任务写的是收束段）。\n");
        sb.append(segmentsBlock(outline));
        sb.append('\n').append(passInstruction(pass, soFar, "本集正文", plan, outline));
        return sb.toString();
    }

    /** 分段写作的公共指令（提纲定位 + 长度目标 + 不重复 + 不提前收尾）。 */
    private static String passInstruction(int pass, String soFar, String task, PassPlan plan, Outline outline) {
        StringBuilder sb = new StringBuilder();
        String item = outline == null ? "" : outline.at(pass);
        sb.append("【本段任务】写作").append(task).append("的**第 ").append(pass).append(" 段**");
        if (!item.isBlank()) {
            sb.append("，严格按提纲这一条写：\n  ").append(item).append('\n');
        } else {
            sb.append("（提纲缺失，请自行合理推进）。\n");
        }
        sb.append("长度：约 ").append(plan.perPass()).append(" 字，**严格不超过 ")
                .append(segmentCeiling(plan.perPass()))
                .append(" 字**（超出会被接口截断；写不下就精简场景/要点，不要超字）。\n");
        if (pass > 1 && soFar != null && !soFar.isBlank()) {
            sb.append("【已有前文（**勿重复**，只用于衔接）】\n")
                    .append(clip(tail(soFar, 1400), 1400)).append('\n')
                    .append("接着上文继续写；**不得重复前文已讲内容**，也不要写全文总结。\n");
        } else {
            sb.append("写完本段即停，**不要写总结/结语**（后面还有段落）。\n");
        }
        sb.append("只输出 JSON：{\"value\":\"<本段内容>\",\"note\":\"<本段要点，可选>\"}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 润色（用户自己写的正文）

    /** 润色系统词（在**用户自己的正文**上改文笔，不重编剧情）。 */
    public static String polishSystem() {
        return load("script_polish_system.md", POLISH_SYSTEM_FALLBACK);
    }

    private static final String POLISH_SYSTEM_FALLBACK = """
            你是这部剧的编剧／润色编辑。用户会给你**他自己写的一段正文**，以及剧本上下文（精简的故事、要素、已写各集）。
            任务：**只改文笔与节奏，不重编剧情**。
            硬规则：
            1. **不得新增／删除人物、事件、设定与结局**：作者已写的事实（谁做了什么、结果如何）一律保留；
               缺细节可以补动作/神态/舞台说明，但不能改变情节走向；
            2. 可以做的事：精修台词、补【舞台说明】、理顺衔接、去重复与口语病、强化冲突与情绪；
            3. **禁止**写成剧情提要或大纲，必须仍是可直接拍摄的剧本正文（对话 + 【舞台说明】）；
            4. 篇幅以用户消息给出的字数为准；【硬上限】**严格不超过 %d 字** —— 超出会被接口截断，必须自己收住；
            5. 若给了「已润色的前文」，只用于衔接称呼与语气，**不得重复它的内容**。
            只输出 JSON：{"value":"<润色后的本段正文>","note":"<本次改了什么，一句话，可选>"}
            【格式】JSON 字符串内**不得出现真实换行**（需要分段请写 \\n 转义），不要输出注释或多余文字。
            """.formatted(MAX_SEGMENT_CHARS);

    /**
     * 润色请求的 user：把作者的正文**按块**送去润色（块内原文给全）。
     *
     * @param pass      当前块号（1 起）
     * @param total     总块数
     * @param chunk     本块原文
     * @param soFar     已润色好的前文（仅用于衔接，不给全文）
     */
    public static String polishUser(Script s, List<ScriptEpisode> episodes, int pass, int total,
                                    String chunk, String soFar, PassPlan plan, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, null, true).text());
        sb.append("\n【本次任务】润色**作者自己写的**第 ").append(pass).append('/').append(total)
                .append(" 段正文：只改文笔与节奏，**不得改动剧情事实**。\n");
        sb.append("【全篇目标】约 ").append(plan.target()).append(" 字；本段约 ")
                .append(plan.perPass()).append(" 字，**严格不超过 ")
                .append(segmentCeiling(plan.perPass())).append(" 字**。\n");
        if (instruction != null && !instruction.isBlank()) {
            sb.append("【用户对润色的额外要求】").append(instruction.trim()).append('\n');
        }
        if (soFar != null && !soFar.isBlank()) {
            sb.append("【已润色的前文（只用于衔接称呼与语气，**勿重复**）】\n")
                    .append(clip(tail(soFar, 800), 800)).append('\n');
        }
        sb.append("\n【待润色的原文（本段）】\n").append(chunk).append('\n');
        sb.append("只输出 JSON：{\"value\":\"<润色后的本段正文>\"}");
        return sb.toString();
    }

    /**
     * 把长文按**段落**切块（每块 ≤ {@code max} 字；单段超限就硬切）。
     *
     * <p>为什么：润色是「一段一段改」—— 一次要模型输出上万字会被 `max_tokens` 截断（实测 422）。
     */
    public static List<String> chunk(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int limit = Math.max(200, max);
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.length() > limit) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < p.length(); i += limit) {
                    out.add(p.substring(i, Math.min(p.length(), i + limit)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + p.length() + 1 > limit) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(p);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 把提纲整份给出（模型需要知道全貌才能不越界写后面的内容）。 */
    private static String segmentsBlock(Outline outline) {
        if (outline == null || outline.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【本目标的写作提纲（共 ")
                .append(outline.segments().size()).append(" 段；你只写任务指定的那一段）】\n");
        for (String seg : outline.segments()) {
            sb.append("  ").append(seg).append('\n');
        }
        return sb.toString();
    }

    /** 解析提纲 JSON → Outline（容错：segments/outline/数组都认）。 */
    public static Outline parseOutline(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> out = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode arr = node.path("segments");
        if (!arr.isArray()) arr = node.path("outline");
        if (!arr.isArray() && node.isArray()) arr = node;
        if (arr != null && arr.isArray()) {
            int i = 1;
            for (com.fasterxml.jackson.databind.JsonNode seg : arr) {
                if (seg.isTextual()) {
                    String t = seg.asText("").trim();
                    if (!t.isEmpty()) out.add(i++ + ". " + t);
                    continue;
                }
                String title = seg.path("title").asText("").trim();
                List<String> points = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode p : seg.path("points")) {
                    String v = p.asText("").trim();
                    if (!v.isEmpty()) points.add(v);
                }
                if (title.isEmpty() && points.isEmpty()) continue;
                int idx = seg.path("index").asInt(i);
                StringBuilder line = new StringBuilder();
                line.append(idx).append(". ");
                if (!title.isEmpty()) line.append(title);
                if (!points.isEmpty()) {
                    line.append(title.isEmpty() ? "" : " —— ");
                    line.append(String.join("；", points));
                }
                out.add(line.toString());
                i = Math.max(i, idx) + 1;
            }
        }
        return new Outline(List.copyOf(out));
    }

    // ---------------------------------------------------------------- 其它用途

    /** 精简故事刷新 + 一致性检查（Q4：只提议，用户确认后才改）。 */
    public static String condenseUser(Script s, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【现有精简的故事】\n")
                .append(s.condensedStory() == null || s.condensedStory().isBlank()
                        ? "（尚无，请从下面各集内容建立）" : clip(s.condensedStory(), CONDENSED_MAX_CHARS))
                .append("\n\n");
        sb.append(context(s, episodes, null, true).text());
        sb.append("\n请输出 JSON（精简故事 ≤ ").append(CONDENSED_MAX_CHARS).append(" 字）。");
        return sb.toString();
    }

    /** AI 引导（输入很小：只读精简故事 + 结构 + 集标题摘要）。 */
    public static String guideUser(Script s, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【精简的故事（全剧记忆）】\n")
                .append(nz(clip(s.condensedStory(), CONDENSED_MAX_CHARS))).append("\n\n");
        sb.append("【剧本标题】").append(s.title()).append("\n【剧本类型】").append(s.genre()).append('\n');
        sb.append("【剧本情节结构】\n").append(clip(nz(s.plotStructure()), ELEMENT_DIGEST_CHARS)).append('\n');
        sb.append("【剧本冲突】\n").append(clip(nz(s.conflict()), ELEMENT_DIGEST_CHARS)).append('\n');
        sb.append("\n【已完成集数】").append(episodes == null ? 0 : episodes.size()).append(" 集\n");
        sb.append(episodesBlock(episodes, false));
        sb.append("\n请判断当前阶段并给出下一步建议。请输出 JSON。");
        return sb.toString();
    }

    /** 应用一致性改动：一次重写多集（Q4：用户确认后才调用）。 */
    public static String rewriteEpisodesUser(Script s, List<ScriptConflict> items, List<ScriptEpisode> episodes) {
        StringBuilder sb = new StringBuilder();
        sb.append(context(s, episodes, null, false).text());
        sb.append("\n【需要修改的既有集与修改要点】\n");
        for (ScriptConflict c : items) {
            sb.append("- 第 ").append(c.episodeNo()).append(" 集《").append(nz(c.title())).append("》：")
                    .append("问题=").append(nz(c.issue())).append("；修改要点=")
                    .append(nz(c.fix())).append('\n');
        }
        int bodyBudget = Math.max(ELEMENT_DIGEST_CHARS, CONTEXT_BUDGET_CHARS
                - (s.condensedStory() == null ? 0 : CONDENSED_MAX_CHARS) - 2000);
        int each = Math.max(1500, bodyBudget / Math.max(1, items.size()));
        sb.append("\n【这些集的当前正文（按下标对应，请按修改要点重写）】\n");
        for (ScriptConflict c : items) {
            ScriptEpisode e = findByNo(episodes, c.episodeNo());
            sb.append("\n第 ").append(c.episodeNo()).append(" 集《")
                    .append(e == null ? nz(c.title()) : e.title()).append("》正文：\n")
                    .append(e == null ? "（缺失）" : clip(e.content(), each)).append('\n');
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
                【精简的故事（全剧记忆）】
                %s

                【本集】第 %d 集《%s》
                %s
                """.formatted(BRIEF_MAX_CHARS, s.title(), s.genre(),
                clip(s.condensedStory(), CONDENSED_MAX_CHARS), e.episodeNo(), e.title(),
                clip(e.content(), FIELD_TARGET_MAX));
    }

    // ---------------------------------------------------------------- 上下文组装（【A】预算）

    /** 组装结果：正文 + 被压缩/省略的记录（供上层打日志，不静默）。 */
    public record Block(String text, List<String> notes) {
        public boolean trimmed() {
            return !notes.isEmpty();
        }
    }

    /**
     * 【A】受预算约束的上下文：**目标字段全文 + 其它要素摘要 + 每集标题摘要 + 仅最近 N 集正文**。
     *
     * @param targetKey  需要全文的要素 key（字段生成时传；集生成/一致性检查传 null）
     * @param withBodies 是否带最近几集的正文节选
     */
    public static Block context(Script s, List<ScriptEpisode> episodes, String targetKey, boolean withBodies) {
        StringBuilder sb = new StringBuilder();
        List<String> notes = new ArrayList<>();
        int budget = CONTEXT_BUDGET_CHARS;

        // 头部：标题 + 类型（必留，很小）
        sb.append("【剧本标题】").append(s.title()).append('\n');
        sb.append("【剧本类型】").append(s.genre()).append('\n');
        budget -= sb.length();

        // 【C】精简的故事：全剧唯一连续记忆，最高优先级（所有调用都带上）
        String cond = clip(nz(s.condensedStory()), CONDENSED_MAX_CHARS);
        sb.append("【精简的故事（全剧记忆，优先遵守）】\n")
                .append(cond.isBlank() ? "（尚无——本剧还没有已完成的集）" : cond).append('\n');
        budget -= cond.length() + 40;

        // 目标字段全文（若指定）
        if (targetKey != null) {
            String raw = nz(fieldValue(s, targetKey));
            if (!raw.isBlank()) {
                String t = clip(raw, FIELD_TARGET_MAX);
                sb.append("【").append(labelOf(targetKey)).append("（本次目标字段，全文）】\n")
                        .append(t).append('\n');
                budget -= t.length() + 32;
            }
        }

        // 集：标题 + 摘要全量，正文只给最近 N 集（省预算，又保住上下文连续性）
        Block eps = episodesBlockBounded(episodes, withBodies, Math.min(EPISODES_RESERVE_CHARS, Math.max(800, budget / 3)));
        sb.append('\n').append(eps.text());
        notes.addAll(eps.notes());
        budget -= eps.text().length();

        // 其它要素：摘要（头 60% + 尾 40%），预算不够就继续压缩、再不够就省略
        List<ScriptField> rest = new ArrayList<>();
        for (ScriptField f : ScriptField.values()) {
            if (f.key().equals(targetKey)) continue;
            if (!nz(fieldValue(s, f.key())).isBlank()) rest.add(f);
        }
        int per = rest.isEmpty() ? 0 : Math.max(DIGEST_MIN_CHARS,
                Math.min(ELEMENT_DIGEST_CHARS, Math.max(0, budget) / rest.size()));
        for (ScriptField f : rest) {
            String raw = nz(fieldValue(s, f.key()));
            if (per < DIGEST_MIN_CHARS || budget < DIGEST_MIN_CHARS) {
                notes.add("省略「" + f.label() + "」（上下文预算不足）");
                continue;
            }
            String t = digest(raw, per);
            if (t.length() < raw.length()) {
                notes.add("压缩「" + f.label() + "」→ " + t.length() + " 字");
            }
            sb.append("\n【").append(f.label()).append("】\n").append(t).append('\n');
            budget -= t.length() + 16;
        }
        sb.append("\n（上下文总预算 ").append(CONTEXT_BUDGET_CHARS).append(" 字；超出的长要素已按「头+尾」压缩）\n");
        return new Block(sb.toString(), List.copyOf(notes));
    }

    /** 集列表：标题 + 摘要全量；正文只保留最近 {@link #RECENT_EPISODES_WITH_BODY} 集，且受 room 限制。 */
    private static Block episodesBlockBounded(List<ScriptEpisode> episodes, boolean withBodies, int room) {
        List<String> notes = new ArrayList<>();
        if (episodes == null || episodes.isEmpty()) {
            return new Block("【已写集数】无（这是第一集）\n", notes);
        }
        StringBuilder sb = new StringBuilder("【已写集数】共 ").append(episodes.size()).append(" 集\n");
        int budget = Math.max(300, room);
        int bodyFrom = withBodies ? Math.max(0, episodes.size() - RECENT_EPISODES_WITH_BODY) : episodes.size();
        int skippedBodies = 0;
        for (int i = 0; i < episodes.size(); i++) {
            ScriptEpisode e = episodes.get(i);
            String summary = nz(e.summary().isBlank() ? clip(e.content(), 120) : e.summary());
            String head = "第 " + e.episodeNo() + " 集《" + nz(e.title()) + "》摘要：" + clip(summary, 200) + '\n';
            if (budget - head.length() < 0) {
                notes.add("省略部分集摘要（上下文预算不足，保留了最近的）");
                break;
            }
            sb.append(head);
            budget -= head.length();
            if (i >= bodyFrom) {
                String body = clip(nz(e.content()), Math.min(EPISODE_BODY_CHARS, Math.max(0, budget)));
                if (body.length() < e.content().length()) {
                    notes.add("第 " + e.episodeNo() + " 集正文压缩 → " + body.length() + " 字");
                }
                if (!body.isBlank()) {
                    sb.append("  正文节选：").append(body).append('\n');
                    budget -= body.length();
                }
            } else {
                skippedBodies++;
            }
        }
        if (skippedBodies > 0) {
            notes.add("前 " + skippedBodies + " 集只给摘要（正文仅保留最近 "
                    + RECENT_EPISODES_WITH_BODY + " 集，控上下文）");
        }
        return new Block(sb.toString(), notes);
    }

    /** 兼容旧调用：全要素摘要（无目标字段）。 */
    public static String elementsBlock(Script s) {
        return context(s, List.of(), null, false).text();
    }

    /** 兼容旧调用：集列表（可带正文，未额外限额）。 */
    public static String episodesBlock(List<ScriptEpisode> episodes, boolean withContent) {
        return episodesBlockBounded(episodes, withContent, EPISODES_RESERVE_CHARS).text();
    }

    /** 长文本摘要：头 60% + 尾 40%（保留「开端…结局」这类首尾信息）。 */
    public static String digest(String raw, int keep) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() <= keep) return t;
        int headLen = Math.max(1, (int) (keep * 0.6));
        int tailLen = Math.max(1, keep - headLen);
        return t.substring(0, headLen) + "\n……（中略）……\n" + t.substring(t.length() - tailLen);
    }

    private static String fieldValue(Script s, String key) {
        ScriptField f = ScriptField.of(key);
        return f == null ? "" : switch (f) {
            case CHARACTERS -> nz(s.characters());
            case STORY -> nz(s.story());
            case CONFLICT -> nz(s.conflict());
            case PLOT_STRUCTURE -> nz(s.plotStructure());
            case LANGUAGE -> nz(s.language());
            case STAGE_DIRECTIONS -> nz(s.stageDirections());
        };
    }

    private static String labelOf(String key) {
        ScriptField f = ScriptField.of(key);
        return f == null ? key : f.label();
    }

    private static ScriptEpisode findByNo(List<ScriptEpisode> episodes, Integer no) {
        if (no == null || episodes == null) return null;
        for (ScriptEpisode e : episodes) {
            if (e.episodeNo() == no) return e;
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 取字符串末尾（续写时给模型看的上文尾巴）。 */
    private static String tail(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(t.length() - max);
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
