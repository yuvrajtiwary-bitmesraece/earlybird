package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;

/** A row of {@code boards}: one company's careers board on one platform. */
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
        Instant createdAt) {}
