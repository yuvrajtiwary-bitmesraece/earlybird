package io.github.yuvrajtiwary.earlybird.common.redis;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A token bucket shared by every process, in one Lua script (spec 3.5: key {@code rl:tg:global}, one EVAL per
 * send). Redis runs a script atomically, so concurrent senders can't both take the last token. Falls back to
 * the local bucket when Redis is unavailable (correct while there's one instance).
 */
public class RedisRateLimiter implements RateLimiter {

    /**
     * KEYS[1] = bucket; ARGV = capacity, refill per ms, now (ms). Returns 0 if a token was taken, otherwise
     * the milliseconds until one will be available. The hash expires once the bucket would be full again.
     */
    static final String SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1]) or capacity
            local ts = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill)
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
            else
              wait = math.ceil((1 - tokens) / refill)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill) + 1000)
            return wait
            """;

    private static final RedisScript<Long> BUCKET = RedisScript.of(SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final RateLimiter fallback;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, RedisGuard guard, RateLimiter fallback, Clock clock) {
        this.redis = redis;
        this.guard = guard;
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public Duration tryAcquire(String key, Bandwidth bandwidth) {
        double refillPerMs = bandwidth.refillPerSecond() / 1000.0;
        return guard.call(1, () -> redis.execute(BUCKET, List.of(guard.key("rl:" + key)),
                        String.valueOf(bandwidth.capacity()), String.valueOf(refillPerMs),
                        String.valueOf(clock.millis())))
                .map(Duration::ofMillis)
                .orElseGet(() -> fallback.tryAcquire(key, bandwidth));
    }
}
