package io.github.yuvrajtiwary.earlybird.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import io.github.yuvrajtiwary.earlybird.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T08:30:00Z"));
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);

    @Test
    void allowsABurstUpToCapacityThenAsksToWait() {
        Bandwidth telegramGlobal = Bandwidth.perSecond(25);
        for (int i = 0; i < 25; i++) {
            assertThat(limiter.tryAcquire("tg:global", telegramGlobal)).isZero();
        }
        // One token refills every 1000/25 = 40 ms.
        assertThat(limiter.tryAcquire("tg:global", telegramGlobal)).isEqualTo(Duration.ofMillis(40));
    }

    @Test
    void refillsOverTime() {
        Bandwidth bw = Bandwidth.perSecond(25);
        for (int i = 0; i < 25; i++) {
            limiter.tryAcquire("k", bw);
        }
        clock.advance(Duration.ofMillis(40));
        assertThat(limiter.tryAcquire("k", bw)).isZero();
        assertThat(limiter.tryAcquire("k", bw)).isPositive();

        clock.advance(Duration.ofSeconds(10)); // refill is capped at capacity, not 250 tokens
        int granted = 0;
        while (limiter.tryAcquire("k", bw).isZero()) {
            granted++;
        }
        assertThat(granted).isEqualTo(25);
    }

    @Test
    void perChatPacingAllowsOneMessagePerSecond() {
        Bandwidth perChat = Bandwidth.perSecond(1);
        assertThat(limiter.tryAcquire("chat:42", perChat)).isZero();
        assertThat(limiter.tryAcquire("chat:42", perChat)).isEqualTo(Duration.ofSeconds(1));
        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire("chat:42", perChat)).isEqualTo(Duration.ofMillis(1));
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("chat:42", perChat)).isZero();
    }

    @Test
    void keysHaveSeparateBuckets() {
        Bandwidth perChat = Bandwidth.perSecond(1);
        assertThat(limiter.tryAcquire("chat:1", perChat)).isZero();
        assertThat(limiter.tryAcquire("chat:2", perChat)).isZero();
        assertThat(limiter.tryAcquire("chat:1", perChat)).isPositive();
    }

    @Test
    void neverGrantsMoreThanCapacityUnderConcurrency() throws Exception {
        Bandwidth bw = Bandwidth.perSecond(25); // the clock is frozen, so exactly 25 tokens exist
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < 64; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        if (limiter.tryAcquire("tg:global", bw).isZero()) {
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
        assertThat(granted).hasValue(25);
    }

    @Test
    void rejectsNonsenseBandwidths() {
        assertThatThrownBy(() -> new Bandwidth(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bandwidth(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
