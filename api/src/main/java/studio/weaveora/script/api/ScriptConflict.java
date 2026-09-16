package studio.weaveora.script.api;

/** AI 一致性检查给出的一条「历史章节需改动」建议（Q4：由用户确认后才应用）。 */
public record ScriptConflict(
        Integer episodeNo,
        String title,
        String issue,
        String fix
) {
}
