package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

/**
 * "Companies hiring freshers this week" (spec 3.5): a Redis sorted set per ISO week, company → count of new
 * fresher-friendly postings. Written when postings are tagged, read by the website (Block 7). Without Redis it
 * is simply not kept.
 */
public class HiringRanking {

    public record Entry(String company, long postings) {}

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final StringRedisTemplate redis; // null without Redis
    private final RedisGuard guard;
    private final Clock clock;

    public HiringRanking(StringRedisTemplate redis, RedisGuard guard, Clock clock) {
        this.redis = redis;
        this.guard = guard;
        this.clock = clock;
    }

    public void recordFresherPosting(String company) {
        if (redis == null) {
            return;
        }
        String key = weekKey(LocalDate.now(clock.withZone(INDIA)));
        guard.call(2, () -> {
            redis.opsForZSet().incrementScore(key, company, 1);
            redis.expire(key, Duration.ofDays(21));
            return true;
        });
    }

    public List<Entry> top(int n) {
        if (redis == null) {
            return List.of();
        }
        String key = weekKey(LocalDate.now(clock.withZone(INDIA)));
        Set<TypedTuple<String>> top = guard.call(1, () -> redis.opsForZSet().reverseRangeWithScores(key, 0, n - 1))
                .orElse(Set.of());
        return top.stream()
                .map(t -> new Entry(t.getValue(), t.getScore() == null ? 0 : t.getScore().longValue()))
                .toList();
    }

    String weekKey(LocalDate day) {
        return guard.key(String.format("hiring:%d-W%02d",
                day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
    }
}
