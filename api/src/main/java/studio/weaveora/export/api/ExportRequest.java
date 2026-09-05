package studio.weaveora.export.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** POST /projects/{id}/exports */
public record ExportRequest(@NotNull UUID revisionId) {
}
