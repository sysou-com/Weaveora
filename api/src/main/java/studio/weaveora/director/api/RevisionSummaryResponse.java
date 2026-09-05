package studio.weaveora.director.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** revision 列表项。 */
public record RevisionSummaryResponse(
        UUID id,
        int revisionNo,
        String source,
        String title,
        String logline,
        String mode,
        boolean approved,
        OffsetDateTime createdAt
) {
}
