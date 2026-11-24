package io.github.yuvrajtiwary.earlybird.common.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps every Redis command (spec 3.5: Redis is optional, with fallbacks):
 * <ul>
 *   <li>prefixes keys with {@code EARLYBIRD_ENV}, so dev and prod can share one Upstash database;
 *   <li>counts commands ({@code earlybird_redis_commands_total}) against Upstash's 500K/month;
 *   <li>after a failure, skips Redis for {@value #COOL_DOWN_SECONDS} s (a tiny circuit breaker), so an
 *       outage costs one timeout, not one per message, and callers use their fallback meanwhile.
 * </ul>
 */
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);
    static final long COOL_DOWN_SECONDS = 30;

    private final String prefix;
    private final Clock clock;
    private final Counter commands;
    private volatile Instant skipUntil = Instant.EPOCH;

    public RedisGuard(String env, Clock clock, MeterRegistry meters) {
        this.prefix = env + ":";
        this.clock = clock;
        this.commands = meters.counter("earlybird.redis.commands");
    }

    public String key(String name) {
        return prefix + name;
    }

    /** Runs a command; empty if Redis is cooling down or the command failed (the caller falls back). */
    public <T> Optional<T> call(int commandCount, Supplier<T> command) {
        if (clock.instant().isBefore(skipUntil)) {
            return Optional.empty();
        }
        try {
            commands.increment(commandCount);
            return Optional.ofNullable(command.get());
        } catch (RuntimeException e) {
            skipUntil = clock.instant().plus(Duration.ofSeconds(COOL_DOWN_SECONDS));
            log.warn("Redis unavailable, using fallbacks for {} s: {}", COOL_DOWN_SECONDS, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public boolean coolingDown() {
        return clock.instant().isBefore(skipUntil);
    }
}
