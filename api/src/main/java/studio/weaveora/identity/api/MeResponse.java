package studio.weaveora.identity.api;

import java.util.List;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String displayName,
        List<WorkspaceInfo> workspaces
) {
    public record WorkspaceInfo(UUID id, String role) {
    }
}
