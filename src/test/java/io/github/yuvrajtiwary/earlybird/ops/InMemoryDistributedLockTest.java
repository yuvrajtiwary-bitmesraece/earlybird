package io.github.yuvrajtiwary.earlybird.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.ops.DistributedLock.Handle;
import io.github.yuvrajtiwary.earlybird.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryDistributedLockTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T08:30:00Z"));
    private final InMemoryDistributedLock lock = new InMemoryDistributedLock(clock);

    @Test
    void onlyOneCallerHoldsTheLock() {
        assertThat(lock.tryAcquire("tick", TTL)).isPresent();
        assertThat(lock.tryAcquire("tick", TTL)).isEmpty(); // the overlapping tick is skipped
    }

    @Test
    void releasingLetsTheNextCallerIn() {
        try (Handle ignored = lock.tryAcquire("tick", TTL).orElseThrow()) {
            assertThat(lock.tryAcquire("tick", TTL)).isEmpty();
        }
        assertThat(lock.tryAcquire("tick", TTL)).isPresent();
    }

    @Test
    void anExpiredLeaseCanBeTakenOver() {
        assertThat(lock.tryAcquire("tick", TTL)).isPresent(); // holder "crashes" and never releases
        clock.advance(TTL);
        assertThat(lock.tryAcquire("tick", TTL)).isPresent();
    }

    @Test
    void aStaleHolderCannotReleaseSomeoneElsesLock() {
        Handle stale = lock.tryAcquire("tick", TTL).orElseThrow();
        clock.advance(TTL.plusSeconds(1));
        Handle current = lock.tryAcquire("tick", TTL).orElseThrow();

        stale.close(); // must not remove the current holder's lease
        assertThat(lock.tryAcquire("tick", TTL)).isEmpty();

        current.close();
        assertThat(lock.tryAcquire("tick", TTL)).isPresent();
    }

    @Test
    void differentNamesAreIndependent() {
        assertThat(lock.tryAcquire("tick", TTL)).isPresent();
        assertThat(lock.tryAcquire("digest", TTL)).isPresent();
    }

    @Test
    void rejectsANonPositiveTtl() {
        assertThatThrownBy(() -> lock.tryAcquire("tick", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
