package studio.weaveora.director.api;

import java.util.UUID;

/** revision 全片确认响应。 */
public record RevisionApproveResult(
        UUID revisionId,
        boolean approved,
        String projectStatus
) {
}
