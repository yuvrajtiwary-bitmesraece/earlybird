package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row of {@code postings}: one job seen on a board.
 *
 * @param baseline true if the job was already on the board at our first check, so not "new" (V2)
 */
public record Posting(
        UUID id,
        long boardId,
        Source source,
        String externalId,
        String url,
        String title,
        String company,
        String locationRaw,
        List<String> departments,
        Instant sourcePublishedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int missingCount,
        Instant closedAt,
        String contentHash,
        String descriptionText,
        boolean repost,
        Instant eventPublishedAt,
        boolean baseline) {

    public Posting {
        departments = departments == null ? List.of() : List.copyOf(departments);
    }
}
