package io.github.yuvrajtiwary.earlybird.ops;

import java.time.Duration;
import java.util.Optional;

/**
 * A named lock with a lease. If the holder crashes without releasing, the lease expires after {@code ttl},
 * so a dead process can't block the tick forever. Used so overlapping ticks, digests and maintenance runs
 * are skipped instead of running twice. Redis ({@code SET NX PX}) and Postgres advisory-lock versions
 * come in Block 6.
 */
public interface DistributedLock {

    /**
     * Tries to take the lock without waiting.
     *
     * @return a handle to release it, or empty if someone else holds it
     */
    Optional<Handle> tryAcquire(String name, Duration ttl);

    /** Releases the lock, but only if this caller still owns it (its lease may have expired and been taken). */
    @FunctionalInterface
    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
