package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A row of {@code postings}: one job seen on a board. */
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
        Instant eventPublishedAt) {

    public Posting {
        departments = departments == null ? List.of() : List.copyOf(departments);
    }
}
