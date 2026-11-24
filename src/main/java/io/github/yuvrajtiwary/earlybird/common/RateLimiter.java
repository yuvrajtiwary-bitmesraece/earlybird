package io.github.yuvrajtiwary.earlybird.common;

import java.time.Duration;

/**
 * Token-bucket rate limiting by key. Used for Telegram's global send limit (25 messages/s), per-chat
 * pacing (1 message/s) and per-user command limits. In-memory now; a Redis version (one Lua script per
 * call) shares the global bucket across processes later.
 */
public interface RateLimiter {

    /**
     * Takes one token from the bucket named {@code key}.
     *
     * @return {@link Duration#ZERO} if a token was taken; otherwise how long until one will be available
     */
    Duration tryAcquire(String key, Bandwidth bandwidth);

    /** Blocks until a token is taken. Fine on virtual threads, where sleeping doesn't hold a platform thread. */
    default void acquire(String key, Bandwidth bandwidth) throws InterruptedException {
        Duration wait;
        while (!(wait = tryAcquire(key, bandwidth)).isZero()) {
            Thread.sleep(wait);
        }
    }

    /**
     * Bucket shape: up to {@code capacity} tokens, refilled at {@code refillPerSecond}. Capacity is the
     * burst size; the refill rate is the sustained rate.
     */
    record Bandwidth(int capacity, double refillPerSecond) {

        public Bandwidth {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be at least 1");
            }
            if (!(refillPerSecond > 0)) {
                throw new IllegalArgumentException("refillPerSecond must be positive");
            }
        }

        /** {@code n} per second, with a burst of {@code n}. */
        public static Bandwidth perSecond(int n) {
            return new Bandwidth(n, n);
        }
    }
}
