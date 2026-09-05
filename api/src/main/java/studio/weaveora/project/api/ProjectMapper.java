package studio.weaveora.project.api;

import studio.weaveora.project.domain.Project;

import java.util.UUID;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectResponse toResponse(Project p) {
        return new ProjectResponse(
                p.id(), p.workspaceId(), p.title(), p.mode(), p.aspectRatio(),
                p.durationSec(), p.status(), p.createdAt());
    }
}
