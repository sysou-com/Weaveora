package studio.weaveora.script.api;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** POST /api/v1/scripts/{id}/episodes/{eid}/apply-sync —— 用户确认后，AI 重写并应用历史章节改动。 */
public record ApplySyncRequest(@NotEmpty List<ScriptConflict> items) {
}
