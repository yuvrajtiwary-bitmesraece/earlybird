package io.github.yuvrajtiwary.earlybird.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.yuvrajtiwary.earlybird.alerts.DailyCaps;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import io.github.yuvrajtiwary.earlybird.common.TokenBucketRateLimiter;
import io.github.yuvrajtiwary.earlybird.enrich.HiringRanking;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The Redis-backed pieces against a real Redis (CI only): the Lua token bucket, daily caps, the ranking. */
@Testcontainers(disabledWithoutDocker = true)
class RedisIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T08:30:00Z"));
    private final RedisGuard guard = new RedisGuard("it-" + UUID.randomUUID(), clock, new SimpleMeterRegistry());

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    private RedisRateLimiter limiter() {
        return new RedisRateLimiter(redis, guard, new TokenBucketRateLimiter(clock), clock);
    }

    @Test
    void theBucketIsSharedBetweenProcesses() {
        // Two limiters = two app instances; together they may only take the bucket's 5 tokens.
        RedisRateLimiter one = limiter();
        RedisRateLimiter two = limiter();
        Bandwidth bw = Bandwidth.perSecond(5);
        int granted = 0;
        for (int i = 0; i < 6; i++) {
            if ((i % 2 == 0 ? one : two).tryAcquire("tg:global", bw).isZero()) {
                granted++;
            }
        }
        assertThat(granted).isEqualTo(5);
        assertThat(one.tryAcquire("tg:global", bw)).isEqualTo(Duration.ofMillis(200)); // one token per 200 ms

        clock.advance(Duration.ofSeconds(1));
        assertThat(two.tryAcquire("tg:global", bw)).isZero();
    }

    @Test
    void theLuaScriptIsAtomicUnderConcurrency() throws Exception {
        RedisRateLimiter shared = limiter();
        Bandwidth bw = Bandwidth.perSecond(10); // clock frozen: exactly 10 tokens exist
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < 32; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        if (shared.tryAcquire("race", bw).isZero()) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(granted).hasValue(10);
    }

    @Test
    void dailyCapsCountPerUserPerDay() {
        DailyCaps caps = new DailyCaps(redis, guard, mock(AlertRepository.class), clock);

        assertThat(caps.tryReserve(1, 2)).isTrue();
        assertThat(caps.tryReserve(1, 2)).isTrue();
        assertThat(caps.tryReserve(1, 2)).isFalse();
        assertThat(caps.tryReserve(2, 2)).as("another user").isTrue();

        clock.advance(Duration.ofDays(1));
        assertThat(caps.tryReserve(1, 2)).as("a new day").isTrue();
    }

    @Test
    void theHiringRankingOrdersCompaniesByFresherPostings() {
        HiringRanking ranking = new HiringRanking(redis, guard, clock);
        ranking.recordFresherPosting("Acme");
        ranking.recordFresherPosting("Beta");
        ranking.recordFresherPosting("Acme");

        assertThat(ranking.top(5)).containsExactly(new HiringRanking.Entry("Acme", 2), new HiringRanking.Entry("Beta", 1));
    }

    @Test
    void whenRedisIsUnreachableTheLocalFallbackAnswers() {
        var deadFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        deadFactory.afterPropertiesSet();
        deadFactory.start();
        var dead = new StringRedisTemplate(deadFactory);
        dead.afterPropertiesSet();
        var limiter = new RedisRateLimiter(dead, guard, new TokenBucketRateLimiter(clock), clock);

        assertThat(limiter.tryAcquire("tg:global", Bandwidth.perSecond(5))).isZero();
        assertThat(guard.coolingDown()).isTrue(); // next calls skip Redis for a while instead of timing out each time
        deadFactory.destroy();
    }

    /** Spec 3.3: SET NX PX, one holder at a time, and only the owner can release. */
    @Test
    void theRedisLockIsExclusiveAndOnlyTheOwnerReleasesIt() {
        var fallback = new io.github.yuvrajtiwary.earlybird.ops.DistributedLock() {
            int calls;

            @Override
            public java.util.Optional<Handle> tryAcquire(String name, Duration ttl) {
                calls++;
                return java.util.Optional.empty();
            }
        };
        var one = new io.github.yuvrajtiwary.earlybird.ops.RedisLock(redis, guard, fallback);
        var two = new io.github.yuvrajtiwary.earlybird.ops.RedisLock(redis, guard, fallback);

        var held = one.tryAcquire("tick", Duration.ofMinutes(1));
        assertThat(held).isPresent();
        assertThat(two.tryAcquire("tick", Duration.ofMinutes(1))).as("another instance").isEmpty();
        assertThat(redis.getExpire(guard.key("lock:tick"))).isBetween(1L, 60L); // the lease is set

        held.get().close();
        var again = two.tryAcquire("tick", Duration.ofMinutes(1));
        assertThat(again).isPresent();
        held.get().close(); // a stale handle must not release the new holder's lock
        assertThat(one.tryAcquire("tick", Duration.ofMinutes(1))).isEmpty();
        again.get().close();
        assertThat(fallback.calls).as("Redis was up, so the database was never asked").isZero();
    }
}
