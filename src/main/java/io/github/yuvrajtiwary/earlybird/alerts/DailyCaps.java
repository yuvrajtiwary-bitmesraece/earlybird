package io.github.yuvrajtiwary.earlybird.alerts;

import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Each user's instant alerts per day ({@code daily_cap}, default 10; the rest wait for the digest). The day is
 * the user's day in India. Redis {@code INCR cap:{userId}:{yyyymmdd}} + {@code EXPIRE} is the fast path (spec
 * 3.5); without Redis, today's sent instant alerts are counted in Postgres, which is exact because one user's
 * alerts are delivered one at a time.
 */
public class DailyCaps {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis; // null without Redis
    private final RedisGuard guard;          // null without Redis
    private final AlertRepository alerts;
    private final Clock clock;

    public DailyCaps(StringRedisTemplate redis, RedisGuard guard, AlertRepository alerts, Clock clock) {
        this.redis = redis;
        this.guard = guard;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Counts one instant alert for today; false if that would go over {@code cap}. */
    public boolean tryReserve(long userId, int cap) {
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        if (redis != null) {
            String key = guard.key("cap:" + userId + ":" + DAY.format(today));
            Long count = guard.call(2, () -> {
                Long n = redis.opsForValue().increment(key);
                redis.expire(key, Duration.ofDays(2));
                return n;
            }).orElse(null);
            if (count != null) {
                return count <= cap;
            }
        }
        var startOfDay = today.atStartOfDay(INDIA).toInstant();
        return alerts.countInstantSentSince(userId, startOfDay) < cap;
    }
}
