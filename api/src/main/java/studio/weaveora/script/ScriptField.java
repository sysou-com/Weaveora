package studio.weaveora.script;

import java.util.Arrays;

/**
 * 剧本 6 个「可 AI 生成/更新」的要素字段（标题与类型除外，Q2）。
 *
 * <p>单一真源：key（前后端 + JSON）、中文名、字段说明（前端 help 文案 + AI 提示词共用），
 * 避免「界面写一套、提示词写另一套」。
 */
public enum ScriptField {

    CHARACTERS("characters", "剧本人物及人物介绍",
            "人物是剧本的灵魂，是推动剧情发展的主体。人物设计需要考虑性格特征、背景经历、动机和目标，"
                    + "以及与其他角色的关系。立体的人物形象能够引发观众共鸣，并通过人物的行动和选择推动故事发展。"),
    STORY("story", "剧本故事",
            "故事是剧本的核心内容，是剧本创作的基础。它包括事件的起因、发展、高潮和结局，同时需要具备完整性和逻辑性。"
                    + "故事的长度、节奏和题材会影响剧本的结构和表现形式。"),
    CONFLICT("conflict", "剧本冲突",
            "冲突是推动剧情发展的动力源泉，也是展现人物性格和主题的重要手段。冲突可以表现为人与人之间的矛盾、"
                    + "人与环境的对抗或人物内心的挣扎。有效的冲突设计能够制造悬念和戏剧张力，使故事更具吸引力。"),
    PLOT_STRUCTURE("plotStructure", "剧本情节结构",
            "剧本的结构通常包括开端、发展、转折、高潮和结局。开端引入故事和人物，发展部分展开情节，"
                    + "转折和高潮制造戏剧张力，结局解决冲突并给观众满意的回应。结构可以纵向发展（条式结构）"
                    + "或横向发展（团块结构），根据故事类型和创作风格灵活安排。"),
    LANGUAGE("language", "剧本语言",
            "语言是剧本的表达工具，包括对话、旁白和内心独白。剧本语言应简洁明了、富有个性和节奏感，"
                    + "能够准确传达人物的思想、情感和性格特点。戏曲或歌剧中，语言还可能以唱词形式呈现。"),
    STAGE_DIRECTIONS("stageDirections", "舞台说明",
            "舞台说明是剧本中对场景、时间、地点、环境、道具、人物动作与表情、灯光音响等的说明性文字，"
                    + "由剧作者提供，是导演、演员与舞台各工种再创作的依据。");

    private final String key;
    private final String label;
    private final String help;

    ScriptField(String key, String label, String help) {
        this.key = key;
        this.label = label;
        this.help = help;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String help() { return help; }

    public static ScriptField of(String key) {
        if (key == null) return null;
        return Arrays.stream(values())
                .filter(f -> f.key.equalsIgnoreCase(key.trim()))
                .findFirst().orElse(null);
    }
}
