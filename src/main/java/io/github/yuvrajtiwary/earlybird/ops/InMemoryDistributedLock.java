package io.github.yuvrajtiwary.earlybird.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lease-based lock for a single process. Correct here because Render runs exactly one instance; also the
 * fallback when Redis is unreachable.
 *
 * <p>Each lease is a fresh object, and release uses {@code remove(name, lease)}, which removes only that
 * exact lease. So a holder whose lease expired can't release the lock now held by someone else, the same
 * compare-and-delete rule the Redis version applies with a token.
 */
public final class InMemoryDistributedLock implements DistributedLock {

    private record Lease(Instant expiresAt) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    public InMemoryDistributedLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Handle> tryAcquire(String name, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = clock.instant();
        Lease mine = new Lease(now.plus(ttl));
        Lease winner = leases.compute(name,
                (key, current) -> current == null || !current.expiresAt().isAfter(now) ? mine : current);
        if (winner != mine) {
            return Optional.empty();
        }
        return Optional.of(() -> leases.remove(name, mine));
    }
}
