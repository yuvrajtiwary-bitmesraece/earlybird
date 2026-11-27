package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The spec's lock (3.3): Redis {@code SET key token NX PX ttl}, released only by its owner (a script compares the
 * token before deleting, so a holder whose lease expired can't release someone else's lock). When Redis is
 * unreachable, the Postgres advisory lock takes over, so jobs never stop because of Redis.
 *
 * <p>Trade-off: if Redis fails <em>while</em> one instance holds the Redis lock, a second instance could take the
 * Postgres one. Production runs one instance, and a doubled tick only re-checks boards (inserts are idempotent).
 */
public final class RedisLock implements DistributedLock {

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final DistributedLock fallback;

    public RedisLock(StringRedisTemplate redis, RedisGuard guard, DistributedLock fallback) {
        this.redis = redis;
        this.guard = guard;
        this.fallback = fallback;
    }

    @Override
    public Optional<Handle> tryAcquire(String name, Duration ttl) {
        String key = guard.key("lock:" + name);
        String token = UUID.randomUUID().toString();
        Optional<Boolean> taken = guard.call(1, () -> redis.opsForValue().setIfAbsent(key, token, ttl));
        if (taken.isEmpty()) {
            return fallback.tryAcquire(name, ttl); // Redis is down: the database decides
        }
        if (!taken.get()) {
            return Optional.empty();
        }
        // If Redis is down at release time, the lease simply expires.
        return Optional.of(() -> guard.call(1, () -> redis.execute(RELEASE, List.of(key), token)));
    }
}
