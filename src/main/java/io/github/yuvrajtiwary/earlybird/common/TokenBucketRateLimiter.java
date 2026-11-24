package io.github.yuvrajtiwary.earlybird.common;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token buckets. Each bucket is updated inside {@link ConcurrentHashMap#compute}, which runs
 * atomically per key, so concurrent callers can never take more tokens than the bucket holds.
 *
 * <p>Buckets are refilled lazily: nothing runs in the background; the refill since the last call is
 * computed from the elapsed time. Safe as the only limiter while there is one process; with several
 * processes the global Telegram limit needs the shared Redis version.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private record Bucket(double tokens, long updatedAtMillis) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Duration tryAcquire(String key, Bandwidth bandwidth) {
        long now = clock.millis();
        Duration[] wait = new Duration[1];
        buckets.compute(key, (k, bucket) -> {
            double tokens;
            if (bucket == null) {
                tokens = bandwidth.capacity();
            } else {
                long elapsed = Math.max(0, now - bucket.updatedAtMillis()); // ignore clock steps backwards
                tokens = Math.min(bandwidth.capacity(),
                        bucket.tokens() + elapsed * bandwidth.refillPerSecond() / 1000.0);
            }
            if (tokens >= 1.0) {
                wait[0] = Duration.ZERO;
                return new Bucket(tokens - 1.0, now);
            }
            double exactMillis = (1.0 - tokens) * 1000.0 / bandwidth.refillPerSecond();
            // Subtract a nanosecond before rounding up: floating-point noise (1 - 0.999 = 0.0010000000000000009)
            // would otherwise turn an exact 1 ms into 2 ms.
            long millis = (long) Math.ceil(exactMillis - 1e-6);
            wait[0] = Duration.ofMillis(Math.max(1, millis));
            return new Bucket(tokens, now);
        });
        return wait[0];
    }
}
