package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;

/**
 * A row of {@code boards}: one company's careers board on one platform.
 *
 * @param lastSuccessAt          null until the first successful check, which makes that check the baseline
 * @param consecutiveEmptyChecks suspicious "0 jobs" answers in a row (see the poller's empty guard)
 * @param etag                   from the last full response, sent back as If-None-Match
 */
public record Board(
        long id,
        Source source,
        String token,
        String company,
        boolean enabled,
        int checkIntervalS,
        Instant nextCheckAt,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        int consecutiveFailures,
        String lastError,
        Integer lastJobCount,
        Instant createdAt,
        int consecutiveEmptyChecks,
        String etag) {}
